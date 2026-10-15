package space.br1440.platform.tracing.autoconfigure.bootstrap;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.core.env.Environment;

import space.br1440.platform.tracing.autoconfigure.control.TracingConfigurationGuardException;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class TracingBootstrapSettingsResolver {

    public static TracingBootstrapSettings resolve(Environment environment) {
        boolean tracingEnabled = parseEnabled(environment)
                .orElseThrow(() -> invalidEnabled(environment));

        TracingControlChannel controlChannel = parseControlChannel(environment)
                .orElseThrow(() -> invalidControlChannel(environment));

        rejectDisabledActuatorChannel(tracingEnabled, controlChannel);
        return new TracingBootstrapSettings(tracingEnabled, controlChannel);
    }

    static Optional<Boolean> parseEnabled(Environment environment) {
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

    static Optional<TracingControlChannel> parseControlChannel(Environment environment) {
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

    private static void rejectDisabledActuatorChannel(boolean tracingEnabled, TracingControlChannel controlChannel) {
        if (!tracingEnabled && controlChannel == TracingControlChannel.ACTUATOR) {
            throw new TracingConfigurationGuardException("Tracing disabled is incompatible with ACTUATOR control channel.");
        }
    }

    private static IllegalStateException invalidEnabled(Environment environment) {
        String propertyName = TracingBootstrapPropertyNames.ENABLED;
        String raw = environment.getProperty(propertyName);
        return new IllegalStateException(
                "Invalid property '%s'='%s'. Accepted values: true, false"
                        .formatted(propertyName, raw)
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
}
