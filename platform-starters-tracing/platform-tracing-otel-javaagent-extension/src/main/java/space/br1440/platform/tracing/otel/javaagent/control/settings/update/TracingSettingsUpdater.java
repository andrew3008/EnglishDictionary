package space.br1440.platform.tracing.otel.javaagent.control.settings.update;

import java.util.Objects;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentEventCodes;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.control.settings.SettingsCommitOutcome;
import space.br1440.platform.tracing.otel.javaagent.control.settings.SettingsCommitResult;
import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsDigest;
import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsStore;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.ExpectedSettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsValidationConstraints;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;

public final class TracingSettingsUpdater {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(TracingSettingsUpdater.class);

    private final TracingSettingsStore store;
    private final SettingsValidationConstraints validationConstraints;
    private final LastSettingsUpdate diagnostics;

    public TracingSettingsUpdater(TracingSettingsStore store,
                                  SettingsValidationConstraints validationConstraints,
                                  LastSettingsUpdate diagnostics) {
        this.store = Objects.requireNonNull(store, "store");
        this.validationConstraints = Objects.requireNonNull(validationConstraints,"validationConstraints");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    public SettingsUpdateResult apply(SettingsUpdateAttempt attempt,
                                      ExpectedSettingsRevision expectedBase,
                                      TracingSettings candidate) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(expectedBase, "expectedBase");
        Objects.requireNonNull(candidate, "candidate");

        SettingsUpdateResult result;
        try {
            result = evaluate(expectedBase, candidate);
        } catch (RuntimeException exception) {
            log.warn(AgentEventCodes.CTRL_UPDATE, "Tracing settings update failed", exception, "attemptId", attempt.attemptId());
            TracingSettingsRevision accepted = store.current();
            result = new SettingsUpdateResult(
                    SettingsUpdateStatus.FAILED,
                    accepted,
                    TracingSettingsDigest.sha256(accepted.policy()),
                    SettingsRejectionReason.INTERNAL_FAILURE
            );
        }

        diagnostics.record(attempt, result);
        return result;
    }

    public SettingsUpdateResult rejectBeforeApply(String wireAttemptId, SettingsRejectionReason reason) {
        Objects.requireNonNull(reason, "reason");

        if (reason == SettingsRejectionReason.NONE) {
            throw new IllegalArgumentException("rejectBeforeApply requires a non-NONE rejection reason");
        }

        return recordPreApplyTerminal(wireAttemptId, SettingsUpdateStatus.REJECTED, reason);
    }

    public SettingsUpdateResult failBeforeApply(String wireAttemptId, SettingsRejectionReason reason) {
        Objects.requireNonNull(reason, "reason");

        if (reason == SettingsRejectionReason.NONE) {
            throw new IllegalArgumentException("failBeforeApply requires a non-NONE rejection reason");
        }

        return recordPreApplyTerminal(wireAttemptId, SettingsUpdateStatus.FAILED, reason);
    }

    private SettingsUpdateResult recordPreApplyTerminal(String wireAttemptId,
                                                        SettingsUpdateStatus status,
                                                        SettingsRejectionReason reason) {
        TracingSettingsRevision accepted = store.current();
        SettingsUpdateResult result = new SettingsUpdateResult(
                status,
                accepted,
                TracingSettingsDigest.sha256(accepted.policy()),
                reason
        );

        if (SettingsUpdateAttempt.isCanonicalAttemptId(wireAttemptId)) {
            diagnostics.record(SettingsUpdateAttempt.forPreApplyDiagnostics(wireAttemptId), result);
        } else {
            diagnostics.recordPreApplyWithoutAttempt(result);
        }

        return result;
    }

    private SettingsUpdateResult evaluate(ExpectedSettingsRevision expectedBase,
                                          TracingSettings candidate) {
        SettingsRejectionReason validationCode = TracingSettingsValidator.validate(
                candidate,
                validationConstraints
        );

        if (validationCode != SettingsRejectionReason.NONE) {
            TracingSettingsRevision accepted = store.current();
            return result(
                    SettingsUpdateStatus.REJECTED,
                    accepted,
                    validationCode
            );
        }

        TracingSettingsRevision witnessed = store.current();
        SettingsRejectionReason staleCode = staleCode(witnessed, expectedBase);
        if (staleCode != SettingsRejectionReason.NONE) {
            return result(SettingsUpdateStatus.STALE, witnessed, staleCode);
        }

        if (candidate.equals(witnessed.policy())) {
            TracingSettingsRevision confirmed = store.current();
            if (confirmed == witnessed) {
                return result(SettingsUpdateStatus.UNCHANGED, witnessed, SettingsRejectionReason.NONE);
            }

            return result(
                    SettingsUpdateStatus.STALE,
                    confirmed,
                    SettingsRejectionReason.ACCEPTED_SEQUENCE_MISMATCH
            );
        }

        SettingsCommitResult commit = store.commit(expectedBase, candidate);
        if (commit.outcome() == SettingsCommitOutcome.APPLIED) {
            return result(SettingsUpdateStatus.APPLIED, commit.accepted(), SettingsRejectionReason.NONE);
        }

        return result(
                SettingsUpdateStatus.STALE,
                commit.accepted(),
                SettingsRejectionReason.ACCEPTED_SEQUENCE_MISMATCH
        );
    }

    private static SettingsUpdateResult result(SettingsUpdateStatus outcome,
                                               TracingSettingsRevision accepted,
                                               SettingsRejectionReason messageCode) {
        return new SettingsUpdateResult(
                outcome,
                accepted,
                TracingSettingsDigest.sha256(accepted.policy()),
                messageCode
        );
    }

    private static SettingsRejectionReason staleCode(TracingSettingsRevision accepted,
                                                     ExpectedSettingsRevision expectedBase) {
        if (!accepted.runtimeInstanceId().equals(expectedBase.runtimeInstanceId())) {
            return SettingsRejectionReason.RUNTIME_INSTANCE_MISMATCH;
        }

        if (accepted.acceptedSequence() != expectedBase.acceptedSequence()) {
            return SettingsRejectionReason.ACCEPTED_SEQUENCE_MISMATCH;
        }

        return SettingsRejectionReason.NONE;
    }
}
