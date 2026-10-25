package space.br1440.platform.tracing.otel.javaagent.bootstrap.session;

import java.util.Map;
import java.util.Objects;

import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.otel.javaagent.configuration.ConfigurationPropertyException;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigurationException;
import lombok.experimental.UtilityClass;

@UtilityClass
public final class SamplerConfigurationContract {

    static final String PLACEHOLDER_SAMPLER = "always_off";

    private static final String SAMPLER_PROPERTY = "otel.traces.sampler";
    private static final String SAMPLER_ARG_PROPERTY = "otel.traces.sampler.arg";
    private static final String SDK_DISABLED_PROPERTY = "otel.sdk.disabled";

    public static Map<String, String> normalize(ConfigProperties config) {
        Objects.requireNonNull(config, "config");

        String sampler = config.getString(SAMPLER_PROPERTY);
        if (StringUtils.isNullOrEmpty(sampler)) {
            return Map.of(SAMPLER_PROPERTY, PLACEHOLDER_SAMPLER);
        }

        return Map.of();
    }

    static void requireFinal(ConfigProperties config) {
        Objects.requireNonNull(config, "config");

        requireExactSampler(config.getString(SAMPLER_PROPERTY));
        requireEmpty(
                SAMPLER_ARG_PROPERTY,
                config.getString(SAMPLER_ARG_PROPERTY),
                "otel.traces.sampler.arg is not supported; use platform.tracing.sampling.ratio"
        );
        requireSdkEnabled(config.getString(SDK_DISABLED_PROPERTY));
        requireEmpty(
                ExtensionPropertyNames.OTEL_JAVAAGENT_EXTENSIONS,
                JavaAgentExtensionPaths.resolveRawValue(config),
                "otel.javaagent.extensions is not supported by the controlled agent profile"
        );
    }

    private static void requireExactSampler(String sampler) {
        if (!PLACEHOLDER_SAMPLER.equals(sampler)) {
            throw invalid(
                    SAMPLER_PROPERTY,
                    "otel.traces.sampler is managed by the controlled agent and must be unset"
            );
        }
    }

    private static void requireSdkEnabled(String disabled) {
        if (!StringUtils.isNullOrEmpty(disabled) && !"false".equals(disabled)) {
            throw invalid(
                    SDK_DISABLED_PROPERTY,
                    "otel.sdk.disabled is not supported by the controlled agent profile"
            );
        }
    }

    private static void requireEmpty(String propertyName, String value, String message) {
        if (!StringUtils.isNullOrEmpty(value)) {
            throw invalid(propertyName, message);
        }
    }

    private static ConfigurationPropertyException invalid(String propertyName, String message) {
        return new ConfigurationPropertyException(propertyName, new ConfigurationException(message));
    }
}
