package space.br1440.platform.tracing.otel.javaagent.resource;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.sdk.autoconfigure.ResourceConfiguration;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.resources.Resource;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.utils.Strings;

import java.util.Optional;

/**
 * Резолвер платформенных resource-атрибутов идентичности с семантикой <b>per-key omit</b>.
 * <p>
 * Принимает {@link ConfigProperties} и текущий накопленный {@link Resource} (то, что собрали
 * провайдеры с меньшим {@code order()}), и возвращает {@link Attributes} только для тех ключей,
 * которые платформа должна дополнить. Ключ <b>пропускается</b> (omit), если его уже задал
 * {@code OTEL_*} (через {@code OTEL_SERVICE_NAME} / {@code OTEL_RESOURCE_ATTRIBUTES}) или
 * предыдущий провайдер. Это исключает перетирание явной пользовательской конфигурации.
 *
 * <p>Проверка «задан ли ключ через {@code OTEL_*}» делается через публичный
 * {@link ResourceConfiguration#createEnvironmentResource(ConfigProperties)} — та же логика парсинга,
 * что у штатного {@code EnvironmentResourceProvider} (percent-decoding, приоритет
 * {@code otel.service.name}), без ручного разбора строк.
 *
 * <p>DTO-слои сознательно не вводятся: вход — {@code ConfigProperties} + {@code Resource},
 * выход — {@code Attributes}. Валидация required-ключей вынесена в
 * {@link ResourceValidationDiagnostics} (учитывает effective-view, а не только результат resolve).
 */
public final class ResourceAttributeResolver {

    /** SDK-дефолт {@code service.name}, который не считается «настоящим» значением. */
    static final String SDK_UNKNOWN_SERVICE = "unknown_service:java";

    private static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey(PlatformAttributes.SERVICE_NAME);
    private static final AttributeKey<String> SERVICE_VERSION = AttributeKey.stringKey(PlatformAttributes.SERVICE_VERSION);
    private static final AttributeKey<String> ENVIRONMENT = AttributeKey.stringKey(PlatformAttributes.PLATFORM_ENVIRONMENT);
    private static final AttributeKey<String> C_GROUP = AttributeKey.stringKey(PlatformAttributes.PLATFORM_C_GROUP);
    private static final AttributeKey<String> PLATFORM_ID = AttributeKey.stringKey(PlatformAttributes.PLATFORM_ID);
    private static final AttributeKey<String> POLICY_VERSION =
            AttributeKey.stringKey(ExtensionPropertyNames.RESOURCE_POLICY_VERSION_ATTR);

    /** Свойство Spring с именем приложения — fallback для {@code service.name} в Starter-сценарии. */
    private static final String PROP_SPRING_APPLICATION_NAME = "spring.application.name";

    public ResourceAttributeResolver() {
        // нормализация environment — статическая утилита, состояние не требуется
    }

    /**
     * Собирает платформенные resource-атрибуты, пропуская ключи, заданные через {@code OTEL_*}
     * или предыдущими провайдерами.
     *
     * @param config           конфигурация OTel autoconfigure
     * @param existing         накопленный на этот момент Resource
     * @param buildInfoVersion версия из {@code build-info.properties} (PR-2), если найдена
     * @param manifestVersion  версия из {@code MANIFEST.MF} (PR-2), если найдена
     * @return атрибуты, которые платформа дополняет (без перетираемых OTEL-ключей)
     */
    public Attributes resolve(ConfigProperties config,
                              Resource existing,
                              Optional<String> buildInfoVersion,
                              Optional<String> manifestVersion) {
        Resource otelEnv = ResourceConfiguration.createEnvironmentResource(config);
        boolean normalize = config.getBoolean(
                ExtensionPropertyNames.RESOURCE_NORMALIZE_ENVIRONMENT,
                ExtensionDefaults.DEFAULT_RESOURCE_NORMALIZE_ENVIRONMENT);

        AttributesBuilder builder = Attributes.builder();

        resolveServiceName(config, existing, otelEnv)
                .ifPresent(v -> builder.put(SERVICE_NAME, v));
        resolveServiceVersion(config, existing, otelEnv, buildInfoVersion, manifestVersion)
                .ifPresent(v -> builder.put(SERVICE_VERSION, v));
        resolveEnvironment(config, otelEnv, normalize)
                .ifPresent(v -> builder.put(ENVIRONMENT, v));

        // platform.c_group / platform.id — платформенный namespace; OTEL_* их не задаёт.
        builder.put(C_GROUP, resolveCGroup(config).orElse(EnvironmentNormalizer.UNKNOWN));
        builder.put(PLATFORM_ID, resolvePlatformId(config).orElse(EnvironmentNormalizer.UNKNOWN));

        // policy.version — governance-версия контракта идентичности, добавляется всегда.
        builder.put(POLICY_VERSION, policyVersion(config));

        return builder.build();
    }

