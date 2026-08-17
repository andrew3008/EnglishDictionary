package space.br1440.platform.tracing.otel.javaagent.logging;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class AgentLogEvent {

    public static final int MAX_ENCODED_BYTES = 8192;
    public static final int MAX_ARGUMENT_CHARS = 512;
    /**
     * Policy-only bound для {@code logger}. Owner schema задаёт только {@code minLength: 1};
     * {@code maxLength: 256} в schema не публикуем.
     */
    public static final int MAX_LOGGER_CHARS = 512;
    public static final int MAX_FRAMES = 30;
    public static final int MAX_CAUSES = 3;
    public static final int MAX_SUPPRESSED = 3;

    private final Instant timestamp;
    private final AgentLogLevel level;
    private final String eventCode;
    private final String logger;
    private final String message;
    private final Map<String, String> fields;
    private final String throwableDigest;

    AgentLogEvent(Instant timestamp,
                  AgentLogLevel level,
                  String eventCode,
                  String logger,
                  String message,
                  Map<String, String> fields,
                  String throwableDigest) {
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
        this.level = Objects.requireNonNull(level, "level").requireEventLevel();
        this.eventCode = Objects.requireNonNull(eventCode, "eventCode");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.message = Objects.requireNonNull(message, "message");
        Objects.requireNonNull(fields, "fields");
        if (fields.size() > AgentLogFieldNamePolicy.maxFields()) {
            throw new IllegalArgumentException("fields");
        }
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        this.throwableDigest = throwableDigest;
    }

    public Instant timestamp() {
        return timestamp;
    }

    public AgentLogLevel level() {
        return level;
    }

    public String eventCode() {
        return eventCode;
    }

    public String logger() {
        return logger;
    }

    public String message() {
        return message;
    }

    public Map<String, String> fields() {
        return fields;
    }

    public String throwableDigest() {
        return throwableDigest;
    }
}
