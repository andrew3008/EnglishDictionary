package space.br1440.platform.tracing.otel.javaagent.resource;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

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

public final class PlatformResourceCustomizer {

    public static final String PROP_SERVICE_NAME = "platform.tracing.service.name";
    public static final String PROP_SERVICE_VERSION = "platform.tracing.service.version";
    public static final String PROP_ENVIRONMENT = "platform.tracing.service.environment";
    public static final String PROP_C_GROUP = "platform.tracing.service.c-group";
    public static final String PROP_ID = "platform.tracing.service.id";
    public static final String PROP_HOST = "platform.tracing.service.host";
    public static final String PROP_CONTAINER_ID = "platform.tracing.service.container-id";

    private static final String ENV_CONTAINER_ID = "CONTAINER_ID";
    private static final String OTEL_RESOURCE_DISABLED_KEYS = "otel.resource.disabled.keys";
    private static final String OTEL_ENABLED_RESOURCE_PROVIDERS = "otel.java.enabled.resource.providers";
    private static final String OTEL_DISABLED_RESOURCE_PROVIDERS = "otel.java.disabled.resource.providers";
    private static final String LEGACY_PROVIDER = "space.br1440.platform.tracing.otel.javaagent.resource.SafeResourceProvider";

    private static final AttributeKey<String> HOST_NAME_KEY = AttributeKey.stringKey(PlatformAttributes.PLATFORM_HOST);
    private static final AttributeKey<String> CONTAINER_ID_KEY = AttributeKey.stringKey(PlatformAttributes.CONTAINER_ID);

    private final ResourceAttributeResolver resolver;
    private final HostNameResolver hostNameResolver;
    private final ResourceValidationDiagnostics validation;
    private final Function<String, String> envReader;
    private final Supplier<Optional<String>> containerIdDetector;
    private final Supplier<Optional<String>> buildVersion;
    private final Supplier<Optional<String>> manifestVersion;

    public PlatformResourceCustomizer() {
        this(
                new ResourceAttributeResolver(),
                new HostNameResolver(),
                new ResourceValidationDiagnostics(),
                System::getenv,
                new ProcfsContainerIdDetector()::detect,
                memoize(new BuildInfoReader()::readVersion),
                memoize(new ManifestVersionReader()::readVersion)
        );
    }

