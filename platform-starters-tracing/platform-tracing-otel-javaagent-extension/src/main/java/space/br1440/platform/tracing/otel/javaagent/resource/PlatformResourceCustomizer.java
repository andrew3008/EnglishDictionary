package space.br1440.platform.tracing.otel.javaagent.resource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.configuration.ResourceValidationMode;
import space.br1440.platform.tracing.otel.javaagent.diagnostics.TracingDiagnostics;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogFields;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.event.ResourceIdentityLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.sdk.autoconfigure.ResourceConfiguration;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.resources.Resource;

/**
 * Дополняет ресурс SDK identity-атрибутами платформы.
 *
 * <p>Порядок приоритетов одинаков для каждого атрибута:
 * <ol>
 *   <li>явная OTel-конфигурация ({@code otel.service.name}, {@code otel.resource.attributes}) — атрибут не меняется;</li>
 *   <li>свойство {@code platform.tracing.service.*} — перекрывает значение, уже присутствующее в ресурсе;</li>
 *   <li>значение, уже присутствующее в ресурсе;</li>
 *   <li>локальный детектор.</li>
 * </ol>
 *
 * <p>Отсутствующее значение не записывается.
 * Обязательные атрибуты проверяются по итоговому ресурсу после слияния.
 */
public final class PlatformResourceCustomizer {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(PlatformResourceCustomizer.class);

    private static final String OTEL_RESOURCE_DISABLED_KEYS = "otel.resource.disabled.keys";
    private static final String SDK_UNKNOWN_SERVICE_PREFIX = "unknown_service";
    private static final String ENV_HOSTNAME = "HOSTNAME";
    private static final String ENV_COMPUTERNAME = "COMPUTERNAME";

    private static final AttributeKey<String> SERVICE_NAME_KEY = AttributeKey.stringKey(PlatformAttributes.SERVICE_NAME);
    private static final AttributeKey<String> SERVICE_VERSION_KEY = AttributeKey.stringKey(PlatformAttributes.SERVICE_VERSION);
    private static final AttributeKey<String> ENVIRONMENT_KEY = AttributeKey.stringKey(PlatformAttributes.PLATFORM_ENVIRONMENT);
    private static final AttributeKey<String> HOST_NAME_KEY = AttributeKey.stringKey(PlatformAttributes.PLATFORM_HOST);
    private static final AttributeKey<String> CONTAINER_ID_KEY = AttributeKey.stringKey(PlatformAttributes.CONTAINER_ID);

    private static final Supplier<Optional<String>> NO_DETECTOR = Optional::empty;

    private final List<IdentityAttribute> attributes;
    private final TracingDiagnostics diagnostics;

    public PlatformResourceCustomizer() {
        this(System::getenv, new BuildInfoReader()::readVersion, TracingDiagnostics.shared());
    }

