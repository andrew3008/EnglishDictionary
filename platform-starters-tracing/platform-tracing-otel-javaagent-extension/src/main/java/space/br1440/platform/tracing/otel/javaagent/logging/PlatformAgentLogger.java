package space.br1440.platform.tracing.otel.javaagent.logging;

import java.util.Objects;

import space.br1440.platform.tracing.otel.javaagent.logging.event.AgentLogEventType;

public final class PlatformAgentLogger {

    private final String ownerName;
    private final AgentLoggingRuntime runtime;

    PlatformAgentLogger(String ownerName, AgentLoggingRuntime runtime) {
        this.ownerName = Objects.requireNonNull(ownerName, "ownerName");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    public static PlatformAgentLogger get(Class<?> owner) {
        Objects.requireNonNull(owner, "owner");

        String logger = AgentLogTextSanitizer.normalizeBounded(owner.getName(), AgentLogRecord.MAX_LOGGER_CHARS);
        return new PlatformAgentLogger(logger, AgentLoggingRuntime.shared());
    }

    public void log(AgentLogEventType eventType) {
        log(eventType, AgentLogFieldsWriter.NONE);
    }

    public void log(AgentLogEventType eventType, AgentLogFieldsWriter fieldsWriter) {
        AgentLogEventType requiredEventType = Objects.requireNonNull(eventType, "eventType");
        AgentLogFieldsWriter requiredFieldsWriter = Objects.requireNonNull(fieldsWriter, "fieldsWriter");
        if (!runtime.permits(requiredEventType.level())) {
            return;
        }

        emitAdmitted(requiredEventType, requiredFieldsWriter);
    }

    EmitResult emitAdmitted(AgentLogEventType eventType, AgentLogFieldsWriter fieldsWriter) {
        return emitAdmitted(eventType, fieldsWriter, 0L);
    }

    EmitResult emitAdmitted(AgentLogEventType eventType,
                            AgentLogFieldsWriter fieldsWriter,
                            long suppressedCount) {
        return runtime.emitAdmitted(ownerName, eventType, fieldsWriter, suppressedCount);
    }

    EmitResult emitRateTransitionAdmitted(AgentLogEventType markerType,
                                          AgentLogFieldsWriter markerWriter,
                                          AgentLogEventType problemType,
                                          AgentLogFieldsWriter problemWriter,
                                          long suppressedCount) {
        return runtime.emitRateTransitionAdmitted(
                ownerName,
                markerType,
                markerWriter,
                problemType,
                problemWriter,
                suppressedCount
        );
    }

    boolean permits(AgentLogEventType eventType) {
        return runtime.permits(eventType.level());
    }
}
