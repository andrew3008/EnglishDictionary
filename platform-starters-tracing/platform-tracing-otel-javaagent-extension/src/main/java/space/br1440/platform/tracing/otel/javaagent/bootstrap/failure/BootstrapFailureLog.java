package space.br1440.platform.tracing.otel.javaagent.bootstrap.failure;

import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.BootstrapCleanupFailures;
import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxRegistrationAssessment;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.event.BootstrapLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;

public final class BootstrapFailureLog {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(BootstrapFailureLog.class);

    private static final String CLOSE_PHASE = "close";

    private BootstrapFailureLog() {
    }

    public static void phaseFailed(FailureSite site, BootstrapFailure recorded) {
        emit(
                BootstrapLogEvent.BOOTSTRAP_PHASE_FAILED,
                recorded.effectiveCode(),
                recorded.phase(),
                site.subphase(),
                site.propertyName(),
                recorded.cause()
        );
    }

    public static void escalated(BootstrapFailure escalation, Throwable cleanupFailure) {
        emit(
                BootstrapLogEvent.BOOTSTRAP_PHASE_FAILED,
                escalation.effectiveCode(),
                escalation.phase(),
                escalation.phase(),
                null,
                cleanupFailure
        );
    }

    public static void rejected(FailureSite site, String failureCode, Throwable cause) {
        emit(
                BootstrapLogEvent.BOOTSTRAP_OPERATION_REJECTED,
                failureCode,
                site.phase(),
                site.subphase(),
                site.propertyName(),
                cause
        );
    }

    public static void rejected(BootstrapFailure rejection) {
        emit(
                BootstrapLogEvent.BOOTSTRAP_OPERATION_REJECTED,
                rejection.primaryCode(),
                rejection.phase(),
                rejection.phase(),
                null,
                rejection.cause()
        );
    }

    public static void closeFailed(BootstrapFailure failure, Throwable outcome) {
        log.log(BootstrapLogEvent.BOOTSTRAP_PHASE_FAILED, fields -> fields
                .putString(AgentLogField.FAILURE_CODE, failure.effectiveCode())
                .putString(AgentLogField.PHASE, CLOSE_PHASE)
                .putString(AgentLogField.SUBPHASE, CLOSE_PHASE)
                .putString(AgentLogField.RESOURCE_LABEL, BootstrapCleanupFailures.resourceLabel(outcome))
                .cause(outcome));
    }

    public static void remoteJmxDenied(RemoteJmxRegistrationAssessment denial) {
        log.log(BootstrapLogEvent.REMOTE_JMX_MUTATION_REGISTRATION_DENIED, fields -> fields
                .putString(AgentLogField.FAILURE_CODE, denial.failureCode().name())
                .putString(AgentLogField.REMEDIATION, denial.remediation())
                .putString(AgentLogField.OFFENDING_ARGUMENTS, String.join(",", denial.offendingArguments())));
    }

    private static void emit(BootstrapLogEvent event,
                             String failureCode,
                             String phase,
                             String subphase,
                             String propertyName,
                             Throwable cause) {
        log.log(event, fields -> {
            fields.putString(AgentLogField.FAILURE_CODE, failureCode)
                    .putString(AgentLogField.PHASE, phase)
                    .putString(AgentLogField.SUBPHASE, subphase);

            if (propertyName != null) {
                fields.putString(AgentLogField.PROPERTY_NAME, propertyName);
            }

            fields.cause(cause);
        });
    }
}