    PlatformResourceCustomizer(UnaryOperator<String> environment,
                               Supplier<Optional<String>> buildVersion,
                               TracingDiagnostics diagnostics) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(buildVersion, "buildVersion");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.attributes = List.of(
                new IdentityAttribute(
                        SERVICE_NAME_KEY, ExtensionPropertyNames.SERVICE_NAME,
                        String::strip, PlatformResourceCustomizer::isRealServiceName, true, NO_DETECTOR
                ),

                new IdentityAttribute(
                        SERVICE_VERSION_KEY, ExtensionPropertyNames.SERVICE_VERSION,
                        String::strip, StringUtils::hasText, false, buildVersion
                ),

                new IdentityAttribute(
                        ENVIRONMENT_KEY, ExtensionPropertyNames.SERVICE_ENVIRONMENT,
                        PlatformResourceCustomizer::canonicalEnvironment, StringUtils::hasText, true, NO_DETECTOR
                ),

                new IdentityAttribute(
                        HOST_NAME_KEY, ExtensionPropertyNames.SERVICE_HOST,
                        String::strip, StringUtils::hasText, false, () -> hostName(environment)
                ),

                new IdentityAttribute(
                        CONTAINER_ID_KEY, ExtensionPropertyNames.SERVICE_CONTAINER_ID,
                        String::strip, StringUtils::hasText, false, NO_DETECTOR
                )
        );
    }

    public Resource customize(Resource existing, ConfigProperties config, ResourceValidationMode mode) {
        Objects.requireNonNull(existing, "existing");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(mode, "mode");

        try {
            return doCustomize(existing, config, mode);
        } catch (RuntimeException failure) {
            if (mode == ResourceValidationMode.ERROR) {
                throw failure;
            }

            log.log(ResourceIdentityLogEvent.RESOURCE_CUSTOMIZATION_FAILED, fields -> fields.cause(failure));
            diagnostics.recordResourceFailure();
            return existing;
        }
    }

    private Resource doCustomize(Resource existing, ConfigProperties config, ResourceValidationMode mode) {
        Resource otelEnv = ResourceConfiguration.createEnvironmentResource(config);

        AttributesBuilder additions = Attributes.builder();
        for (IdentityAttribute attribute : attributes) {
            resolve(attribute, config, otelEnv, existing)
                    .ifPresent(value -> additions.put(attribute.key(), value));
        }

        Attributes projection = withoutDisabledKeys(additions.build(), disabledKeys(config));
        Resource merged = existing.merge(Resource.create(projection));
        validate(merged, mode);
        return merged;
    }

    private static Optional<String> resolve(IdentityAttribute attribute,
                                            ConfigProperties config,
                                            Resource otelEnv,
                                            Resource existing) {
        if (otelEnv.getAttribute(attribute.key()) != null) {
            return Optional.empty();
        }

        String platform = config.getString(attribute.platformProperty());
        if (StringUtils.hasText(platform)) {
            return Optional.of(attribute.normalizer().apply(platform));
        }

        if (attribute.isPresent(existing.getAttribute(attribute.key()))) {
            return Optional.empty();
        }

        return attribute.detector().get()
                .filter(StringUtils::hasText)
                .map(attribute.normalizer());
    }

    private void validate(Resource merged, ResourceValidationMode mode) {
        List<String> missing = new ArrayList<>();
        for (IdentityAttribute attribute : attributes) {
            if (attribute.required() && !attribute.isPresent(merged.getAttribute(attribute.key()))) {
                missing.add(attribute.key().getKey());
            }
        }

        if (missing.isEmpty()) {
            log.log(ResourceIdentityLogEvent.RESOURCE_IDENTITY_RESOLVED, fields -> identityFields(fields, merged));
            return;
        }

        String keys = String.join(", ", missing);
        if (mode == ResourceValidationMode.ERROR) {
            throw new IllegalStateException(
                    "Resource identity failed ERROR validation; missing required keys [" + keys + "]"
            );
        }

        log.log(ResourceIdentityLogEvent.RESOURCE_IDENTITY_INCOMPLETE,
                fields -> identityFields(fields, merged).putString(AgentLogField.MISSING_KEYS, keys));
    }

    private static AgentLogFields identityFields(AgentLogFields fields, Resource merged) {
        String service = merged.getAttribute(SERVICE_NAME_KEY);
        if (isRealServiceName(service)) {
            fields.putString(AgentLogField.SERVICE, service);
        }

        putIfHasText(fields, AgentLogField.ENVIRONMENT, merged.getAttribute(ENVIRONMENT_KEY));
        putIfHasText(fields, AgentLogField.VERSION, merged.getAttribute(SERVICE_VERSION_KEY));
        return fields;
    }

    private static void putIfHasText(AgentLogFields fields, AgentLogField field, String value) {
        if (StringUtils.hasText(value)) {
            fields.putString(field, value);
        }
    }

    private static boolean isRealServiceName(String value) {
        return StringUtils.hasText(value) && !value.startsWith(SDK_UNKNOWN_SERVICE_PREFIX);
    }

    private static String canonicalEnvironment(String raw) {
        return raw.strip().toLowerCase(Locale.ROOT);
    }

    private static Optional<String> hostName(UnaryOperator<String> environment) {
        String hostname = environment.apply(ENV_HOSTNAME);
        if (StringUtils.hasText(hostname)) {
            return Optional.of(hostname);
        }

        String computerName = environment.apply(ENV_COMPUTERNAME);
        return StringUtils.hasText(computerName) ? Optional.of(computerName) : Optional.empty();
    }

    private static Set<String> disabledKeys(ConfigProperties config) {
        Set<String> keys = new HashSet<>();
        for (String key : config.getList(OTEL_RESOURCE_DISABLED_KEYS)) {
            if (StringUtils.hasText(key)) {
                keys.add(key.strip());
            }
        }

        return Set.copyOf(keys);
    }

    private static Attributes withoutDisabledKeys(Attributes attributes, Set<String> disabledKeys) {
        if (disabledKeys.isEmpty() || attributes.isEmpty()) {
            return attributes;
        }

        return attributes.toBuilder()
                .removeIf(key -> disabledKeys.contains(key.getKey()))
                .build();
    }

    private record IdentityAttribute(AttributeKey<String> key,
                                     String platformProperty,
                                     UnaryOperator<String> normalizer,
                                     Predicate<String> presence,
                                     boolean required,
                                     Supplier<Optional<String>> detector) {

        private IdentityAttribute {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(platformProperty, "platformProperty");
            Objects.requireNonNull(normalizer, "normalizer");
            Objects.requireNonNull(presence, "presence");
            Objects.requireNonNull(detector, "detector");
        }

        boolean isPresent(String value) {
            return presence.test(value);
        }
    }
}
