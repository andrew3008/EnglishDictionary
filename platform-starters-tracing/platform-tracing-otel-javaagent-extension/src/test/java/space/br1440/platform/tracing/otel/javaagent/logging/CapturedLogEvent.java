package space.br1440.platform.tracing.otel.javaagent.logging;

import java.time.Instant;
import java.util.Map;

import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;

public record CapturedLogEvent(Instant timestamp,
                               AgentLogLevel level,
                               AgentEventCode eventCode,
                               String eventName,
                               String originalEventName,
                               String logger,
                               String message,
                               Map<String, String> fields,
                               String errorType,
                               boolean stacktraceTruncated,
                               long suppressedCount,
                               String throwableDigest) {
}
