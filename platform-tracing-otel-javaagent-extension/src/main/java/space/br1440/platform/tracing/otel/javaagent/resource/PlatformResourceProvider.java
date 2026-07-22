package space.br1440.platform.tracing.otel.javaagent.resource;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.sdk.autoconfigure.ResourceConfiguration;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.ResourceProvider;
import io.opentelemetry.sdk.autoconfigure.spi.internal.ConditionalResourceProvider;
import io.opentelemetry.sdk.resources.Resource;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.utils.Strings;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Платформенный {@link ResourceProvider} идентичности сервиса (Фаза 9, Resource Model).
 * <p>
 * Реализует {@link ConditionalResourceProvider} и работает с {@code order()=100} — дополняет
 * resource, не перетирая явный {@code OTEL_*} (per-key omit в {@link ResourceAttributeResolver}).
 * Контракт merge и precedence — см. {@code docs/decisions/ADR-resource-merge-precedence.md}.
 *
 * <h2>Что заполняет</h2>
 * <ul>
 *   <li>identity: {@code service.name}, {@code service.version}, {@code deployment.environment.name},
 *       {@code platform.c_group}, {@code platform.id}, {@code platform.tracing.policy.version}
 *       (через {@link ResourceAttributeResolver});</li>
 *   <li>{@code host.name} — omit-if-unknown (через {@link HostNameResolver}); не трогается, если уже
 *       задан предыдущим провайдером (OTel Host detector);</li>
 *   <li>{@code container.id} — opt-in (explicit config/env всегда; procfs только при
 *       {@code platform.tracing.resource.detect-container-id=true}).</li>
 * </ul>
 *
 * <h2>Что НЕ заполняет</h2>
 * {@code k8s.pod.uid} (зона Collector {@code k8sattributes} / Downward API), {@code service.instance.id}
 * (SDK {@code ServiceInstanceIdResourceProvider}), {@code telemetry.sdk.*}.
 *
 * <h2>ConditionalResourceProvider (controlled risk)</h2>
 * Интерфейс живёт в {@code io.opentelemetry.sdk.autoconfigure.spi.internal} (нестабильный SPI).
 * Используется осознанно: {@link #shouldApply} — лишь оптимизация. Корректность обеспечивает
 * per-key omit в {@link #createResource}; при исчезновении SPI провайдер деградирует до обычного
 * {@link ResourceProvider} без потери корректности.
 *
 * <p>{@code existing} Resource доступен только в {@link #shouldApply}; он захватывается в поле и
 * переиспользуется в {@link #createResource} (ResourceConfiguration вызывает их последовательно
 * на однопоточной инициализации SDK).
 */
public final class PlatformResourceProvider implements ResourceProvider, ConditionalResourceProvider {

    /** Свойство, явно задающее имя сервиса (поверх стандартного {@code otel.service.name}). */
    public static final String PROP_SERVICE_NAME = "platform.tracing.service.name";
    public static final String PROP_SERVICE_VERSION = "platform.tracing.service.version";
    public static final String PROP_ENVIRONMENT = "platform.tracing.service.environment";
    public static final String PROP_C_GROUP = "platform.tracing.service.c-group";
    public static final String PROP_ID = "platform.tracing.service.id";
    public static final String PROP_HOST = "platform.tracing.service.host";
    public static final String PROP_CONTAINER_ID = "platform.tracing.service.container-id";

    /** Переменная окружения с явным runtime ID контейнера (не Pod UID). */
    private static final String ENV_CONTAINER_ID = "CONTAINER_ID";

    private static final AttributeKey<String> HOST_NAME_KEY = AttributeKey.stringKey(PlatformAttributes.PLATFORM_HOST);
    private static final AttributeKey<String> CONTAINER_ID_KEY = AttributeKey.stringKey(PlatformAttributes.CONTAINER_ID);

    private final ResourceAttributeResolver resolver;
    private final HostNameResolver hostNameResolver;
    private final ResourceValidationDiagnostics validation;
    private final Function<String, String> envReader;
    private final Supplier<Optional<String>> containerIdDetector;
    // Memoized version readers: shouldApply() и createResource() оба спрашивают версию —
    // мемоизация исключает повторный classpath I/O.
    private final Supplier<Optional<String>> buildVersion;
    private final Supplier<Optional<String>> manifestVersion;

    /** Захваченный в shouldApply накопленный Resource (см. Javadoc класса). */
    private volatile Resource lastSeenExisting = Resource.empty();

    public PlatformResourceProvider() {
        this(new ResourceAttributeResolver(),
                new HostNameResolver(),
                new ResourceValidationDiagnostics(),
                System::getenv,
                new ProcfsContainerIdDetector()::detect,
                memoize(new BuildInfoReader()::readVersion),
                memoize(new ManifestVersionReader()::readVersion));
    }

    /** Конструктор для unit-тестов: подмена всех зависимостей. */
    PlatformResourceProvider(ResourceAttributeResolver resolver,
                             HostNameResolver hostNameResolver,
                             ResourceValidationDiagnostics validation,
                             Function<String, String> envReader,
                             Supplier<Optional<String>> containerIdDetector,
                             Supplier<Optional<String>> buildVersion,
                             Supplier<Optional<String>> manifestVersion) {
        this.resolver = resolver;
        this.hostNameResolver = hostNameResolver;
        this.validation = validation;
        this.envReader = envReader;
        this.containerIdDetector = containerIdDetector;
        this.buildVersion = buildVersion;
        this.manifestVersion = manifestVersion;
    }

    @Override
    public boolean shouldApply(ConfigProperties config, Resource existing) {
        this.lastSeenExisting = existing;
        return resolver.hasWorkToDo(config, existing);
    }

    @Override
    public Resource createResource(ConfigProperties config) {
        Resource existing = this.lastSeenExisting;
        Resource otelEnv = ResourceConfiguration.createEnvironmentResource(config);

        Attributes identity = resolver.resolve(config, existing, buildVersion.get(), manifestVersion.get());
        AttributesBuilder builder = identity.toBuilder();

        // host.name: не трогаем, если уже задан предыдущим провайдером (OTel Host detector);
        // иначе explicit config → omit-if-unknown резолвер. Фейковый "unknown" не пишем.
        if (existing.getAttribute(HOST_NAME_KEY) == null) {
            String explicitHost = config.getString(PROP_HOST);
            if (present(explicitHost)) {
                builder.put(HOST_NAME_KEY, explicitHost.trim().toLowerCase(Locale.ROOT));
            } else {
                hostNameResolver.resolve().ifPresent(h -> builder.put(HOST_NAME_KEY, h));
            }
        }

        // container.id: explicit всегда; procfs — только при opt-in флаге.
        resolveContainerId(config).ifPresent(id -> builder.put(CONTAINER_ID_KEY, id));

        Attributes finalAttrs = builder.build();

        // Диагностика + валидация по effective-view (resolved + otelEnv + existing).
        ResourceValidationMode mode = ResourceValidationMode.fromConfig(
                config.getString(ExtensionPropertyNames.RESOURCE_VALIDATION_MODE));
        ResourceValidationDiagnostics.ValidationResult result =
                validation.validate(finalAttrs, otelEnv, existing, mode);
        ResourceStartupDiagnostics.emit(
                ResourceStartupDiagnostics.from(finalAttrs, result.missingKeys().size(), mode));
        validation.applyOrThrow(result);

        return Resource.create(finalAttrs);
    }

    /**
     * Дополняет, а не перетирает: {@code order()=100} (как OTel SpringBoot detectors). Явный
     * {@code OTEL_*} применяется {@code EnvironmentResourceProvider} (order=MAX-1) и побеждает.
     */
    @Override
    public int order() {
        return 100;
    }

    /**
     * Резолв {@code container.id}: explicit config/env → (opt-in) procfs → omit.
     */
    private Optional<String> resolveContainerId(ConfigProperties config) {
        String explicit = firstNonBlank(config.getString(PROP_CONTAINER_ID), envReader.apply(ENV_CONTAINER_ID));
        if (explicit != null) {
            return Optional.of(explicit);
        }
        boolean detect = config.getBoolean(
                ExtensionPropertyNames.RESOURCE_DETECT_CONTAINER_ID,
                ExtensionDefaults.DEFAULT_RESOURCE_DETECT_CONTAINER_ID);
        if (!detect) {
            return Optional.empty();
        }
        return containerIdDetector.get();
    }

    private static String firstNonBlank(String a, String b) {
        if (Strings.isNotBlank(a)) {
            return a;
        }
        return Strings.isNotBlank(b) ? b : null;
    }

    private static boolean present(String value) {
        return Strings.isNotBlank(value);
    }

    /** Минимальная потокобезопасная мемоизация результата supplier'а. */
    private static <T> Supplier<T> memoize(Supplier<T> delegate) {
        return new Supplier<>() {
            private volatile boolean computed;
            private T value;

            @Override
            public T get() {
                if (!computed) {
                    synchronized (this) {
                        if (!computed) {
                            value = delegate.get();
                            computed = true;
                        }
                    }
                }
                return value;
            }
        };
    }
}
