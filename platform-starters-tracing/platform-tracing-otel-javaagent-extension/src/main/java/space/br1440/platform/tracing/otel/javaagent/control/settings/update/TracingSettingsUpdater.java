package space.br1440.platform.tracing.otel.javaagent.control.settings.update;

import static java.util.Objects.requireNonNull;

import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.settings.SettingsCommitOutcome;
import space.br1440.platform.tracing.otel.javaagent.control.settings.SettingsCommitResult;
import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsStore;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.PolicyLeaf;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsSourceAuthority;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsWriter;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.event.ControlPlaneLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;

public final class TracingSettingsUpdater {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(TracingSettingsUpdater.class);

    private final Supplier<TracingSettingsRevision> current;
    private final SettingsCommit settingsCommit;
    private final Consumer<AgentLogLevel> diagnosticsLevelApplier;
    private final Object updateMonitor = new Object();

    public TracingSettingsUpdater(TracingSettingsStore store, Consumer<AgentLogLevel> diagnosticsLevelApplier) {
        TracingSettingsStore requiredStore = requireNonNull(store, "store");
        this.current = requiredStore::current;
        this.settingsCommit = requiredStore::commit;
        this.diagnosticsLevelApplier = requireNonNull(diagnosticsLevelApplier, "diagnosticsLevelApplier");
    }

    TracingSettingsUpdater(Supplier<TracingSettingsRevision> current,
                           SettingsCommit settingsCommit,
                           Consumer<AgentLogLevel> diagnosticsLevelApplier) {
        this.current = requireNonNull(current, "current");
        this.settingsCommit = requireNonNull(settingsCommit, "settingsCommit");
        this.diagnosticsLevelApplier = requireNonNull(diagnosticsLevelApplier, "diagnosticsLevelApplier");
    }

    public TracingSettingsRevision current() {
        return current.get();
    }

    public void projectDiagnosticsLevel() {
        synchronized (updateMonitor) {
            diagnosticsLevelApplier.accept(current.get().policy().diagnosticsLogLevel());
        }
    }

    public SettingsUpdateResult apply(SettingsUpdateAttempt attempt,
                                      SettingsRevision expectedBase,
                                      TracingSettings candidate) {
        requireNonNull(attempt, "attempt");
        requireNonNull(expectedBase, "expectedBase");
        requireNonNull(candidate, "candidate");

        synchronized (updateMonitor) {
            Decision decision;
            try {
                decision = decide(attempt, expectedBase, candidate);
            } catch (RuntimeException failure) {
                log.log(ControlPlaneLogEvent.TRACING_SETTINGS_UPDATE_FAILED, fields -> fields
                        .putString(AgentLogField.ATTEMPT_ID, attempt.attemptId())
                        .cause(failure));
                return terminal(SettingsUpdateStatus.FAILED, SettingsRejectionReason.INTERNAL_FAILURE);
            }

            if (decision.outcome() == SettingsUpdateStatus.APPLIED) {
                applyDiagnosticsLevel(attempt, decision.accepted());
            }

            logResolved(attempt, decision);
            return decision.toResult();
        }
    }

    public SettingsUpdateResult rejectBeforeApply(SettingsRejectionReason reason) {
        return terminal(SettingsUpdateStatus.REJECTED, requireRejectionReason(reason));
    }

    public SettingsUpdateResult failBeforeApply(SettingsRejectionReason reason) {
        return terminal(SettingsUpdateStatus.FAILED, requireRejectionReason(reason));
    }

    private Decision decide(SettingsUpdateAttempt attempt,
                            SettingsRevision expectedBase,
                            TracingSettings candidate) {
        TracingSettingsRevision witnessed = current.get();

        if (!witnessed.agentInstanceId().equals(expectedBase.agentInstanceId())) {
            return new Decision(
                    SettingsUpdateStatus.REJECTED,
                    witnessed,
                    SettingsRejectionReason.AGENT_INSTANCE_MISMATCH,
                    Set.of()
            );
        }

        if (witnessed.acceptedSequence() != expectedBase.acceptedSequence()) {
            return new Decision(
                    SettingsUpdateStatus.STALE,
                    witnessed,
                    SettingsRejectionReason.ACCEPTED_SEQUENCE_MISMATCH,
                    Set.of()
            );
        }

        SettingsWriter writer = attempt.writer();
        Set<PolicyLeaf> changed = PolicyLeaf.diff(witnessed.policy(), candidate);
        if (!writer.admits(changed)) {
            return new Decision(SettingsUpdateStatus.REJECTED, witnessed, SettingsRejectionReason.INVALID_VALUE, changed);
        }

        if (changed.isEmpty() && writer.authority() == witnessed.sourceAuthority()) {
            return new Decision(SettingsUpdateStatus.UNCHANGED, witnessed, SettingsRejectionReason.NONE, changed);
        }

        SettingsCommitResult commit = commit(expectedBase, candidate, writer.authority());
        if (commit.outcome() != SettingsCommitOutcome.APPLIED) {
            return new Decision(
                    SettingsUpdateStatus.FAILED,
                    witnessed,
                    SettingsRejectionReason.INTERNAL_FAILURE,
                    changed
            );
        }

        return new Decision(SettingsUpdateStatus.APPLIED, commit.accepted(), SettingsRejectionReason.NONE, changed);
    }

