package space.br1440.platform.tracing.otel.javaagent.resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.resources.Resource;
import lombok.experimental.UtilityClass;

@UtilityClass
final class ResourceAttributeResolver {

    static final String SDK_UNKNOWN_SERVICE = "unknown_service:java";

    private static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey(PlatformAttributes.SERVICE_NAME);
    private static final AttributeKey<String> SERVICE_VERSION = AttributeKey.stringKey(PlatformAttributes.SERVICE_VERSION);
    private static final AttributeKey<String> ENVIRONMENT = AttributeKey.stringKey(PlatformAttributes.PLATFORM_ENVIRONMENT);
    private static final AttributeKey<String> PLATFORM_ID = AttributeKey.stringKey(PlatformAttributes.PLATFORM_ID);
    private static final String PROP_SPRING_APPLICATION_NAME = "spring.application.name";

    static Attributes resolve(ConfigProperties config,
                              Resource existing,
                              Resource otelEnv,
                              Supplier<Optional<String>> buildVersion) {
        boolean normalize = config.getBoolean(
                ExtensionPropertyNames.RESOURCE_NORMALIZE_ENVIRONMENT,
                ExtensionDefaults.DEFAULT_RESOURCE_NORMALIZE_ENVIRONMENT
        );

        AttributesBuilder builder = Attributes.builder();

        resolveServiceName(config, existing, otelEnv)
                .ifPresent(value -> builder.put(SERVICE_NAME, value));

        resolveServiceVersion(config, existing, otelEnv, buildVersion)
                .ifPresent(value -> builder.put(SERVICE_VERSION, value));

        resolveEnvironment(config, existing, otelEnv, normalize)
                .ifPresent(value -> builder.put(ENVIRONMENT, value));

        if (existing.getAttribute(PLATFORM_ID) == null) {
            builder.put(PLATFORM_ID, resolvePlatformId(config).orElse(EnvironmentNormalizer.UNKNOWN));
        }

        return builder.build();
    }

    static List<String> missingRequiredKeys(Attributes resolved, Resource otelEnv, Resource existing) {
        List<String> missing = new ArrayList<>();

        if (!hasRealServiceName(resolved, otelEnv, existing)) {
            missing.add(PlatformAttributes.SERVICE_NAME);
        }

        if (isUnknownOrBlank(resolved.get(ENVIRONMENT), otelEnv.getAttribute(ENVIRONMENT), existing.getAttribute(ENVIRONMENT))) {
            missing.add(PlatformAttributes.PLATFORM_ENVIRONMENT);
        }

        return List.copyOf(missing);
    }

    private static Optional<String> resolveServiceName(ConfigProperties config, Resource existing, Resource otelEnv) {
        if (otelEnv.getAttribute(SERVICE_NAME) != null) {
            return Optional.empty();
        }

        String existingName = existing.getAttribute(SERVICE_NAME);
        if (StringUtils.hasText(existingName) && !SDK_UNKNOWN_SERVICE.equals(existingName)) {
            return Optional.empty();
        }

        String platform = config.getString(PlatformResourceCustomizer.PROP_SERVICE_NAME);
        if (StringUtils.hasText(platform)) {
            return Optional.of(platform.trim());
        }

        String spring = config.getString(PROP_SPRING_APPLICATION_NAME);
        if (StringUtils.hasText(spring)) {
            return Optional.of(spring.trim());
        }

        return Optional.empty();
    }

    private static Optional<String> resolveServiceVersion(ConfigProperties config,
                                                          Resource existing,
                                                          Resource otelEnv,
                                                          Supplier<Optional<String>> buildVersion) {
        if (otelEnv.getAttribute(SERVICE_VERSION) != null || StringUtils.hasText(existing.getAttribute(SERVICE_VERSION))) {
            return Optional.empty();
        }

        String platform = config.getString(PlatformResourceCustomizer.PROP_SERVICE_VERSION);
        if (StringUtils.hasText(platform)) {
            return Optional.of(platform.trim());
        }

        String fromBuild = buildVersion.get().orElse(null);
        if (StringUtils.hasText(fromBuild)) {
            return Optional.of(fromBuild.trim());
        }

        return Optional.empty();
    }

    private static Optional<String> resolveEnvironment(ConfigProperties config,
                                                       Resource existing,
                                                       Resource otelEnv,
                                                       boolean normalize) {
        if (existing.getAttribute(ENVIRONMENT) != null || otelEnv.getAttribute(ENVIRONMENT) != null) {
            return Optional.empty();
        }

        String platform = config.getString(PlatformResourceCustomizer.PROP_ENVIRONMENT);
        return Optional.of(EnvironmentNormalizer.normalize(platform, normalize));
    }

    private static Optional<String> resolvePlatformId(ConfigProperties config) {
        String value = config.getString(PlatformResourceCustomizer.PROP_ID);
        return StringUtils.hasText(value) ? Optional.of(value.trim()) : Optional.empty();
    }

    private static boolean hasRealServiceName(Attributes resolved, Resource otelEnv, Resource existing) {
        String name = firstNonNull(
                resolved.get(SERVICE_NAME),
                otelEnv.getAttribute(SERVICE_NAME),
                existing.getAttribute(SERVICE_NAME)
        );

        return StringUtils.hasText(name)
                && !SDK_UNKNOWN_SERVICE.equals(name)
                && !name.startsWith("unknown_service");
    }

    private static boolean isUnknownOrBlank(String resolved, String otelEnv, String existing) {
        String value = firstNonNull(resolved, otelEnv, existing);
        return StringUtils.isNullOrBlank(value) || EnvironmentNormalizer.UNKNOWN.equals(value);
    }

    private static String firstNonNull(String first, String second, String third) {
        if (first != null) {
            return first;
        }

        if (second != null) {
            return second;
        }

        return third;
    }
}
