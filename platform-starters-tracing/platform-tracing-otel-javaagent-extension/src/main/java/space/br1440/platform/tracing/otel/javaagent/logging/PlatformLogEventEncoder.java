package space.br1440.platform.tracing.otel.javaagent.logging;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;

class PlatformLogEventEncoder {

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ISO_INSTANT;
    private static final String FALLBACK_LOGGER = "space.br1440.platform.tracing.otel.javaagent.logging";
    private static final String FALLBACK_MESSAGE = "Platform Agent log event could not be encoded safely";
    private static final String FALLBACK_EVENT_NAME = "ENCODE_FALLBACK";

    byte[] encode(AgentLogRecord event) {
        return encodeWithOutcome(event).payload();
    }

    EncodedLogEvent encodeWithOutcome(AgentLogRecord event) {
        byte[] encoded = render(event, event.fields(), event.throwableDigest(), false)
                .getBytes(StandardCharsets.UTF_8);
        if (encoded.length <= AgentLogRecord.MAX_ENCODED_BYTES) {
            return EncodedLogEvent.normal(encoded);
        }

        if (event.throwableDigest() != null) {
            byte[] withoutStacktrace = render(event, event.fields(), null, true)
                    .getBytes(StandardCharsets.UTF_8);
            if (withoutStacktrace.length <= AgentLogRecord.MAX_ENCODED_BYTES) {
                return EncodedLogEvent.normal(withoutStacktrace);
            }
        }

        return EncodedLogEvent.fallback(
                encodeFallback(
                        event.timestamp(),
                        event.level(),
                        event.eventCode(),
                        event.eventName(),
                        FallbackReason.BYTE_BUDGET,
                        event.suppressedCount()
                )
        );
    }

    byte[] encodeFallback(Instant timestamp,
                          AgentLogLevel level,
                          AgentEventCode original,
                          String originalEventName,
                          FallbackReason reason,
                          long suppressedCount) {
        return renderFallback(timestamp, level, original, originalEventName, reason, suppressedCount)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String render(AgentLogRecord event,
                                 Map<String, String> fields,
                                 String throwableDigest,
                                 boolean stacktraceTruncated) {
        JsonWriter json = new JsonWriter();
        json.beginObject();
        writeKnownFields(json, event);

        json.writeKey("fields");
        writeFieldsObject(json, fields);

        if (event.errorType() != null) {
            json.writeField("error_type", event.errorType());
        }

        if (throwableDigest != null) {
            json.writeField("error_stacktrace", throwableDigest);
        }

        if (stacktraceTruncated) {
            json.writeField("stacktrace_truncated", "true");
        }

        json.endObject();
        return json.toLine();
    }

    private static String renderFallback(Instant timestamp,
                                          AgentLogLevel level,
                                          AgentEventCode original,
                                          String originalEventName,
                                          FallbackReason reason,
                                          long suppressedCount) {
        JsonWriter json = new JsonWriter();
        json.beginObject();
        json.writeField("timestamp", TIMESTAMP_FORMAT.format(timestamp));
        json.writeField("level", level.name());
        json.writeField("logger", FALLBACK_LOGGER);
        json.writeField("message", FALLBACK_MESSAGE);
        json.writeField("event_code", AgentEventCode.ENCODE_FALLBACK.code());
        json.writeField("event_name", FALLBACK_EVENT_NAME);
        json.writeField("original_event_name", originalEventName);
        if (suppressedCount > 0L) {
            json.writeField("suppressed_count", Long.toString(suppressedCount));
        }
        json.writeKey("fields");
        json.beginObject();
        json.writeField("original_event_code", original.code());
        json.writeField("fallback_reason", reason.wireValue());
        json.endObject();
        json.endObject();
        return json.toLine();
    }

    record EncodedLogEvent(byte[] payload, boolean fallback) {

        EncodedLogEvent {
            if (payload == null || payload.length == 0) {
                throw new IllegalArgumentException("Encoded log payload must not be empty");
            }
        }

        static EncodedLogEvent normal(byte[] payload) {
            return new EncodedLogEvent(payload, false);
        }

        static EncodedLogEvent fallback(byte[] payload) {
            return new EncodedLogEvent(payload, true);
        }
    }

    private static void writeKnownFields(JsonWriter json, AgentLogRecord event) {
        json.writeField("timestamp", TIMESTAMP_FORMAT.format(event.timestamp()));
        json.writeField("level", event.level().name());
        json.writeField("logger", event.logger());
        json.writeField("message", event.message());
        json.writeField("event_code", event.eventCode().code());
        json.writeField("event_name", event.eventName());

        if (event.suppressedCount() > 0L) {
            json.writeField("suppressed_count", Long.toString(event.suppressedCount()));
        }
    }

    private static void writeFieldsObject(JsonWriter json, Map<String, String> fields) {
        json.beginObject();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            json.writeField(field.getKey(), field.getValue());
        }
        json.endObject();
    }

    private static final class JsonWriter {

        private final StringBuilder out = new StringBuilder(256);
        private boolean needsComma;

        void beginObject() {
            writeCommaIfNeeded();
            out.append('{');
            needsComma = false;
        }

        void endObject() {
            out.append('}');
            needsComma = true;
        }

        void writeKey(String key) {
            writeCommaIfNeeded();
            writeEscapedString(key);
            out.append(':');
            needsComma = false;
        }

        void writeField(String key, String value) {
            writeKey(key);
            writeEscapedString(value);
            needsComma = true;
        }

        String toLine() {
            return out.append('\n').toString();
        }

        private void writeCommaIfNeeded() {
            if (needsComma) {
                out.append(',');
            }
        }

        private void writeEscapedString(String value) {
            out.append('"');
            if (value != null) {
                for (int i = 0; i < value.length(); i++) {
                    appendEscaped(value.charAt(i));
                }
            }
            out.append('"');
        }

        private void appendEscaped(char ch) {
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        out.append("\\u");
                        String hex = Integer.toHexString(ch);
                        out.append("0000", 0, 4 - hex.length()).append(hex);
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
    }
}
