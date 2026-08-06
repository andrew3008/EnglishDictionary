package space.br1440.platform.tracing.autoconfigure.bootstrap;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

@Slf4j
@UtilityClass
public final class TracingBootstrapSettingsResolver {

    private static final List<String> OBSOLETE_SPRING_ONLY_KEYS = List.of(
            "platform.tracing.log-level",
            "platform.tracing.mode",
            "platform.tracing.configuration.enabled");

    private static final List<String> AGENT_BOOTSTRAP_KEEP_KEYS = List.of(
            "platform.tracing.sampling.enabled",
            "platform.tracing.sampling.ratio",
            "platform.tracing.sampling.route-ratios",
            "platform.tracing.sampling.force-record-header",
            "platform.tracing.sampling.qa-header",
            "platform.tracing.javaagent.sampling.drop-paths",
            "platform.tracing.javaagent.sampling.force-record-values",
            "platform.tracing.validation.enabled",
            "platform.tracing.validation.strict",
            "platform.tracing.validation.mode",
            "platform.tracing.validation.strict-runtime-allowed");

    public static TracingBootstrapSettings resolve(Environment environment) {
        failFastOnObsoleteSpringOnlyKeys(environment);
        warnOnAgentBootstrapKeys(environment);
        return new TracingBootstrapSettings(
                parseBoolean(environment,
                        TracingBootstrapPropertyNames.ENABLED,
                        true
                ),

                parseRequiredEnum(environment,
                        TracingBootstrapPropertyNames.CONFIGURATION_AUTHORITY,
                        TracingConfigurationAuthority.class
                ),

                parseRequiredEnum(
                        environment,
                        TracingBootstrapPropertyNames.ENVIRONMENT_POSTURE,
                        PlatformEnvironmentPosture.class
                )
        );
    }

    private static <E extends Enum<E>> E parseRequiredEnum(Environment environment,
                                                           String propertyName,
                                                           Class<E> enumType) {
        String raw = environment.getProperty(propertyName);
        if (raw == null || raw.trim().isEmpty()) {
            if (TracingBootstrapPropertyNames.CONFIGURATION_AUTHORITY.equals(propertyName)) {
                throw new IllegalStateException("Missing platform.tracing.configuration.authority");
            }

            if (TracingBootstrapPropertyNames.ENVIRONMENT_POSTURE.equals(propertyName)) {
                throw new IllegalStateException("Unknown platform.environment value: ''");
            }

            throw new IllegalStateException("Missing required property '" + propertyName + "'");
        }

        return parseEnumValue(propertyName, raw, enumType);
    }

    private static <E extends Enum<E>> E parseEnumValue(String propertyName,
                                                        String raw,
                                                        Class<E> enumType) {
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalStateException("Property '" + propertyName + "' must not be blank");
        }

        try {
            return Enum.valueOf(enumType, normalized);
        } catch (IllegalArgumentException exception) {
            if (TracingBootstrapPropertyNames.CONFIGURATION_AUTHORITY.equals(propertyName)) {
                throw new IllegalStateException(
                        "Invalid platform.tracing.configuration.authority='" + raw
                                + "'. Must be one of: HELM, CONFIG_SERVER, ACTUATOR",
                        exception);
            }

            if (TracingBootstrapPropertyNames.ENVIRONMENT_POSTURE.equals(propertyName)) {
                throw new IllegalStateException(
                        "Unknown platform.environment value: '" + raw + "'. Accepted exact values: "
                                + "LOCAL, SHARED, PRODUCTION",
                        exception);
            }

            throw new IllegalStateException(
                    "Invalid property '" + propertyName + "'='" + raw
                            + "'. Accepted values: " + List.of(enumType.getEnumConstants()),
                    exception);
        }
    }

    @SuppressWarnings("SameParameterValue")
    private static boolean parseBoolean(Environment environment,
                                        String propertyName,
                                        boolean defaultValue) {
        String raw = environment.getProperty(propertyName);
        if (raw == null) {
            return defaultValue;
        }

        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalStateException("Invalid property '" + propertyName + "'='" + raw + "'. Accepted values: true, false");
        };
    }

    private static void failFastOnObsoleteSpringOnlyKeys(Environment environment) {
        for (String key : OBSOLETE_SPRING_ONLY_KEYS) {
            if (environment.containsProperty(key)) {
                throw new IllegalStateException(
                        "Obsolete Spring-only property '" + key
                                + "' is no longer bound or applied. Remove it from configuration."
                );
            }
        }
    }

    private static void warnOnAgentBootstrapKeys(Environment environment) {
        List<String> present = new ArrayList<>();
        for (String key : AGENT_BOOTSTRAP_KEEP_KEYS) {
            if (environment.containsProperty(key)) {
                present.add(key);
            }
        }

        if (!present.isEmpty()) {
            log.warn("Spring Environment contains Java-agent bootstrap-only properties {}. "
                    + "Spring does not bind or apply these properties.", present
            );
        }
    }
}
