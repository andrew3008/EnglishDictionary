package space.br1440.platform.tracing.otel.javaagent.logging;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Ставит byte-array sink на время теста и восстанавливает default.
 */
public final class AgentLogCapture implements BeforeEachCallback, AfterEachCallback {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ByteArrayOutputStream buffer;

    @Override
    public void beforeEach(ExtensionContext context) {
        install();
    }

    @Override
    public void afterEach(ExtensionContext context) {
        restore();
    }

    public void install() {
        buffer = new ByteArrayOutputStream();
        AgentLoggingRuntime.shared().installForTests(new PlatformStderrSink(buffer));
        AgentLoggingRuntime.shared().applyLevel(AgentLogLevel.TRACE);
    }

    public void restore() {
        AgentLoggingRuntime.resetSharedForTests();
        buffer = null;
    }

    public void applyLevel(AgentLogLevel threshold) {
        AgentLoggingRuntime.shared().applyLevel(threshold);
    }

    public List<AgentLogEvent> events() {
        if (buffer == null) {
            return List.of();
        }
        String text = buffer.toString(StandardCharsets.UTF_8);
        List<AgentLogEvent> events = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            if (line.isBlank()) {
                continue;
            }
            events.add(decode(line));
        }
        return List.copyOf(events);
    }

    public String rendered() {
        StringBuilder out = new StringBuilder();
        for (AgentLogEvent event : events()) {
            out.append(event.level()).append(' ')
                    .append(event.eventCode()).append(' ')
                    .append(event.message()).append(' ')
                    .append(event.fields())
                    .append(' ')
                    .append(event.throwableDigest() == null ? "" : event.throwableDigest())
                    .append('\n');
        }
        return out.toString();
    }

    private static AgentLogEvent decode(String line) {
        try {
            JsonNode node = JSON.readTree(line);
            Map<String, String> fields = new LinkedHashMap<>();
            JsonNode fieldsNode = node.path("fields");
            fieldsNode.fields().forEachRemaining(entry ->
                    fields.put(entry.getKey(), entry.getValue().asText("")));
            JsonNode errorStacktrace = node.get("error_stacktrace");
            return new AgentLogEvent(
                    Instant.parse(node.path("timestamp").asText()),
                    AgentLogLevel.valueOf(node.path("level").asText()),
                    node.path("event_code").asText(),
                    node.path("logger").asText(),
                    node.path("message").asText(),
                    fields,
                    errorStacktrace == null || errorStacktrace.isNull() ? null : errorStacktrace.asText()
            );
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("Captured log line is not a platform-log-event-v1 JSONL record", failure);
        }
    }
}
