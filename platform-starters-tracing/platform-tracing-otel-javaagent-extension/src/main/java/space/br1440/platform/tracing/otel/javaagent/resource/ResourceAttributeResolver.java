package space.br1440.platform.tracing.otel.javaagent.resource;

import java.util.Optional;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.common.util.StringUtils;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.sdk.autoconfigure.ResourceConfiguration;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.resources.Resource;

public final class ResourceAttributeResolver {

    static final String SDK_UNKNOWN_SERVICE = "unknown_service:java";

    private static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey(PlatformAttributes.SERVICE_NAME);
    private static final AttributeKey<String> SERVICE_VERSION = AttributeKey.stringKey(PlatformAttributes.SERVICE_VERSION);
    private static final AttributeKey<String> ENVIRONMENT = AttributeKey.stringKey(PlatformAttributes.PLATFORM_ENVIRONMENT);
    private static final AttributeKey<String> C_GROUP = AttributeKey.stringKey(PlatformAttributes.PLATFORM_C_GROUP);
    private static final AttributeKey<String> PLATFORM_ID = AttributeKey.stringKey(PlatformAttributes.PLATFORM_ID);

    private static final String PROP_SPRING_APPLICATION_NAME = "spring.application.name";

    public Attributes resolve(ConfigProperties config,
                              Resource existing,
                              String buildInfoVersion,
                              String manifestVersion) {
        Resource otelEnv = ResourceConfiguration.createEnvironmentResource(config);
        return resolve(config, existing, otelEnv, buildInfoVersion, manifestVersion);
    }

    Attributes resolve(ConfigProperties config,
                       Resource existing,
                       Resource otelEnv,
                       String buildInfoVersion,
                       String manifestVersion) {
        boolean normalize = config.getBoolean(
                ExtensionPropertyNames.RESOURCE_NORMALIZE_ENVIRONMENT,
                ExtensionDefaults.DEFAULT_RESOURCE_NORMALIZE_ENVIRONMENT);

        AttributesBuilder builder = Attributes.builder();

        resolveServiceName(config, existing, otelEnv)
                .ifPresent(v -> builder.put(SERVICE_NAME, v));

        resolveServiceVersion(config, existing, otelEnv, buildInfoVersion, manifestVersion)
                .ifPresent(v -> builder.put(SERVICE_VERSION, v));

        resolveEnvironment(config, existing, otelEnv, normalize)
                .ifPresent(v -> builder.put(ENVIRONMENT, v));

        if (existing.getAttribute(C_GROUP) == null) {
            builder.put(C_GROUP, resolveCGroup(config).orElse(EnvironmentNormalizer.UNKNOWN));
        }

        if (existing.getAttribute(PLATFORM_ID) == null) {
            builder.put(PLATFORM_ID, resolvePlatformId(config).orElse(EnvironmentNormalizer.UNKNOWN));
        }

        return builder.build();
    }

    Optional<String> resolveServiceName(ConfigProperties config, Resource existing, Resource otelEnv) {
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

    Optional<String> resolveServiceVersion(ConfigProperties config,
                                           Resource existing,
                                           Resource otelEnv,
                                           String buildInfoVersion,
                                           String manifestVersion) {
        if (otelEnv.getAttribute(SERVICE_VERSION) != null || StringUtils.hasText(existing.getAttribute(SERVICE_VERSION))) {
            return Optional.empty();
        }

        String platform = config.getString(PlatformResourceCustomizer.PROP_SERVICE_VERSION);
        if (StringUtils.hasText(platform)) {
            return Optional.of(platform.trim());
        }

        if (StringUtils.hasText(buildInfoVersion)) {
            return Optional.of(buildInfoVersion.trim());
        }

        if (StringUtils.hasText(manifestVersion)) {
            return Optional.of(manifestVersion.trim());
        }

        return Optional.empty();
    }

    Optional<String> resolveEnvironment(ConfigProperties config,
                                        Resource existing,
                                        Resource otelEnv,
                                        boolean normalize) {
        if (existing.getAttribute(ENVIRONMENT) != null || otelEnv.getAttribute(ENVIRONMENT) != null) {
            return Optional.empty();
        }

        String platform = config.getString(PlatformResourceCustomizer.PROP_ENVIRONMENT);
        return Optional.of(EnvironmentNormalizer.normalize(platform, normalize));
    }

    Optional<String> resolveCGroup(ConfigProperties config) {
        String value = config.getString(PlatformResourceCustomizer.PROP_C_GROUP);
        return StringUtils.hasText(value) ? Optional.of(value.trim()) : Optional.empty();
    }

    Optional<String> resolvePlatformId(ConfigProperties config) {
        String value = config.getString(PlatformResourceCustomizer.PROP_ID);
        return StringUtils.hasText(value) ? Optional.of(value.trim()) : Optional.empty();
    }
}
