package space.br1440.platform.tracing.autoconfigure.bootstrap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.autoconfigure.control.TracingConfigurationGuardException;
import space.br1440.platform.tracing.autoconfigure.internal.WinningPropertySourceTraversal;

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
            "platform.tracing.javaagent.span-attributes.validation.level",
            "platform.tracing.exporter.enabled",
            "platform.tracing.propagation.trace-control-headers.enabled",
            "platform.tracing.diagnostics.log-level"
    );

    public static TracingBootstrapSettings resolve(Environment environment) {
        failFastOnObsoleteSpringOnlyKeys(environment);
        boolean tracingEnabled = parseEnabled(environment)
                .orElseThrow(() -> invalidEnabled(environment));

        warnOnAgentBootstrapKeys(environment);

        TracingControlChannel controlChannel = parseControlChannel(environment)
                .orElseThrow(() -> invalidControlChannel(environment));

        rejectDisabledWriteChannel(tracingEnabled, controlChannel);
        return new TracingBootstrapSettings(tracingEnabled, controlChannel);
    }

    public static Optional<Boolean> parseEnabled(Environment environment) {
        String raw = environment.getProperty(TracingBootstrapPropertyNames.ENABLED);
        if (raw == null) {
            return Optional.of(true);
        }

        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "true" -> Optional.of(true);
            case "false" -> Optional.of(false);
            default -> Optional.empty();
        };
    }

    public static Optional<TracingControlChannel> parseControlChannel(Environment environment) {
        String raw = environment.getProperty(TracingBootstrapPropertyNames.CONTROL_CHANNEL);
        if (raw == null) {
            return parseEnabled(environment)
                    .filter(enabled -> !enabled)
                    .map(ignored -> TracingControlChannel.NONE);
        }

        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }

        try {
            return Optional.of(TracingControlChannel.valueOf(normalized));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
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
            throw new TracingConfigurationGuardException(
                    "Tracing disabled is incompatible with CONFIG_SERVER control channel."
            );
        }

        if (controlChannel == TracingControlChannel.ACTUATOR) {
            throw new TracingConfigurationGuardException(
                    "Tracing disabled is incompatible with ACTUATOR control channel."
            );
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

    private static IllegalStateException invalidEnabled(Environment environment) {
        String propertyName = TracingBootstrapPropertyNames.ENABLED;
        String raw = environment.getProperty(propertyName);
        return new IllegalStateException(
                "Invalid property '%s'='%s'. Accepted values: true, false".formatted(propertyName, raw)
        );
    }

    private static IllegalStateException invalidControlChannel(Environment environment) {
        String propertyName = TracingBootstrapPropertyNames.CONTROL_CHANNEL;
        String raw = environment.getProperty(propertyName);
        if (raw == null) {
            return new IllegalStateException("Missing required property '%s'".formatted(propertyName));
        }
        if (raw.isBlank()) {
            return new IllegalStateException("Property '%s' must not be blank".formatted(propertyName));
        }

        return new IllegalStateException(
                "Invalid property '%s'='%s'. Accepted values: %s"
                        .formatted(propertyName, raw, List.of(TracingControlChannel.values()))
        );
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
        if (!(environment instanceof ConfigurableEnvironment configurableEnvironment)) {
            return;
        }

        List<String> present = new ArrayList<>();
        for (String key : AGENT_BOOTSTRAP_KEEP_KEYS) {
            PropertySource<?> winner = WinningPropertySourceTraversal.findWinningLeaf(configurableEnvironment, key);
            if (winner != null && isSpringConfigData(winner.getName())) {
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

    private static boolean isSpringConfigData(String sourceName) {
        return sourceName.startsWith("Config resource '") || sourceName.startsWith("configserver:");
    }
}