    PlatformResourceCustomizer(ResourceAttributeResolver resolver,
                               HostNameResolver hostNameResolver,
                               ResourceValidationDiagnostics validation,
                               Function<String, String> envReader,
                               Supplier<Optional<String>> containerIdDetector,
                               Supplier<Optional<String>> buildVersion,
                               Supplier<Optional<String>> manifestVersion) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.hostNameResolver = Objects.requireNonNull(hostNameResolver, "hostNameResolver");
        this.validation = Objects.requireNonNull(validation, "validation");
        this.envReader = Objects.requireNonNull(envReader, "envReader");
        this.containerIdDetector = Objects.requireNonNull(containerIdDetector, "containerIdDetector");
        this.buildVersion = Objects.requireNonNull(buildVersion, "buildVersion");
        this.manifestVersion = Objects.requireNonNull(manifestVersion, "manifestVersion");
    }

    public Resource customize(Resource existing, ConfigProperties config) {
        Objects.requireNonNull(existing, "existing");
        Objects.requireNonNull(config, "config");
        rejectLegacyProviderControls(config);

        Set<String> disabledKeys = disabledKeys(config);
        Resource filteredExisting = withoutDisabledKeys(existing, disabledKeys);
        Resource otelEnv = withoutDisabledKeys(
                ResourceConfiguration.createEnvironmentResource(config), disabledKeys);

        Attributes identity = resolver.resolve(
                config,
                filteredExisting,
                otelEnv,
                buildVersion.get().orElse(null),
                manifestVersion.get().orElse(null)
        );
        AttributesBuilder builder = identity.toBuilder();

        if (filteredExisting.getAttribute(HOST_NAME_KEY) == null) {
            String explicitHost = config.getString(PROP_HOST);
            if (present(explicitHost)) {
                builder.put(HOST_NAME_KEY, explicitHost.trim().toLowerCase(Locale.ROOT));
            } else {
                hostNameResolver.resolve().ifPresent(host -> builder.put(HOST_NAME_KEY, host));
            }
        }

        if (filteredExisting.getAttribute(CONTAINER_ID_KEY) == null) {
            resolveContainerId(config).ifPresent(id -> builder.put(CONTAINER_ID_KEY, id));
        }

        Attributes projection = withoutDisabledKeys(builder.build(), disabledKeys);
        ResourceValidationMode mode = ResourceValidationMode.fromConfig(
                config.getString(ExtensionPropertyNames.RESOURCE_VALIDATION_MODE)
        );

        ResourceValidationDiagnostics.ValidationResult result = validation.validate(projection, otelEnv, filteredExisting, mode);
        ResourceStartupDiagnostics.emit(
                ResourceStartupDiagnostics.from(projection, result.missingKeys().size(), mode)
        );
        validation.applyOrThrow(result);

        return filteredExisting.merge(Resource.create(projection));
    }

    private Optional<String> resolveContainerId(ConfigProperties config) {
        String explicit = firstNonBlank(config.getString(PROP_CONTAINER_ID), envReader.apply(ENV_CONTAINER_ID));
        if (explicit != null) {
            return Optional.of(explicit.trim());
        }

        boolean detect = config.getBoolean(
                ExtensionPropertyNames.RESOURCE_DETECT_CONTAINER_ID,
                ExtensionDefaults.DEFAULT_RESOURCE_DETECT_CONTAINER_ID);
        return detect ? containerIdDetector.get() : Optional.empty();
    }

    private static void rejectLegacyProviderControls(ConfigProperties config) {
        if (contains(config.getList(OTEL_ENABLED_RESOURCE_PROVIDERS), LEGACY_PROVIDER)
                || contains(config.getList(OTEL_DISABLED_RESOURCE_PROVIDERS), LEGACY_PROVIDER)) {
            throw new IllegalArgumentException(
                    "Legacy SafeResourceProvider controls are unsupported; remove "
                            + LEGACY_PROVIDER + " from OTel resource provider lists"
            );
        }
    }

    private static Set<String> disabledKeys(ConfigProperties config) {
        Set<String> keys = new HashSet<>();
        for (String key : config.getList(OTEL_RESOURCE_DISABLED_KEYS)) {
            if (present(key)) {
                keys.add(key.trim());
            }
        }

        return Set.copyOf(keys);
    }

    private static Resource withoutDisabledKeys(Resource resource, Set<String> disabledKeys) {
        Attributes filtered = withoutDisabledKeys(resource.getAttributes(), disabledKeys);
        return (filtered == resource.getAttributes())
                ? resource
                : Resource.create(filtered, resource.getSchemaUrl()
        );
    }

    private static Attributes withoutDisabledKeys(Attributes attributes, Set<String> disabledKeys) {
        if (disabledKeys.isEmpty() || attributes.isEmpty()) {
            return attributes;
        }

        AttributesBuilder filtered = null;
        for (AttributeKey<?> key : attributes.asMap().keySet()) {
            if (disabledKeys.contains(key.getKey())) {
                if (filtered == null) {
                    filtered = attributes.toBuilder();
                }

                filtered.remove(key);
            }
        }

        return (filtered == null) ? attributes : filtered.build();
    }

    private static boolean contains(List<String> values, String expected) {
        return values.stream().map(String::trim).anyMatch(expected::equals);
    }

    private static String firstNonBlank(String first, String second) {
        if (!StringUtils.isNullOrBlank(first)) {
            return first;
        }

        return !StringUtils.isNullOrBlank(second) ? second : null;
    }

    private static boolean present(String value) {
        return !StringUtils.isNullOrBlank(value);
    }

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