    /**
     * Есть ли у резолвера работа — используется в {@code shouldApply()}.
     * <p>
     * Платформенный provider всегда дополняет governance-атрибут {@code policy.version}, поэтому
     * если его ещё нет в {@code existing} — работа есть. Дополнительно работа есть, когда можно
     * дополнить {@code service.name} или заданы платформенные {@code c_group}/{@code id}.
     */
    public boolean hasWorkToDo(ConfigProperties config, Resource existing) {
        if (existing.getAttribute(POLICY_VERSION) == null) {
            return true;
        }
        Resource otelEnv = ResourceConfiguration.createEnvironmentResource(config);
        return resolveServiceName(config, existing, otelEnv).isPresent()
                || present(config.getString(ExtensionPropertyNames.RESOURCE_POLICY_VERSION))
                || resolveCGroup(config).isPresent()
                || resolvePlatformId(config).isPresent();
    }

    // -- package-private резолверы отдельных ключей (точки для unit-тестов) ----------------------

    Optional<String> resolveServiceName(ConfigProperties config, Resource existing, Resource otelEnv) {
        if (otelEnv.getAttribute(SERVICE_NAME) != null) {
            return Optional.empty(); // OTEL explicit — не перетираем
        }
        String existingName = existing.getAttribute(SERVICE_NAME);
        if (present(existingName) && !SDK_UNKNOWN_SERVICE.equals(existingName)) {
            return Optional.empty(); // уже задан не-дефолтным провайдером
        }
        String platform = config.getString(PlatformResourceProvider.PROP_SERVICE_NAME);
        if (present(platform)) {
            return Optional.of(platform.trim());
        }
        String spring = config.getString(PROP_SPRING_APPLICATION_NAME);
        if (present(spring)) {
            return Optional.of(spring.trim());
        }
        return Optional.empty();
    }

    Optional<String> resolveServiceVersion(ConfigProperties config,
                                           Resource existing,
                                           Resource otelEnv,
                                           Optional<String> buildInfoVersion,
                                           Optional<String> manifestVersion) {
        if (otelEnv.getAttribute(SERVICE_VERSION) != null || present(existing.getAttribute(SERVICE_VERSION))) {
            return Optional.empty();
        }
        String platform = config.getString(PlatformResourceProvider.PROP_SERVICE_VERSION);
        if (present(platform)) {
            return Optional.of(platform.trim());
        }
        if (buildInfoVersion.isPresent() && present(buildInfoVersion.get())) {
            return Optional.of(buildInfoVersion.get().trim());
        }
        if (manifestVersion.isPresent() && present(manifestVersion.get())) {
            return Optional.of(manifestVersion.get().trim());
        }
        return Optional.empty();
    }

    Optional<String> resolveEnvironment(ConfigProperties config, Resource otelEnv, boolean normalize) {
        if (otelEnv.getAttribute(ENVIRONMENT) != null) {
            return Optional.empty(); // OTEL explicit
        }
        String platform = config.getString(PlatformResourceProvider.PROP_ENVIRONMENT);
        return Optional.of(EnvironmentNormalizer.normalize(platform, normalize));
    }

    Optional<String> resolveCGroup(ConfigProperties config) {
        String value = config.getString(PlatformResourceProvider.PROP_C_GROUP);
        return present(value) ? Optional.of(value.trim()) : Optional.empty();
    }

    Optional<String> resolvePlatformId(ConfigProperties config) {
        String value = config.getString(PlatformResourceProvider.PROP_ID);
        return present(value) ? Optional.of(value.trim()) : Optional.empty();
    }

    private static String policyVersion(ConfigProperties config) {
        String value = config.getString(ExtensionPropertyNames.RESOURCE_POLICY_VERSION);
        return present(value) ? value.trim() : ExtensionDefaults.DEFAULT_RESOURCE_POLICY_VERSION;
    }

    private static boolean present(String value) {
        return Strings.isNotBlank(value);
    }
}
