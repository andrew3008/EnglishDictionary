package space.br1440.platform.tracing.otel.javaagent.resource;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.diagnostics.TracingDiagnostics;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentEventCodes;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.sdk.autoconfigure.ResourceConfiguration;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.resources.Resource;

public final class PlatformResourceCustomizer {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(PlatformResourceCustomizer.class);

    public static final String PROP_SERVICE_NAME = "platform.tracing.service.name";
    public static final String PROP_SERVICE_VERSION = "platform.tracing.service.version";
    public static final String PROP_ENVIRONMENT = "platform.tracing.service.environment";
    public static final String PROP_ID = "platform.tracing.service.id";
    public static final String PROP_HOST = "platform.tracing.service.host";
    public static final String PROP_CONTAINER_ID = "platform.tracing.service.container-id";

    private static final String ENV_CONTAINER_ID = "CONTAINER_ID";
    private static final String OTEL_RESOURCE_DISABLED_KEYS = "otel.resource.disabled.keys";

    private static final AttributeKey<String> SERVICE_NAME_KEY = AttributeKey.stringKey(PlatformAttributes.SERVICE_NAME);
    private static final AttributeKey<String> SERVICE_VERSION_KEY = AttributeKey.stringKey(PlatformAttributes.SERVICE_VERSION);
    private static final AttributeKey<String> ENVIRONMENT_KEY = AttributeKey.stringKey(PlatformAttributes.PLATFORM_ENVIRONMENT);
    private static final AttributeKey<String> HOST_NAME_KEY = AttributeKey.stringKey(PlatformAttributes.PLATFORM_HOST);
    private static final AttributeKey<String> CONTAINER_ID_KEY = AttributeKey.stringKey(PlatformAttributes.CONTAINER_ID);

    private final HostNameResolver hostNameResolver;
    private final Function<String, String> envReader;
    private final Supplier<Optional<String>> buildVersion;
    private final TracingDiagnostics diagnostics;

    public PlatformResourceCustomizer() {
        this(new HostNameResolver(), System::getenv, new BuildInfoReader()::readVersion, TracingDiagnostics.shared());
    }

    PlatformResourceCustomizer(HostNameResolver hostNameResolver,
                               Function<String, String> envReader,
                               Supplier<Optional<String>> buildVersion,
                               TracingDiagnostics diagnostics) {
        this.hostNameResolver = Objects.requireNonNull(hostNameResolver, "hostNameResolver");
        this.envReader = Objects.requireNonNull(envReader, "envReader");
        this.buildVersion = Objects.requireNonNull(buildVersion, "buildVersion");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    public Resource customize(Resource existing, ConfigProperties config) {
        Objects.requireNonNull(existing, "existing");
        Objects.requireNonNull(config, "config");
        ResourceValidationMode mode = ResourceValidationMode.fromConfig(
                config.getString(ExtensionPropertyNames.RESOURCE_VALIDATION_MODE)
        );

        try {
            return doCustomize(existing, config, mode);
        } catch (Throwable failure) {
            PlatformThrowables.propagateIfFatal(failure);
            if (mode == ResourceValidationMode.ERROR) {
                if (failure instanceof RuntimeException runtimeFailure) {
                    throw runtimeFailure;
                }

                throw (Error) failure;
            }

            log.warn(AgentEventCodes.BOOT_RESOURCE_IDENTITY, "Resource customization failed", failure);
            diagnostics.recordResourceFailure();
            return existing;
        }
    }

    private Resource doCustomize(Resource existing, ConfigProperties config, ResourceValidationMode mode) {
        Set<String> disabledKeys = disabledKeys(config);
        Resource otelEnv = withoutDisabledKeys(ResourceConfiguration.createEnvironmentResource(config), disabledKeys);

        Attributes identity = ResourceAttributeResolver.resolve(config, existing, otelEnv, buildVersion);
        AttributesBuilder builder = identity.toBuilder();

        if (existing.getAttribute(HOST_NAME_KEY) == null) {
            String explicitHost = config.getString(PROP_HOST);
            if (StringUtils.hasText(explicitHost)) {
                builder.put(HOST_NAME_KEY, explicitHost.trim().toLowerCase(Locale.ROOT));
            } else {
                hostNameResolver.resolve().ifPresent(host -> builder.put(HOST_NAME_KEY, host));
            }
        }

        if (existing.getAttribute(CONTAINER_ID_KEY) == null) {
            resolveContainerId(config).ifPresent(id -> builder.put(CONTAINER_ID_KEY, id));
        }

        Attributes projection = withoutDisabledKeys(builder.build(), disabledKeys);
        enforceIdentity(projection, otelEnv, existing, mode);
        return existing.merge(Resource.create(projection));
    }

    private static void enforceIdentity(Attributes projection,
                                        Resource otelEnv,
                                        Resource existing,
                                        ResourceValidationMode mode) {
        List<String> missing = ResourceAttributeResolver.missingRequiredKeys(projection, otelEnv, existing);
        if (missing.isEmpty()) {
            log.info(AgentEventCodes.BOOT_RESOURCE_IDENTITY, "Resource identity resolved",
                    "service", orAbsent(projection.get(SERVICE_NAME_KEY)),
                    "environment", orAbsent(projection.get(ENVIRONMENT_KEY)),
                    "version", orAbsent(projection.get(SERVICE_VERSION_KEY)),
                    "kind", mode.name(),
                    "count", "0");
            return;
        }

        String keys = String.join(", ", missing);
        if (mode == ResourceValidationMode.ERROR) {
            throw new IllegalStateException(
                    "Resource identity failed ERROR validation; missing required keys [" + keys + "]"
            );
        }

        log.warn(AgentEventCodes.BOOT_RESOURCE_IDENTITY, "Resource identity incomplete",
                "service", orAbsent(projection.get(SERVICE_NAME_KEY)),
                "environment", orAbsent(projection.get(ENVIRONMENT_KEY)),
                "version", orAbsent(projection.get(SERVICE_VERSION_KEY)),
                "kind", mode.name(),
                "missingKeys", keys);
    }

    private Optional<String> resolveContainerId(ConfigProperties config) {
        String explicit = firstNonBlank(config.getString(PROP_CONTAINER_ID), envReader.apply(ENV_CONTAINER_ID));
        return (explicit == null) ? Optional.empty() : Optional.of(explicit.trim());
    }

    private static Set<String> disabledKeys(ConfigProperties config) {
        Set<String> keys = new HashSet<>();
        for (String key : config.getList(OTEL_RESOURCE_DISABLED_KEYS)) {
            if (StringUtils.hasText(key)) {
                keys.add(key.trim());
            }
        }

        return Set.copyOf(keys);
    }

    private static Resource withoutDisabledKeys(Resource resource, Set<String> disabledKeys) {
        if (disabledKeys.isEmpty()) {
            return resource;
        }

        return resource.toBuilder()
                .removeIf(key -> disabledKeys.contains(key.getKey()))
                .build();
    }

    private static Attributes withoutDisabledKeys(Attributes attributes, Set<String> disabledKeys) {
        if (disabledKeys.isEmpty() || attributes.isEmpty()) {
            return attributes;
        }

        return attributes.toBuilder()
                .removeIf(key -> disabledKeys.contains(key.getKey()))
                .build();
    }

    private static String firstNonBlank(String first, String second) {
        if (StringUtils.hasText(first)) {
            return first;
        }

        return StringUtils.hasText(second) ? second : null;
    }

    private static String orAbsent(String value) {
        return StringUtils.hasText(value) ? value : "<absent>";
    }
}
