package space.br1440.platform.tracing.autoconfigure.bootstrap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.core.env.Environment;

import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.autoconfigure.control.TracingConfigurationGuardException;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@UtilityClass
public final class TracingBootstrapSettingsResolver {

    private static final Map<String, String> OBSOLETE_SPRING_ONLY_KEYS = obsoleteKeys();

    private static final List<String> AGENT_BOOTSTRAP_KEEP_KEYS = List.of(
            "platform.tracing.sampling.enabled",
            "platform.tracing.sampling.ratio",
            "platform.tracing.sampling.route-ratios",
            "platform.tracing.javaagent.sampling.drop-paths",
            "platform.tracing.javaagent.sampling.force-record-values",
            "platform.tracing.javaagent.span-attributes.validation.level"
    );

    public static TracingBootstrapSettings resolve(Environment environment) {
        failFastOnObsoleteSpringOnlyKeys(environment);
        warnOnAgentBootstrapKeys(environment);

        boolean tracingEnabled = parseBoolean(
                environment,
                TracingBootstrapPropertyNames.ENABLED,
                true
        );

        TracingControlChannel controlChannel = parseRequiredEnum(
                environment,
                TracingBootstrapPropertyNames.CONTROL_CHANNEL,
                TracingControlChannel.class
        );

        rejectDisabledWriteChannel(tracingEnabled, controlChannel);
        return new TracingBootstrapSettings(tracingEnabled, controlChannel);
    }

    private static Map<String, String> obsoleteKeys() {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("platform.tracing.log-level", "");
        keys.put("platform.tracing.mode", "");
        keys.put("platform.tracing.configuration.enabled", "");
        keys.put("platform.tracing.configuration.authority", TracingBootstrapPropertyNames.CONTROL_CHANNEL);
        return Collections.unmodifiableMap(keys);
    }

    private static void rejectDisabledWriteChannel(boolean tracingEnabled, TracingControlChannel controlChannel) {
        if (tracingEnabled) {
            return;
        }

        if (controlChannel == TracingControlChannel.CONFIG_SERVER) {
            throw new TracingConfigurationGuardException("Tracing disabled is incompatible with CONFIG_SERVER authority.");
        }

        if (controlChannel == TracingControlChannel.ACTUATOR) {
            throw new TracingConfigurationGuardException("Tracing disabled is incompatible with ACTUATOR authority.");
        }
    }

    @SuppressWarnings("SameParameterValue")
    private static <E extends Enum<E>> E parseRequiredEnum(Environment environment,
                                                           String propertyName,
                                                           Class<E> enumType) {
        String raw = environment.getProperty(propertyName);
        if (StringUtils.isNullOrBlank(raw)) {
            throw new IllegalStateException("Missing required property '%s'".formatted(propertyName));
        }

        if (!raw.equals(raw.trim())) {
            throw new IllegalStateException("""
                    Invalid property '%s'='%s'. Surrounding whitespace is not accepted. \
                    Accepted values: %s""".formatted(propertyName, raw, List.of(enumType.getEnumConstants())));
        }

        return parseEnumValue(propertyName, raw, enumType);
    }

    private static <E extends Enum<E>> E parseEnumValue(String propertyName,
                                                        String raw,
                                                        Class<E> enumType) {
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalStateException("Property '%s' must not be blank".formatted(propertyName));
        }

        try {
            return Enum.valueOf(enumType, normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("""
                    Invalid property '%s'='%s'. \
                    Accepted values: %s""".formatted(propertyName, raw, List.of(enumType.getEnumConstants())),
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
            default -> throw new IllegalStateException("""
                    Invalid property '%s'='%s'. Accepted values: true, false""".formatted(propertyName, raw)
            );
        };
    }

    private static void failFastOnObsoleteSpringOnlyKeys(Environment environment) {
        for (Map.Entry<String, String> obsolete : OBSOLETE_SPRING_ONLY_KEYS.entrySet()) {
            if (!environment.containsProperty(obsolete.getKey())) {
                continue;
            }

            String hint = obsolete.getValue();
            if (hint.isBlank()) {
                throw new IllegalStateException("""
                        Obsolete Spring-only property '%s' is no longer bound or applied. \
                        Remove it from configuration.""".formatted(obsolete.getKey()));
            }

            throw new IllegalStateException("""
                    Obsolete Spring-only property '%s' is no longer bound or applied. \
                    Use '%s'.""".formatted(obsolete.getKey(), hint));
        }
    }

    private static void warnOnAgentBootstrapKeys(Environment environment) {
        List<String> present = new ArrayList<>();
        for (String key : AGENT_BOOTSTRAP_KEEP_KEYS) {
            if (environment.containsProperty(key)) {
                present.add(key);
            }
        }

        if (present.isEmpty()) {
            return;
        }

        log.warn("""
                Spring Environment contains Java-agent bootstrap-only properties {}. \
                Spring does not bind or apply these properties.""", present);
    }
}
