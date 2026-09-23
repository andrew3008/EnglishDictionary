package space.br1440.platform.tracing.otel.javaagent.logging;

import java.time.Instant;
import java.util.Map;

/**
 * Разбор захваченной JSONL-строки для тестов вне пакета логгера.
 */
public record CapturedLogEvent(Instant timestamp,
                               AgentLogLevel level,
                               AgentEventCode eventCode,
                               String logger,
                               String message,
                               Map<String, String> fields,
                               String throwableDigest) {
}