    private SettingsCommitResult commit(SettingsRevision expectedBase,
                                        TracingSettings candidate,
                                        SettingsSourceAuthority authority) {
        return settingsCommit.commit(expectedBase, candidate, authority);
    }

    private void applyDiagnosticsLevel(SettingsUpdateAttempt attempt, TracingSettingsRevision accepted) {
        try {
            diagnosticsLevelApplier.accept(accepted.policy().diagnosticsLogLevel());
        } catch (RuntimeException failure) {
            log.log(ControlPlaneLogEvent.DIAGNOSTICS_LOG_LEVEL_APPLY_FAILED, fields -> fields
                    .putString(AgentLogField.ATTEMPT_ID, attempt.attemptId())
                    .cause(failure));
        }
    }

    private SettingsUpdateResult terminal(SettingsUpdateStatus status, SettingsRejectionReason reason) {
        return result(status, current.get(), reason);
    }

    private static SettingsRejectionReason requireRejectionReason(SettingsRejectionReason reason) {
        requireNonNull(reason, "reason");

        if (reason == SettingsRejectionReason.NONE) {
            throw new IllegalArgumentException("Pre-apply termination requires a non-NONE reason");
        }

        return reason;
    }

    private static void logResolved(SettingsUpdateAttempt attempt, Decision decision) {
        log.log(resolvedEvent(decision.outcome()), fields -> {
            fields.putString(AgentLogField.ATTEMPT_ID, attempt.attemptId())
                    .putEnum(AgentLogField.WRITER, attempt.writer())
                    .putEnum(AgentLogField.OUTCOME, decision.outcome())
                    .putEnum(AgentLogField.MESSAGE_CODE, decision.messageCode())
                    .putLong(AgentLogField.ACCEPTED_SEQUENCE, decision.accepted().acceptedSequence());
            if (!decision.changed().isEmpty()) {
                fields.putString(AgentLogField.CHANGED_LEAVES, leafNames(decision.changed()));
            }
        });
    }

    private static ControlPlaneLogEvent resolvedEvent(SettingsUpdateStatus outcome) {
        return switch (outcome) {
            case APPLIED -> ControlPlaneLogEvent.TRACING_SETTINGS_UPDATE_APPLIED;
            case REJECTED, STALE -> ControlPlaneLogEvent.TRACING_SETTINGS_UPDATE_REJECTED;
            case UNCHANGED -> ControlPlaneLogEvent.TRACING_SETTINGS_UPDATE_UNCHANGED;
            case FAILED -> ControlPlaneLogEvent.TRACING_SETTINGS_UPDATE_COMMIT_FAILED;
        };
    }

    private static String leafNames(Set<PolicyLeaf> leaves) {
        return leaves.stream().map(PolicyLeaf::name).sorted().collect(Collectors.joining(","));
    }

    private static SettingsUpdateResult result(SettingsUpdateStatus status,
                                               TracingSettingsRevision accepted,
                                               SettingsRejectionReason reason) {
        return new SettingsUpdateResult(status, accepted, reason);
    }

    @FunctionalInterface
    interface SettingsCommit {
        SettingsCommitResult commit(SettingsRevision expectedBase,
                                    TracingSettings candidate,
                                    SettingsSourceAuthority authority);
    }

    private record Decision(SettingsUpdateStatus outcome,
                            TracingSettingsRevision accepted,
                            SettingsRejectionReason messageCode,
                            Set<PolicyLeaf> changed) {

        SettingsUpdateResult toResult() {
            return result(outcome, accepted, messageCode);
        }
    }
}
