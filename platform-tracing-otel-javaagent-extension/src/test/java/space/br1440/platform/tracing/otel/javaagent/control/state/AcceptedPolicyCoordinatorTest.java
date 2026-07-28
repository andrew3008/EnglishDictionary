package space.br1440.platform.tracing.otel.javaagent.control.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class AcceptedPolicyCoordinatorTest {

    @Test
    void exposesAllApplyOutcomesWithoutHiddenRetry() throws Exception {
        AcceptedPolicyStateStore store = store();
        PolicyApplyDiagnostics diagnostics = new PolicyApplyDiagnostics();
        PolicyAuditBuffer audit = new PolicyAuditBuffer();
        AcceptedPolicyCoordinator coordinator = new AcceptedPolicyCoordinator(
                store,
                new PolicyValidationConstraints(false),
                diagnostics,
                audit);

        ExpectedPolicyBase initialBase = store.current().base();
        PolicyApplyResult applied = coordinator.apply(
                AcceptedPolicyTestFixtures.attempt(1L),
                initialBase,
                AcceptedPolicyTestFixtures.policy(0.2d));
        PolicyApplyResult unchanged = coordinator.apply(
                AcceptedPolicyTestFixtures.attempt(2L),
                applied.accepted().base(),
                applied.accepted().policy());
        PolicyApplyResult stale = coordinator.apply(
                AcceptedPolicyTestFixtures.attempt(3L),
                initialBase,
                AcceptedPolicyTestFixtures.policy(0.3d));
        PolicyApplyResult rejected = coordinator.apply(
                AcceptedPolicyTestFixtures.attempt(4L),
                applied.accepted().base(),
                strictPolicy());

        setAcceptedSequence(store, Long.MAX_VALUE);
        PolicyApplyResult failed = coordinator.apply(
                AcceptedPolicyTestFixtures.attempt(5L),
                store.current().base(),
                AcceptedPolicyTestFixtures.policy(0.4d));

        assertThat(applied.outcome()).isEqualTo(PolicyApplyOutcome.APPLIED);
        assertThat(applied.accepted().acceptedSequence()).isEqualTo(1L);
        assertThat(unchanged.outcome()).isEqualTo(PolicyApplyOutcome.UNCHANGED);
        assertThat(unchanged.accepted()).isSameAs(applied.accepted());
        assertThat(stale.outcome()).isEqualTo(PolicyApplyOutcome.STALE);
        assertThat(stale.messageCode())
                .isEqualTo(PolicyMessageCode.ACCEPTED_SEQUENCE_MISMATCH);
        assertThat(rejected.outcome()).isEqualTo(PolicyApplyOutcome.REJECTED);
        assertThat(rejected.messageCode())
                .isEqualTo(PolicyMessageCode.STRICT_RUNTIME_NOT_ALLOWED);
        assertThat(failed.outcome()).isEqualTo(PolicyApplyOutcome.FAILED);
        assertThat(failed.messageCode()).isEqualTo(PolicyMessageCode.INTERNAL_FAILURE);

        PolicyApplyDiagnosticsSnapshot snapshot = diagnostics.snapshot();
        assertThat(snapshot.appliedCount()).isEqualTo(1L);
        assertThat(snapshot.unchangedCount()).isEqualTo(1L);
        assertThat(snapshot.staleCount()).isEqualTo(1L);
        assertThat(snapshot.rejectedCount()).isEqualTo(1L);
        assertThat(snapshot.failedCount()).isEqualTo(1L);
        assertThat(audit.recent()).hasSize(5);
    }

    @Test
    void runtimeMismatchIsReportedSeparately() {
        AcceptedPolicyStateStore store = store();
        AcceptedPolicyCoordinator coordinator = new AcceptedPolicyCoordinator(
                store,
                new PolicyValidationConstraints(true),
                new PolicyApplyDiagnostics(),
                new PolicyAuditBuffer());

        PolicyApplyResult result = coordinator.apply(
                AcceptedPolicyTestFixtures.attempt(1L),
                new ExpectedPolicyBase(
                        "00000000-0000-0000-0000-000000000002",
                        0L),
                AcceptedPolicyTestFixtures.policy(0.2d));

        assertThat(result.outcome()).isEqualTo(PolicyApplyOutcome.STALE);
        assertThat(result.messageCode()).isEqualTo(PolicyMessageCode.RUNTIME_INSTANCE_MISMATCH);
        assertThat(store.current().acceptedSequence()).isZero();
    }

    @Test
    void auditIsBoundedAndContainsOnlyMetadata() {
        PolicyAuditBuffer audit = new PolicyAuditBuffer();
        AcceptedPolicyEnvelope accepted = store().current();
        String digest = EffectiveTracingPolicyDigest.sha256(accepted.policy());

        for (long ordinal = 1L; ordinal <= 40L; ordinal++) {
            audit.record(
                    AcceptedPolicyTestFixtures.attempt(ordinal),
                    new PolicyApplyResult(
                            PolicyApplyOutcome.UNCHANGED,
                            accepted,
                            digest,
                            PolicyMessageCode.NONE));
        }

        assertThat(audit.recent()).hasSize(PolicyAuditBuffer.CAPACITY);
        assertThat(audit.recent().get(0).attempt().sourceRevision()).isEqualTo("revision-9");
        assertThat(PolicyAuditEntry.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly(
                        "attempt",
                        "outcome",
                        "messageCode",
                        "acceptedSequence",
                        "payloadDigest");
    }

    private static AcceptedPolicyStateStore store() {
        return new AcceptedPolicyStateStore(
                AcceptedPolicyTestFixtures.RUNTIME_INSTANCE_ID,
                AcceptedPolicyTestFixtures.policy(0.1d));
    }

    private static EffectiveTracingPolicy strictPolicy() {
        return EffectiveTracingPolicy.of(
                true,
                0.2d,
                Map.of(),
                true,
                true,
                true,
                true,
                PolicyLogLevel.INFO);
    }

    @SuppressWarnings("unchecked")
    private static void setAcceptedSequence(
            AcceptedPolicyStateStore store,
            long acceptedSequence) throws Exception {
        Field currentField = AcceptedPolicyStateStore.class.getDeclaredField("current");
        currentField.setAccessible(true);
        AtomicReference<AcceptedPolicyEnvelope> current =
                (AtomicReference<AcceptedPolicyEnvelope>) currentField.get(store);
        AcceptedPolicyEnvelope previous = current.get();
        current.set(new AcceptedPolicyEnvelope(
                previous.runtimeInstanceId(),
                acceptedSequence,
                previous.policy()));
    }
}
