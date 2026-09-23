package space.br1440.platform.tracing.otel.javaagent.logging;

import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import space.br1440.platform.common.util.ArrayUtils;

public final class PlatformAgentLogger {

    private final String ownerName;
    private final AgentLoggingRuntime runtime;

    private PlatformAgentLogger(String ownerName, AgentLoggingRuntime runtime) {
        this.ownerName = Objects.requireNonNull(ownerName, "ownerName");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    public static PlatformAgentLogger get(Class<?> owner) {
        Objects.requireNonNull(owner, "owner");
        String logger = AgentLogTextSanitizer.normalizeBounded(owner.getName(), AgentLogEvent.MAX_LOGGER_CHARS);
        return new PlatformAgentLogger(logger, AgentLoggingRuntime.shared());
    }

    public boolean isEnabled(AgentLogLevel eventLevel) {
        return runtime.isEnabled(eventLevel);
    }

    public boolean isDebugEnabled() {
        return isEnabled(AgentLogLevel.DEBUG);
    }

    public EmitResult error(AgentEventCode eventCode, String message, Object... fields) {
        return emit(AgentLogLevel.ERROR, eventCode, message, null, fields);
    }

    public EmitResult error(AgentEventCode eventCode, String message, Throwable error, Object... fields) {
        return emit(AgentLogLevel.ERROR, eventCode, message, error, fields);
    }

    public EmitResult warn(AgentEventCode eventCode, String message, Object... fields) {
        return emit(AgentLogLevel.WARN, eventCode, message, null, fields);
    }

    public EmitResult warn(AgentEventCode eventCode, String message, Throwable error, Object... fields) {
        return emit(AgentLogLevel.WARN, eventCode, message, error, fields);
    }

    public EmitResult info(AgentEventCode eventCode, String message, Object... fields) {
        return emit(AgentLogLevel.INFO, eventCode, message, null, fields);
    }

    public EmitResult debug(AgentEventCode eventCode, String message, Object... fields) {
        return emit(AgentLogLevel.DEBUG, eventCode, message, null, fields);
    }

    public EmitResult emit(AgentLogLevel level,
                           AgentEventCode eventCode,
                           String message,
                           Throwable error,
                           Object... fields) {
        if (level == null || eventCode == null) {
            return EmitResult.REJECTED_BY_SECURITY_POLICY;
        }

        if (!runtime.isEnabled(level)) {
            return EmitResult.FILTERED_BY_LEVEL;
        }

        try {
            String sanitizedMessage = AgentLogTextSanitizer.normalizeBounded(message, AgentLogEvent.MAX_ARGUMENT_CHARS);
            Map<String, String> sanitized = sanitizeFields(fields);
            if (sanitizedMessage.isBlank() || sanitized == null || ownerName.isBlank()) {
                return EmitResult.REJECTED_BY_SECURITY_POLICY;
            }

            return runtime.emit(new AgentLogEvent(
                    Instant.now(),
                    level,
                    eventCode,
                    ownerName,
                    sanitizedMessage,
                    sanitized,
                    BoundedThrowableRenderer.render(error)
            ));
        } catch (RuntimeException invariantViolation) {
            return runtime.emitFallback(level, eventCode, FallbackReason.INVARIANT_VIOLATION);
        }
    }

    private static Map<String, String> sanitizeFields(Object[] fields) {
        if (ArrayUtils.isNullOrEmpty(fields)) {
            return Map.of();
        }

        if ((fields.length & 1) != 0) {
            return null;
        }

        int pairCount = (fields.length / 2);
        if (pairCount > AgentLogFieldNamePolicy.maxFields()) {
            return null;
        }

        Map<String, String> sanitized = new LinkedHashMap<>(pairCount);
        Set<String> seen = new HashSet<>(pairCount);
        for (int i = 0; i < fields.length; i += 2) {
            String name = (fields[i] == null) ? null : String.valueOf(fields[i]);
            if ((name == null) || AgentLogFieldNamePolicy.isRejectedFieldName(name)) {
                return null;
            }

            if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                return null;
            }

            String value = (fields[i + 1] == null) ? null : String.valueOf(fields[i + 1]);
            sanitized.put(name, AgentLogTextSanitizer.normalizeBounded(value, AgentLogEvent.MAX_ARGUMENT_CHARS));
        }

        return Collections.unmodifiableMap(sanitized);
    }
}
