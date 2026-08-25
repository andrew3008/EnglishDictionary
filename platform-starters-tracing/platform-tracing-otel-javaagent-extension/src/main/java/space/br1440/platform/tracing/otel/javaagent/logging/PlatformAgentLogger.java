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
        return new PlatformAgentLogger(owner.getName(), AgentLoggingRuntime.shared());
    }

    public boolean isEnabled(AgentLogLevel eventLevel) {
        return runtime.isEnabled(eventLevel);
    }

    public boolean isDebugEnabled() {
        return isEnabled(AgentLogLevel.DEBUG);
    }

    public EmitResult error(String eventCode, String message, Object... fields) {
        return emit(AgentLogLevel.ERROR, eventCode, message, null, fields);
    }

    public EmitResult error(String eventCode, String message, Throwable error, Object... fields) {
        return emit(AgentLogLevel.ERROR, eventCode, message, error, fields);
    }

    public EmitResult warn(String eventCode, String message, Object... fields) {
        return emit(AgentLogLevel.WARN, eventCode, message, null, fields);
    }

    public EmitResult warn(String eventCode, String message, Throwable error, Object... fields) {
        return emit(AgentLogLevel.WARN, eventCode, message, error, fields);
    }

    public EmitResult info(String eventCode, String message, Object... fields) {
        return emit(AgentLogLevel.INFO, eventCode, message, null, fields);
    }

    public EmitResult debug(String eventCode, String message, Object... fields) {
        return emit(AgentLogLevel.DEBUG, eventCode, message, null, fields);
    }

    public EmitResult trace(String eventCode, String message, Object... fields) {
        return emit(AgentLogLevel.TRACE, eventCode, message, null, fields);
    }

    public EmitResult emit(AgentLogLevel level,
                           String eventCode,
                           String message,
                           Throwable error,
                           Object... fields) {
        Objects.requireNonNull(level, "level");
        if (!runtime.isEnabled(level)) {
            return EmitResult.FILTERED_BY_LEVEL;
        }

        if (!AgentEventCodes.isKnown(eventCode)) {
            return EmitResult.REJECTED_BY_SECURITY_POLICY;
        }

        String sanitizedMessage = AgentLogTextSanitizer.normalizeBounded(message, AgentLogEvent.MAX_ARGUMENT_CHARS);
        if (sanitizedMessage.isBlank()) {
            return EmitResult.REJECTED_BY_SECURITY_POLICY;
        }

        String sanitizedLogger = AgentLogTextSanitizer.normalizeBounded(ownerName, AgentLogEvent.MAX_LOGGER_CHARS);
        if (sanitizedLogger.isBlank()) {
            return EmitResult.REJECTED_BY_SECURITY_POLICY;
        }

        Map<String, String> sanitized = sanitizeFields(fields);
        if (sanitized == null) {
            return EmitResult.REJECTED_BY_SECURITY_POLICY;
        }

        AgentLogEvent event = new AgentLogEvent(
                Instant.now(),
                level,
                eventCode,
                sanitizedLogger,
                sanitizedMessage,
                sanitized,
                BoundedThrowableRenderer.render(error)
        );
        return runtime.emit(event);
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
