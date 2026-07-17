package space.br1440.platform.tracing.api.control.protocol;

import java.util.Optional;

public record TracingControlProtocolVersion(int major) {

    public static Optional<TracingControlProtocolVersion> parse(Object raw) {
        switch (raw) {
            case null -> {
                return Optional.empty();
            }

            case Integer integerValue -> {
                return Optional.of(new TracingControlProtocolVersion(integerValue));
            }

            case Long longValue -> {
                if (longValue < Integer.MIN_VALUE || longValue > Integer.MAX_VALUE) {
                    return Optional.empty();
                }

                return Optional.of(new TracingControlProtocolVersion(longValue.intValue()));
            }

            case String stringValue -> {
                String trimmed = stringValue.trim();
                if (trimmed.isEmpty()) {
                    return Optional.empty();
                }

                try {
                    long parsed = Long.parseLong(trimmed);
                    if (parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE) {
                        return Optional.empty();
                    }

                    return Optional.of(new TracingControlProtocolVersion((int) parsed));
                } catch (NumberFormatException ex) {
                    return Optional.empty();
                }
            }

            default -> {
            }
        }

        return Optional.empty();
    }
}
