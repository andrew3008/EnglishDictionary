package space.br1440.platform.tracing.otel.javaagent.logging;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;

record AgentLogRecord(Instant timestamp,
                     AgentLogLevel level,
                     AgentEventCode eventCode,
                     String eventName,
                     String logger,
                     String message,
                     Map<String, String> fields,
                     String errorType,
                     String throwableDigest,
                     long suppressedCount) {

    static final int MAX_ENCODED_BYTES = 8192;
    static final int MAX_ARGUMENT_CHARS = 512;
    static final int MAX_FIELDS = 16;
    static final int MAX_EVENT_NAME_CHARS = 128;
    static final int MAX_LOGGER_CHARS = 512;

    AgentLogRecord {
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(level, "level").requireEventLevel();
        Objects.requireNonNull(eventCode, "eventCode");

        requireBounded(eventName, MAX_EVENT_NAME_CHARS, "eventName");
        requireBounded(logger, MAX_LOGGER_CHARS, "logger");
        requireBounded(message, MAX_ARGUMENT_CHARS, "message");
        if (errorType != null) {
            requireBounded(errorType, MAX_ARGUMENT_CHARS, "errorType");
        }

        Objects.requireNonNull(fields, "fields");
        if (fields.size() > MAX_FIELDS) {
            throw new IllegalArgumentException("fields");
        }

        for (String value : fields.values()) {
            if (value != null && value.length() > MAX_ARGUMENT_CHARS) {
                throw new IllegalArgumentException("field value");
            }
        }

        if (throwableDigest != null && throwableDigest.length() > BoundedThrowableRenderer.maxDigestChars()) {
            throw new IllegalArgumentException("throwableDigest");
        }
        if (suppressedCount < 0L) {
            throw new IllegalArgumentException("suppressedCount");
        }
    }

    AgentLogRecord(Instant timestamp,
                   AgentLogLevel level,
                   AgentEventCode eventCode,
                   String eventName,
                   String logger,
                   String message,
                   Map<String, String> fields,
                   String errorType,
                   String throwableDigest) {
        this(timestamp, level, eventCode, eventName, logger, message, fields, errorType, throwableDigest, 0L);
    }

    private static void requireBounded(String value, int max, String name) {
        Objects.requireNonNull(value, name);

        if (!StringUtils.hasText(value) || value.length() > max) {
            throw new IllegalArgumentException(name);
        }
    }
}
