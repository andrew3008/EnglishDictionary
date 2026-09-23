package space.br1440.platform.tracing.otel.javaagent.logging;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

record AgentLogEvent(Instant timestamp,
                     AgentLogLevel level,
                     AgentEventCode eventCode,
                     String logger,
                     String message,
                     Map<String, String> fields,
                     String throwableDigest) {

    static final int MAX_ENCODED_BYTES = 8192;
    static final int MAX_ARGUMENT_CHARS = 512;
    static final int MAX_LOGGER_CHARS = 512;

    AgentLogEvent {
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(level, "level").requireEventLevel();
        Objects.requireNonNull(eventCode, "eventCode");

        requireBounded(logger, MAX_LOGGER_CHARS, "logger");
        requireBounded(message, MAX_ARGUMENT_CHARS, "message");

        Objects.requireNonNull(fields, "fields");
        if (fields.size() > AgentLogFieldNamePolicy.MAX_FIELDS) {
            throw new IllegalArgumentException("fields");
        }

        for (String value : fields.values()) {
            if (value != null && value.length() > MAX_ARGUMENT_CHARS) {
                throw new IllegalArgumentException("field value");
            }
        }

        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));

        if (throwableDigest != null && throwableDigest.length() > BoundedThrowableRenderer.maxDigestChars()) {
            throw new IllegalArgumentException("throwableDigest");
        }
    }

    private static void requireBounded(String value, int max, String name) {
        Objects.requireNonNull(value, name);

        if (value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(name);
        }
    }
}
