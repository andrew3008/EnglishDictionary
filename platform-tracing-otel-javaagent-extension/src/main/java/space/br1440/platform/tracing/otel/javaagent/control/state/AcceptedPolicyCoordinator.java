package space.br1440.platform.tracing.otel.javaagent.control.state;

import java.util.Objects;

/**
 * Оркестрирует validation и apply outcomes, не расширяя ответственность CAS store.
 */
final class AcceptedPolicyCoordinator {

    private final AcceptedPolicyStateStore store;
    private final PolicyValidationConstraints validationConstraints;
    private final PolicyApplyDiagnostics diagnostics;
    private final PolicyAuditBuffer audit;

    AcceptedPolicyCoordinator(
            AcceptedPolicyStateStore store,
            PolicyValidationConstraints validationConstraints,
            PolicyApplyDiagnostics diagnostics,
            PolicyAuditBuffer audit) {
        this.store = Objects.requireNonNull(store, "store");
        this.validationConstraints = Objects.requireNonNull(
                validationConstraints,
                "validationConstraints");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    PolicyApplyResult apply(
            PolicyApplyAttempt attempt,
            ExpectedPolicyBase expectedBase,
            EffectiveTracingPolicy candidate) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(expectedBase, "expectedBase");
        Objects.requireNonNull(candidate, "candidate");

        PolicyApplyResult result;
        try {
            result = evaluate(expectedBase, candidate);
        } catch (RuntimeException exception) {
            AcceptedPolicyEnvelope accepted = store.current();
            result = new PolicyApplyResult(
                    PolicyApplyOutcome.FAILED,
                    accepted,
                    EffectiveTracingPolicyDigest.sha256(accepted.policy()),
                    PolicyMessageCode.INTERNAL_FAILURE);
        }

        diagnostics.record(attempt, result);
        audit.record(attempt, result);
        return result;
    }

    private PolicyApplyResult evaluate(
            ExpectedPolicyBase expectedBase,
            EffectiveTracingPolicy candidate) {
        PolicyValidationResult validation = EffectiveTracingPolicyValidator.validate(
                candidate,
                validationConstraints);
        if (!validation.valid()) {
            AcceptedPolicyEnvelope accepted = store.current();
            return result(
                    PolicyApplyOutcome.REJECTED,
                    accepted,
                    validation.messageCode());
        }

        AcceptedPolicyEnvelope witnessed = store.current();
        PolicyMessageCode staleCode = staleCode(witnessed, expectedBase);
        if (staleCode != PolicyMessageCode.NONE) {
            return result(PolicyApplyOutcome.STALE, witnessed, staleCode);
        }

        if (candidate.equals(witnessed.policy())) {
            AcceptedPolicyEnvelope confirmed = store.current();
            if (confirmed == witnessed) {
                return result(PolicyApplyOutcome.UNCHANGED, witnessed, PolicyMessageCode.NONE);
            }
            return result(
                    PolicyApplyOutcome.STALE,
                    confirmed,
                    PolicyMessageCode.ACCEPTED_SEQUENCE_MISMATCH);
        }

        PolicyCommitResult commit = store.commit(expectedBase, candidate);
        if (commit.outcome() == PolicyCommitOutcome.APPLIED) {
            return result(PolicyApplyOutcome.APPLIED, commit.accepted(), PolicyMessageCode.NONE);
        }
        return result(
                PolicyApplyOutcome.STALE,
                commit.accepted(),
                PolicyMessageCode.ACCEPTED_SEQUENCE_MISMATCH);
    }

    private static PolicyApplyResult result(
            PolicyApplyOutcome outcome,
            AcceptedPolicyEnvelope accepted,
            PolicyMessageCode messageCode) {
        return new PolicyApplyResult(
                outcome,
                accepted,
                EffectiveTracingPolicyDigest.sha256(accepted.policy()),
                messageCode);
    }

    private static PolicyMessageCode staleCode(
            AcceptedPolicyEnvelope accepted,
            ExpectedPolicyBase expectedBase) {
        if (!accepted.runtimeInstanceId().equals(expectedBase.runtimeInstanceId())) {
            return PolicyMessageCode.RUNTIME_INSTANCE_MISMATCH;
        }
        if (accepted.acceptedSequence() != expectedBase.acceptedSequence()) {
            return PolicyMessageCode.ACCEPTED_SEQUENCE_MISMATCH;
        }
        return PolicyMessageCode.NONE;
    }
}
