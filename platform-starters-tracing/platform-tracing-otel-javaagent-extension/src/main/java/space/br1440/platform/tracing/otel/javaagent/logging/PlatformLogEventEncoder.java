package space.br1440.platform.tracing.otel.javaagent.logging;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.Map;

public final class PlatformLogEventEncoder {

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ISO_INSTANT;
    private static final String FALLBACK_LOGGER = "space.br1440.platform.tracing.otel.javaagent.logging";
    private static final String FALLBACK_MESSAGE = "Platform Agent log event could not be encoded safely";
    private static final byte[] MINIMAL_SCHEMA_VALID_ASCII_FALLBACK =
            ("{\"timestamp\":\"1970-01-01T00:00:00Z\",\"level\":\"ERROR\",\"logger\":\""
                    + FALLBACK_LOGGER
                    + "\",\"message\":\""
                    + FALLBACK_MESSAGE
                    + "\",\"event_code\":\""
                    + AgentEventCodes.ENCODE_FALLBACK
                    + "\"}\n")
                    .getBytes(StandardCharsets.US_ASCII);

    public byte[] encode(AgentLogEvent event) {
        if (violatesInputBounds(event)) {
            return encodeFallback(event);
        }

        byte[] encoded = render(event, event.fields(), event.throwableDigest())
                .getBytes(StandardCharsets.UTF_8);
        if (encoded.length <= AgentLogEvent.MAX_ENCODED_BYTES) {
            return encoded;
        }

        return encodeFallback(event);
    }

    private static byte[] encodeFallback(AgentLogEvent event) {
        byte[] fallback = renderFallback(event).getBytes(StandardCharsets.UTF_8);
        if (fallback.length <= AgentLogEvent.MAX_ENCODED_BYTES) {
            return fallback;
        }

        return MINIMAL_SCHEMA_VALID_ASCII_FALLBACK.clone();
    }

    private static boolean violatesInputBounds(AgentLogEvent event) {
        if (!AgentEventCodes.isKnown(event.eventCode())) {
            return true;
        }

        if (event.logger().isBlank()
                || event.logger().length() > AgentLogEvent.MAX_LOGGER_CHARS
                || AgentLogTextSanitizer.isWireUnsafe(event.logger())) {
            return true;
        }

        if (event.message().isBlank()
                || event.message().length() > AgentLogEvent.MAX_ARGUMENT_CHARS
                || AgentLogTextSanitizer.isWireUnsafe(event.message())) {
            return true;
        }

        Map<String, String> fields = event.fields();
        if (fields.size() > AgentLogFieldNamePolicy.maxFields()) {
            return true;
        }

        for (Map.Entry<String, String> field : fields.entrySet()) {
            if (AgentLogFieldNamePolicy.isRejectedFieldName(field.getKey())) {
                return true;
            }

            String value = field.getValue();
            if (value != null
                    && (value.length() > AgentLogEvent.MAX_ARGUMENT_CHARS
                    || AgentLogTextSanitizer.isWireUnsafe(value))) {
                return true;
            }
        }

        String digest = event.throwableDigest();
        return digest != null && digest.length() > BoundedThrowableRenderer.maxDigestChars();
    }

    private static String render(AgentLogEvent event, Map<String, String> fields, String throwableDigest) {
        JsonWriter json = new JsonWriter();
        json.beginObject();

        writeKnownFields(json, event);

        json.writeKey("fields");
        writeFieldsObject(json, fields);

        if (throwableDigest != null) {
            json.writeField("error_stacktrace", throwableDigest);
        }

        json.endObject();
        return json.toLine();
    }

    private static String renderFallback(AgentLogEvent event) {
        JsonWriter json = new JsonWriter();
        json.beginObject();
        json.writeField("timestamp", TIMESTAMP_FORMAT.format(event.timestamp()));
        json.writeField("level", event.level().name());
        json.writeField("logger", FALLBACK_LOGGER);
        json.writeField("message", FALLBACK_MESSAGE);
        json.writeField("event_code", AgentEventCodes.ENCODE_FALLBACK);
        json.endObject();
        return json.toLine();
    }

    private static void writeKnownFields(JsonWriter json, AgentLogEvent event) {
        json.writeField("timestamp", TIMESTAMP_FORMAT.format(event.timestamp()));
        json.writeField("level", event.level().name());
        json.writeField("logger", event.logger());
        json.writeField("message", event.message());
        json.writeField("event_code", event.eventCode());
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
