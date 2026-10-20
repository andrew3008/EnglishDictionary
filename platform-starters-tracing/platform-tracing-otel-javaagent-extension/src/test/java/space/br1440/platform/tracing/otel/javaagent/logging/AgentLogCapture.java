package space.br1440.platform.tracing.otel.javaagent.logging;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;

public final class AgentLogCapture implements BeforeEachCallback, AfterEachCallback, AutoCloseable {

    public static final String RESOURCE_LOCK = "agent-logging-runtime";

    private static final ObjectMapper JSON = new ObjectMapper();

    private ByteArrayOutputStream buffer;
    private AgentLoggingRuntime.ScopedInstallation installation;

    public static AgentLogCapture installed() {
        AgentLogCapture capture = new AgentLogCapture();
        capture.install();
        return capture;
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        install();
    }

    @Override
    public void afterEach(ExtensionContext context) {
        restore();
    }

    @Override
    public void close() {
        restore();
    }

    private void install() {
        if (installation != null) {
            throw new IllegalStateException("Agent log capture is already installed");
        }

        ByteArrayOutputStream candidate = new ByteArrayOutputStream();
        AgentLoggingRuntime.ScopedInstallation installed = AgentLoggingTestSupport.install(
                new PlatformStderrSink(candidate)
        );

        buffer = candidate;
        installation = installed;
        AgentLoggingRuntime.shared().applyLevel(AgentLogLevel.TRACE);
    }

    private void restore() {
        if (installation != null) {
            installation.close();
            installation = null;
        }

        buffer = null;
    }

    public void applyLevel(AgentLogLevel threshold) {
        AgentLoggingRuntime.shared().applyLevel(threshold);
    }

    public List<CapturedLogEvent> events() {
        String text = capturedText();
        List<CapturedLogEvent> events = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            if (line.isBlank()) {
                continue;
            }

            events.add(decode(line));
        }

        return List.copyOf(events);
    }

    public List<JsonNode> jsonEvents() {
        String text = capturedText();
        List<JsonNode> events = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            if (line.isBlank()) {
                continue;
            }

            try {
                events.add(JSON.readTree(line));
            } catch (IOException | RuntimeException failure) {
                throw new IllegalStateException(
                        "Captured log line is not a platform-log-event-v1 JSONL record",
                        failure
                );
            }
        }

        return List.copyOf(events);
    }

    public String rendered() {
        StringBuilder out = new StringBuilder();
        for (CapturedLogEvent event : events()) {
            out.append(event.level()).append(' ')
                    .append(event.eventCode().code()).append(' ')
                    .append(event.message()).append(' ')
                    .append(event.fields())
                    .append(' ')
                    .append(event.throwableDigest() == null ? "" : event.throwableDigest())
                    .append('\n');
        }

        return out.toString();
    }

    private String capturedText() {
        if (buffer == null) {
            throw new IllegalStateException("Agent log capture is not installed");
        }

        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static AgentEventCode eventCode(String code) {
        return Arrays.stream(AgentEventCode.values())
                .filter(candidate -> candidate.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Unknown event code: " + code));
    }

    private static CapturedLogEvent decode(String line) {
        try {
            JsonNode node = JSON.readTree(line);
            Map<String, String> fields = new LinkedHashMap<>();
            JsonNode fieldsNode = node.path("fields");
            fieldsNode.properties().forEach(entry ->
                    fields.put(entry.getKey(), entry.getValue().asText(""))
            );

            JsonNode errorType = node.get("error_type");
            JsonNode errorStacktrace = node.get("error_stacktrace");
            return new CapturedLogEvent(
                    Instant.parse(node.path("timestamp").asText()),
                    AgentLogLevel.valueOf(node.path("level").asText()),
                    eventCode(node.path("event_code").asText()),
                    node.path("event_name").asText(),
                    node.has("original_event_name") ? node.path("original_event_name").asText() : null,
                    node.path("logger").asText(),
                    node.path("message").asText(),
                    fields,
                    errorType == null || errorType.isNull() ? null : errorType.asText(),
                    "true".equals(node.path("stacktrace_truncated").asText()),
                    node.path("suppressed_count").asLong(0L),
                    errorStacktrace == null || errorStacktrace.isNull() ? null : errorStacktrace.asText()
            );
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("Captured log line is not a platform-log-event-v1 JSONL record", failure);
        }
    }
}
