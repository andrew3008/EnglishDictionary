package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.ACTIVATION_FAILED;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.FAILED_ROLLBACK_INCOMPLETE;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.SHUTDOWN_CLEANUP_FAILED;
import static space.br1440.platform.tracing.otel.javaagent.readiness.ReadinessSnapshotAssertions.failureOf;

import java.io.IOException;
import java.io.OutputStream;
import jakarta.annotation.Nonnull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.BootstrapCleanupFailures;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.BootstrapResources;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSession;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionReadinessWireSnapshot;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionRuntimeEvidence;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLoggingTestSupport;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionCapability;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadiness;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadinessSnapshot;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class BootstrapTerminalTransitionsTest {

    private static final String BATCH_ID = "batch-1";
    private static final CustomizationStep STEP = CustomizationStep.SPAN_PROCESSOR_INITIALIZATION;
    private static final long READY_EVIDENCE_GENERATION = 1L;

    @Test
    void terminalize_records_failure_and_publishes_it_in_the_same_section() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        Fixture fixture = activeFixture(lifecycle);
        IllegalStateException activationFailure = new IllegalStateException("Activation failed");

        // when
        FailureAdmission admission = recordActivationFailure(fixture, activationFailure);

        // then
        assertThat(admission.kind()).isEqualTo(FailureAdmission.Kind.RECORDED);
        assertThat(admission.projectionFailure()).isNull();
        assertThat(admission.failure().primaryCode()).isEqualTo(ACTIVATION_FAILED);
        assertThat(admission.failure().phase()).isEqualTo("activate");
        assertThat(admission.failure().cause()).isSameAs(activationFailure);
        assertThat(fixture.journal().current().failureCode()).isEqualTo(ACTIVATION_FAILED);
        assertThat(failureOf(fixture.readiness().snapshot()).primaryCode()).isEqualTo(ACTIVATION_FAILED);
    }

    @Test
    void escalation_keeps_primary_failure_and_publishes_rollback_incomplete_without_graph_mutation() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        Fixture fixture = activeFixture(lifecycle);
        IllegalStateException activationFailure = new IllegalStateException("Activation failed");
        IllegalStateException cleanupFailure = new IllegalStateException("Runtime controls cleanup failed");
        recordActivationFailure(fixture, activationFailure);

        // when
        FailureAdmission escalation = fixture.transitions().escalate("activate", cleanupFailure, BATCH_ID, fixture.evidence());

        // then
        assertThat(escalation.kind()).isEqualTo(FailureAdmission.Kind.RECORDED);
        assertThat(escalation.projectionFailure()).isNull();
        assertThat(escalation.failure().primaryCode()).isEqualTo(ACTIVATION_FAILED);
        assertThat(escalation.failure().effectiveCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
        assertThat(escalation.failure().cause()).isSameAs(activationFailure);
        assertThat(activationFailure.getSuppressed()).isEmpty();
        assertThat(lifecycle.failure()).isSameAs(escalation.failure());
        assertThat(failureOf(fixture.readiness().snapshot()).effectiveCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
        assertThat(failureOf(fixture.readiness().snapshot()).primaryCode()).isEqualTo(ACTIVATION_FAILED);
        assertThat(fixture.journal().current().failureCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
        assertThat(fixture.journal().current().primaryFailureCode()).isEqualTo(ACTIVATION_FAILED);
    }

    @Test
    void escalation_projection_failure_is_captured_after_lifecycle_escalation() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        Fixture fixture = activeFixtureWithJournalSpy(lifecycle);
        IllegalStateException activationFailure = new IllegalStateException("Activation failed");
        IllegalStateException cleanupFailure = new IllegalStateException("Runtime controls cleanup failed");
        LinkageError publicationFailure = new LinkageError("Fatal readiness publication failure");
        recordActivationFailure(fixture, activationFailure);
        doThrow(publicationFailure)
                .when(fixture.journal())
                .publishFailed(any(BootstrapFailure.class), eq(BATCH_ID), same(fixture.evidence()));

        // when
        FailureAdmission escalation = fixture.transitions().escalate("activate", cleanupFailure, BATCH_ID, fixture.evidence());

        // then
        assertThat(escalation.kind()).isEqualTo(FailureAdmission.Kind.RECORDED);
        assertThat(escalation.projectionFailure()).isSameAs(publicationFailure);
        assertThat(lifecycle.failure().effectiveCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
        assertThat(activationFailure.getSuppressed()).isEmpty();
        assertThat(publicationFailure.getSuppressed()).isEmpty();
        assertThat(fixture.journal().current().failureCode()).isEqualTo(ACTIVATION_FAILED);
    }

    @Test
    void repeated_escalation_is_already_failed_without_republication() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        Fixture fixture = activeFixtureWithJournalSpy(lifecycle);
        IllegalStateException activationFailure = new IllegalStateException("Activation failed");
        IllegalStateException cleanupFailure = new IllegalStateException("Runtime controls cleanup failed");
        recordActivationFailure(fixture, activationFailure);
        FailureAdmission first = fixture.transitions().escalate("activate", cleanupFailure, BATCH_ID, fixture.evidence());
        clearInvocations(fixture.journal());

        // when
        FailureAdmission repeated = fixture.transitions().escalate("close", cleanupFailure, BATCH_ID, fixture.evidence());

        // then
        assertThat(repeated.kind()).isEqualTo(FailureAdmission.Kind.ALREADY_FAILED);
        assertThat(repeated.failure()).isSameAs(first.failure());
        verify(fixture.journal(), times(0)).publishFailed(any(BootstrapFailure.class), any(), any());
    }
    @Test
    void early_failure_fence_preserves_ready_evidence_and_avoids_final_republication() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        Fixture fixture = activeFixture(lifecycle);
        IllegalStateException cleanupFailure = new IllegalStateException("Diagnostic readiness cleanup failed");
        BootstrapLifecycleState.CloseAcquisition acquisition = lifecycle.acquireClose();

        // when
        fixture.transitions().publishCloseFailureSnapshot(acquisition.context(), cleanupFailure);
        ExtensionReadinessWireSnapshot fenced = fixture.journal().current();
        Throwable outcome = fixture.transitions().completeCloseFailure(
                acquisition.context(),
                cleanupFailure,
                null,
                true
        );
        lifecycle.completeClose(acquisition.flight(), outcome);

        // then
        assertThat(acquisition.owner()).isTrue();
        assertThat(outcome).isSameAs(cleanupFailure);
        assertThat(lifecycle.failure().cause()).isSameAs(cleanupFailure);
        assertThat(lifecycle.failure().primaryCode()).isEqualTo(SHUTDOWN_CLEANUP_FAILED);
        assertThat(lifecycle.failure().effectiveCode()).isEqualTo(SHUTDOWN_CLEANUP_FAILED);
        assertThat(fixture.journal().current()).isSameAs(fenced);
        assertThat(fenced.batchId()).isEqualTo(BATCH_ID);
        assertThat(fenced.evidenceGeneration()).isEqualTo(READY_EVIDENCE_GENERATION + 1L);
        assertThat(fenced.evidence()).isSameAs(fixture.evidence());
        assertThat(fenced.primaryFailureCode()).isEqualTo(SHUTDOWN_CLEANUP_FAILED);
        assertThat(fenced.failureCode()).isEqualTo(SHUTDOWN_CLEANUP_FAILED);
        assertFollowerReplays(lifecycle, acquisition, outcome);
    }

    @Test
    void graph_reachable_cleanup_without_settlement_is_terminalized_by_close() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        Fixture fixture = activeFixtureWithMockJournal(lifecycle);
        IllegalStateException activationFailure = new IllegalStateException("Activation failed");
        FailureAdmission admission = recordActivationFailure(fixture, activationFailure);
        assertThat(admission.failure().cause()).isSameAs(activationFailure);
        clearInvocations(fixture.journal());
        IllegalStateException cleanupFailure = new IllegalStateException("Resource cleanup failed");
        BootstrapCleanupFailures.suppressPreservingPrimary(activationFailure, cleanupFailure);

        // when
        CloseResult result = completeCloseFailure(fixture, cleanupFailure, null);

        // then
        assertThat(lifecycle.failure().primaryCode()).isEqualTo(ACTIVATION_FAILED);
        assertThat(lifecycle.failure().effectiveCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
        assertThat(lifecycle.failure().cause()).isSameAs(activationFailure);
        assertThat(result.outcome()).isSameAs(cleanupFailure);
        assertThat(activationFailure.getSuppressed()).containsExactly(cleanupFailure);
        verifySingleFailurePublication(fixture);
        assertFollowerReplays(lifecycle, result.acquisition(), result.outcome());
    }

    @Test
    void settled_close_repairs_stale_journal_without_close_log() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        Fixture fixture = activeFixture(lifecycle);
        IllegalStateException activationFailure = new IllegalStateException("Activation failed");
        FailureAdmission admission = recordActivationFailure(fixture, activationFailure);
        assertThat(admission.failure().cause()).isSameAs(activationFailure);
        IllegalStateException cleanupFailure = new IllegalStateException("Resource cleanup failed");
        BootstrapCleanupFailures.suppressPreservingPrimary(activationFailure, cleanupFailure);
        lifecycle.escalateRollbackIncomplete("activate", cleanupFailure);
        assertThat(fixture.journal().current().failureCode()).isEqualTo(ACTIVATION_FAILED);
        BootstrapLifecycleState.CloseAcquisition acquisition = lifecycle.acquireClose();

        // when
        Throwable outcome;
        try (AgentLogCapture logs = AgentLogCapture.installed()) {
            outcome = fixture.transitions().completeCloseFailure(
                    acquisition.context(),
                    cleanupFailure,
                    null,
                    false
            );
            lifecycle.completeClose(acquisition.flight(), outcome);

            // then
            assertThat(logs.events())
                    .filteredOn(event -> "BOOTSTRAP_PHASE_FAILED".equals(event.eventName())
                            && "close".equals(event.fields().get("phase")))
                    .isEmpty();
        }
        assertThat(outcome).isSameAs(cleanupFailure);
        assertThat(lifecycle.failure().effectiveCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
        assertThat(fixture.journal().current().primaryFailureCode()).isEqualTo(ACTIVATION_FAILED);
        assertThat(fixture.journal().current().failureCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
        assertFollowerReplays(lifecycle, acquisition, outcome);
    }

    @Test
    void failed_journal_repair_keeps_fatal_first_outcome_without_close_log() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        Fixture fixture = activeFixtureWithJournalSpy(lifecycle);
        IllegalStateException activationFailure = new IllegalStateException("Activation failed");
        FailureAdmission admission = recordActivationFailure(fixture, activationFailure);
        assertThat(admission.failure().cause()).isSameAs(activationFailure);
        IllegalStateException cleanupFailure = new IllegalStateException("Resource cleanup failed");
        BootstrapCleanupFailures.suppressPreservingPrimary(activationFailure, cleanupFailure);
        lifecycle.escalateRollbackIncomplete("activate", cleanupFailure);
        LinkageError publicationFailure = new LinkageError("Fatal readiness publication failure");
        clearInvocations(fixture.journal());
        doThrow(publicationFailure).when(fixture.journal()).publishFailed(
                any(BootstrapFailure.class),
                eq(BATCH_ID),
                same(fixture.evidence())
        );
        BootstrapLifecycleState.CloseAcquisition acquisition = lifecycle.acquireClose();

        // when
        Throwable outcome;
        try (AgentLogCapture logs = AgentLogCapture.installed()) {
            outcome = fixture.transitions().completeCloseFailure(
                    acquisition.context(),
                    cleanupFailure,
                    null,
                    false
            );
            lifecycle.completeClose(acquisition.flight(), outcome);

            // then
            assertThat(logs.events())
                    .filteredOn(event -> "BOOTSTRAP_PHASE_FAILED".equals(event.eventName())
                            && "close".equals(event.fields().get("phase")))
                    .isEmpty();
        }
        assertThat(outcome).isSameAs(publicationFailure);
        assertThat(publicationFailure.getSuppressed()).containsExactly(cleanupFailure);
        assertThat(BootstrapCleanupFailures.contains(activationFailure, cleanupFailure)).isTrue();
        assertThat(BootstrapCleanupFailures.contains(activationFailure, publicationFailure)).isTrue();
        assertThat(fixture.journal().current().failureCode()).isEqualTo(ACTIVATION_FAILED);
        verifySingleFailurePublication(fixture);
        assertFollowerReplays(lifecycle, acquisition, outcome);
    }

    @Test
    void final_runtime_publication_failure_keeps_cleanup_as_lifecycle_and_flight_root() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        Fixture fixture = activeFixtureWithMockJournal(lifecycle);
        IllegalStateException cleanupFailure = new IllegalStateException("Resource cleanup failed");
        UnsupportedOperationException publicationFailure =
                new UnsupportedOperationException("Readiness publication failed");
        doThrow(publicationFailure).when(fixture.journal()).publishFailed(
                any(BootstrapFailure.class),
                eq(BATCH_ID),
                same(fixture.evidence())
        );

        // when
        CloseResult result = completeCloseFailure(fixture, cleanupFailure, null);

        // then
        assertNormalCloseOwnership(fixture, cleanupFailure);
        assertThat(result.outcome()).isSameAs(cleanupFailure);
        assertThat(cleanupFailure.getSuppressed()).containsExactly(publicationFailure);
        verifySingleFailurePublication(fixture);
        assertFollowerReplays(lifecycle, result.acquisition(), result.outcome());
    }

    @Test
    void final_publication_error_is_secondary_in_lifecycle_and_root_in_flight() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        Fixture fixture = activeFixtureWithMockJournal(lifecycle);
        IllegalStateException cleanupFailure = new IllegalStateException("Resource cleanup failed");
        LinkageError publicationFailure = new LinkageError("Fatal readiness publication failure");
        doThrow(publicationFailure).when(fixture.journal()).publishFailed(
                any(BootstrapFailure.class),
                eq(BATCH_ID),
                same(fixture.evidence())
        );

        // when
        CloseResult result = completeCloseFailure(fixture, cleanupFailure, null);

        // then
        assertNormalCloseOwnership(fixture, cleanupFailure);
        assertThat(cleanupFailure.getSuppressed()).containsExactly(publicationFailure);
        assertThat(result.outcome()).isSameAs(publicationFailure);
        assertThat(publicationFailure.getSuppressed()).isEmpty();
        assertThat(BootstrapCleanupFailures.contains(cleanupFailure, publicationFailure)).isTrue();
        assertThat(BootstrapCleanupFailures.contains(publicationFailure, cleanupFailure)).isFalse();
        verifySingleFailurePublication(fixture);
        assertFollowerReplays(lifecycle, result.acquisition(), result.outcome());
    }

    @Test
    void early_publication_error_is_secondary_in_lifecycle_and_root_in_flight() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        Fixture fixture = activeFixtureWithMockJournal(lifecycle);
        IllegalStateException cleanupFailure = new IllegalStateException("Readiness withdrawal failed");
        LinkageError publicationFailure = new LinkageError("Fatal early readiness publication failure");

        // when
        CloseResult result = completeCloseFailure(fixture, cleanupFailure, publicationFailure);

        // then
        assertNormalCloseOwnership(fixture, cleanupFailure);
        assertThat(cleanupFailure.getSuppressed()).containsExactly(publicationFailure);
        assertThat(result.outcome()).isSameAs(publicationFailure);
        assertThat(publicationFailure.getSuppressed()).isEmpty();
        assertThat(BootstrapCleanupFailures.contains(cleanupFailure, publicationFailure)).isTrue();
        assertThat(BootstrapCleanupFailures.contains(publicationFailure, cleanupFailure)).isFalse();
        verifySingleFailurePublication(fixture);
        assertFollowerReplays(lifecycle, result.acquisition(), result.outcome());
    }

    @Test
    void cleanup_error_remains_first_fatal_over_runtime_publication_failure() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        Fixture fixture = activeFixtureWithMockJournal(lifecycle);
        LinkageError cleanupFailure = new LinkageError("Fatal resource cleanup failure");
        IllegalStateException publicationFailure = new IllegalStateException("Readiness publication failed");
        doThrow(publicationFailure).when(fixture.journal()).publishFailed(
                any(BootstrapFailure.class),
                eq(BATCH_ID),
                same(fixture.evidence())
        );

        // when
        CloseResult result = completeCloseFailure(fixture, cleanupFailure, null);

        // then
        assertNormalCloseOwnership(fixture, cleanupFailure);
        assertThat(result.outcome()).isSameAs(cleanupFailure);
        assertThat(cleanupFailure.getSuppressed()).containsExactly(publicationFailure);
        verifySingleFailurePublication(fixture);
        assertFollowerReplays(lifecycle, result.acquisition(), result.outcome());
    }

    @Test
    void cleanup_error_remains_first_fatal_over_publication_error() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        Fixture fixture = activeFixtureWithMockJournal(lifecycle);
        NoClassDefFoundError cleanupFailure = new NoClassDefFoundError("First fatal resource cleanup failure");
        LinkageError publicationFailure = new LinkageError("Second fatal readiness publication failure");
        doThrow(publicationFailure).when(fixture.journal()).publishFailed(
                any(BootstrapFailure.class),
                eq(BATCH_ID),
                same(fixture.evidence())
        );

        // when
        CloseResult result = completeCloseFailure(fixture, cleanupFailure, null);

        // then
        assertNormalCloseOwnership(fixture, cleanupFailure);
        assertThat(result.outcome()).isSameAs(cleanupFailure);
        assertThat(cleanupFailure.getSuppressed()).containsExactly(publicationFailure);
        assertThat(publicationFailure.getSuppressed()).isEmpty();
        verifySingleFailurePublication(fixture);
        assertFollowerReplays(lifecycle, result.acquisition(), result.outcome());
    }

    @Test
    void assertion_cleanup_error_is_not_promoted_over_fatal_publication_error() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        Fixture fixture = activeFixtureWithMockJournal(lifecycle);
        AssertionError cleanupFailure = new AssertionError("Resource cleanup assertion failure");
        LinkageError publicationFailure = new LinkageError("Fatal readiness publication failure");
        doThrow(publicationFailure).when(fixture.journal()).publishFailed(
                any(BootstrapFailure.class),
                eq(BATCH_ID),
                same(fixture.evidence())
        );

        // when
        CloseResult result = completeCloseFailure(fixture, cleanupFailure, null);

        // then
        assertNormalCloseOwnership(fixture, cleanupFailure);
        assertThat(result.outcome()).isSameAs(publicationFailure);
        assertThat(cleanupFailure.getSuppressed()).containsExactly(publicationFailure);
        assertThat(publicationFailure.getSuppressed()).isEmpty();
        verifySingleFailurePublication(fixture);
        assertFollowerReplays(lifecycle, result.acquisition(), result.outcome());
    }

    @Test
    void preexisting_activation_cause_survives_fatal_first_close_outcome() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        Fixture fixture = activeFixtureWithMockJournal(lifecycle);
        IllegalStateException activationFailure = new IllegalStateException("Activation failed");
        FailureAdmission admission = recordActivationFailure(fixture, activationFailure);
        assertThat(admission.failure().cause()).isSameAs(activationFailure);
        clearInvocations(fixture.journal());
        IllegalStateException cleanupFailure = new IllegalStateException("Resource cleanup failed");
        LinkageError publicationFailure = new LinkageError("Fatal readiness publication failure");

        // when
        CloseResult result = completeCloseFailure(fixture, cleanupFailure, publicationFailure);

        // then
        assertThat(lifecycle.failure().cause()).isSameAs(activationFailure);
        assertThat(lifecycle.failure().primaryCode()).isEqualTo(ACTIVATION_FAILED);
        assertThat(lifecycle.failure().effectiveCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
        assertThat(activationFailure.getSuppressed()).containsExactly(cleanupFailure, publicationFailure);
        assertThat(result.outcome()).isSameAs(publicationFailure);
        assertThat(publicationFailure.getSuppressed()).containsExactly(cleanupFailure);
        assertThat(BootstrapCleanupFailures.contains(activationFailure, publicationFailure)).isTrue();
        assertThat(BootstrapCleanupFailures.contains(publicationFailure, activationFailure)).isFalse();
        verifySingleFailurePublication(fixture);
        assertFollowerReplays(lifecycle, result.acquisition(), result.outcome());
    }

    @Test
    void identical_fatal_cleanup_and_publication_failure_does_not_self_suppress() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        Fixture fixture = activeFixtureWithMockJournal(lifecycle);
        LinkageError sharedFailure = new LinkageError("Shared fatal shutdown failure");

        // when
        CloseResult result = completeCloseFailure(fixture, sharedFailure, sharedFailure);

        // then
        assertNormalCloseOwnership(fixture, sharedFailure);
        assertThat(result.outcome()).isSameAs(sharedFailure);
        assertThat(sharedFailure.getSuppressed()).isEmpty();
        verifySingleFailurePublication(fixture);
        assertFollowerReplays(lifecycle, result.acquisition(), result.outcome());
    }

    @Test
    void reachable_publication_fatal_is_promoted_without_backlink_cycle() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        Fixture fixture = activeFixtureWithMockJournal(lifecycle);
        IllegalStateException cleanupFailure = new IllegalStateException("Resource cleanup failed");
        LinkageError publicationFailure = new LinkageError("Fatal readiness publication failure");
        cleanupFailure.addSuppressed(publicationFailure);

        // when
        CloseResult result = completeCloseFailure(fixture, cleanupFailure, publicationFailure);

        // then
        assertNormalCloseOwnership(fixture, cleanupFailure);
        assertThat(result.outcome()).isSameAs(publicationFailure);
        assertThat(cleanupFailure.getSuppressed()).containsExactly(publicationFailure);
        assertThat(publicationFailure.getSuppressed()).isEmpty();
        assertThat(BootstrapCleanupFailures.contains(cleanupFailure, publicationFailure)).isTrue();
        assertThat(BootstrapCleanupFailures.contains(publicationFailure, cleanupFailure)).isFalse();
        verifySingleFailurePublication(fixture);
    }

    @Test
    void first_fatal_stays_root_when_later_fatal_already_reaches_it() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        Fixture fixture = activeFixtureWithMockJournal(lifecycle);
        NoClassDefFoundError cleanupFailure = new NoClassDefFoundError("First fatal resource cleanup failure");
        LinkageError publicationFailure = new LinkageError("Later fatal readiness publication failure");
        publicationFailure.addSuppressed(cleanupFailure);

        // when
        CloseResult result = completeCloseFailure(fixture, cleanupFailure, publicationFailure);

        // then
        assertNormalCloseOwnership(fixture, cleanupFailure);
        assertThat(result.outcome()).isSameAs(cleanupFailure);
        assertThat(cleanupFailure.getSuppressed()).isEmpty();
        assertThat(publicationFailure.getSuppressed()).containsExactly(cleanupFailure);
        assertThat(BootstrapCleanupFailures.contains(cleanupFailure, publicationFailure)).isFalse();
        assertThat(BootstrapCleanupFailures.contains(publicationFailure, cleanupFailure)).isTrue();
        verifySingleFailurePublication(fixture);
    }

    @Test
    void preexisting_cyclic_failure_graph_terminates_without_duplicate_edges() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        Fixture fixture = activeFixtureWithMockJournal(lifecycle);
        IllegalStateException cleanupFailure = new IllegalStateException("Resource cleanup failed");
        LinkageError publicationFailure = new LinkageError("Fatal readiness publication failure");
        cleanupFailure.addSuppressed(publicationFailure);
        publicationFailure.addSuppressed(cleanupFailure);

        // when
        CloseResult result = completeCloseFailure(fixture, cleanupFailure, publicationFailure);

        // then
        assertNormalCloseOwnership(fixture, cleanupFailure);
        assertThat(result.outcome()).isSameAs(publicationFailure);
        assertThat(cleanupFailure.getSuppressed()).containsExactly(publicationFailure);
        assertThat(publicationFailure.getSuppressed()).containsExactly(cleanupFailure);
        verifySingleFailurePublication(fixture);
    }

    @Test
    void shutdown_log_delivery_failure_preserves_cleanup_outcome_and_completes_flight() throws Exception {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        Fixture fixture = activeFixtureWithMockJournal(lifecycle);
        IllegalStateException cleanupFailure = new IllegalStateException("Resource cleanup failed");
        FailingOutputStream stream = new FailingOutputStream(new IOException("Log delivery failed"));
        AgentLoggingTestSupport.reset();

        // when
        CloseResult result;
        try (AutoCloseable ignored = AgentLoggingTestSupport.install(stream)) {
            result = completeCloseFailure(fixture, cleanupFailure, null);
        }

        // then
        assertNormalCloseOwnership(fixture, cleanupFailure);
        assertThat(result.outcome()).isSameAs(cleanupFailure);
        assertThat(cleanupFailure.getSuppressed()).isEmpty();
        assertThat(stream.writeAttempts()).isEqualTo(1);
        verifySingleFailurePublication(fixture);
        assertFollowerReplays(lifecycle, result.acquisition(), result.outcome());
        assertThat(stream.writeAttempts()).isEqualTo(1);
    }

    @Test
    void fatal_shutdown_logging_failure_becomes_secondary_then_flight_root_without_retry() throws Exception {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        Fixture fixture = activeFixtureWithMockJournal(lifecycle);
        IllegalStateException cleanupFailure = new IllegalStateException("Resource cleanup failed");
        LinkageError loggerFailure = new LinkageError("Fatal log delivery failure");
        FailingOutputStream stream = new FailingOutputStream(loggerFailure);
        AgentLoggingTestSupport.reset();

        // when
        CloseResult result;
        try (AutoCloseable ignored = AgentLoggingTestSupport.install(stream)) {
            result = completeCloseFailure(fixture, cleanupFailure, null);
        }

        // then
        assertNormalCloseOwnership(fixture, cleanupFailure);
        assertThat(cleanupFailure.getSuppressed()).containsExactly(loggerFailure);
        assertThat(result.outcome()).isSameAs(loggerFailure);
        assertThat(loggerFailure.getSuppressed()).isEmpty();
        assertThat(BootstrapCleanupFailures.contains(cleanupFailure, loggerFailure)).isTrue();
        assertThat(BootstrapCleanupFailures.contains(loggerFailure, cleanupFailure)).isFalse();
        assertThat(stream.writeAttempts()).isEqualTo(1);
        verifySingleFailurePublication(fixture);
        assertFollowerReplays(lifecycle, result.acquisition(), result.outcome());
        assertThat(stream.writeAttempts()).isEqualTo(1);
    }

    @Test
    void fatal_shutdown_logging_failure_preserves_preexisting_activation_cause() throws Exception {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        Fixture fixture = activeFixtureWithMockJournal(lifecycle);
        IllegalStateException activationFailure = new IllegalStateException("Activation failed");
        FailureAdmission admission = recordActivationFailure(fixture, activationFailure);
        assertThat(admission.failure().cause()).isSameAs(activationFailure);
        clearInvocations(fixture.journal());
        IllegalStateException cleanupFailure = new IllegalStateException("Resource cleanup failed");
        LinkageError loggerFailure = new LinkageError("Fatal log delivery failure");
        FailingOutputStream stream = new FailingOutputStream(loggerFailure);
        AgentLoggingTestSupport.reset();

        // when
        CloseResult result;
        try (AutoCloseable ignored = AgentLoggingTestSupport.install(stream)) {
            result = completeCloseFailure(fixture, cleanupFailure, null);
        }

        // then
        assertThat(lifecycle.failure().cause()).isSameAs(activationFailure);
        assertThat(lifecycle.failure().primaryCode()).isEqualTo(ACTIVATION_FAILED);
        assertThat(lifecycle.failure().effectiveCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
        assertThat(activationFailure.getSuppressed()).containsExactly(cleanupFailure, loggerFailure);
        assertThat(result.outcome()).isSameAs(loggerFailure);
        assertThat(loggerFailure.getSuppressed()).containsExactly(cleanupFailure);
        assertThat(BootstrapCleanupFailures.contains(activationFailure, loggerFailure)).isTrue();
        assertThat(BootstrapCleanupFailures.contains(loggerFailure, activationFailure)).isFalse();
        assertThat(stream.writeAttempts()).isEqualTo(1);
        verifySingleFailurePublication(fixture);
        assertFollowerReplays(lifecycle, result.acquisition(), result.outcome());
        assertThat(stream.writeAttempts()).isEqualTo(1);
    }

    @Test
    void accepted_operation_failure_after_close_is_rejected_without_publication() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        BootstrapReadinessJournal journal = mock(BootstrapReadinessJournal.class);
        Fixture fixture = activeFixture(lifecycle, journal);
        lifecycle.requireOperable("customization");
        BootstrapLifecycleState.CloseAcquisition acquisition = lifecycle.acquireClose();
        lifecycle.markClosed();
        lifecycle.completeClose(acquisition.flight(), null);
        IllegalStateException operationFailure = new IllegalStateException("Customization failed after close");

        // when
        FailureAdmission rejection = fixture.transitions().terminalize(
                FailureSite.customization(STEP),
                STEP.failureCode(),
                operationFailure,
                BATCH_ID,
                fixture.evidence()
        );

        // then
        assertThat(acquisition.owner()).isTrue();
        assertThat(rejection.kind()).isEqualTo(FailureAdmission.Kind.REJECTED);
        assertThat(rejection.failure().primaryCode()).isEqualTo(STEP.failureCode());
        assertThat(rejection.failure().effectiveCode()).isEqualTo(STEP.failureCode());
        assertThat(rejection.failure().phase()).isEqualTo("customization");
        assertThat(rejection.failure().cause()).isSameAs(operationFailure);
        assertThat(rejection.claimedSession()).isNull();
        assertThat(rejection.projectionFailure()).isNull();
        assertThat(lifecycle.state()).isEqualTo(BootstrapState.CLOSED);
        assertThatThrownBy(lifecycle::failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Bootstrap failure is not recorded");
        verifyNoInteractions(journal);
    }

    @Test
    void accepted_operation_failure_during_close_is_rejected_without_publication() {
        // given
        BootstrapLifecycleState lifecycle = new BootstrapLifecycleState();
        BootstrapReadinessJournal journal = mock(BootstrapReadinessJournal.class);
        Fixture fixture = activeFixture(lifecycle, journal);
        lifecycle.requireOperable("customization");
        BootstrapLifecycleState.CloseAcquisition acquisition = lifecycle.acquireClose();
        IllegalStateException operationFailure = new IllegalStateException("Customization failed during close");

        // when
        FailureAdmission rejection = fixture.transitions().terminalize(
                FailureSite.customization(STEP),
                STEP.failureCode(),
                operationFailure,
                BATCH_ID,
                fixture.evidence()
        );

        // then
        assertThat(acquisition.owner()).isTrue();
        assertThat(rejection.kind()).isEqualTo(FailureAdmission.Kind.REJECTED);
        assertThat(rejection.failure().primaryCode()).isEqualTo(STEP.failureCode());
        assertThat(rejection.failure().effectiveCode()).isEqualTo(STEP.failureCode());
        assertThat(rejection.failure().phase()).isEqualTo("customization");
        assertThat(rejection.failure().cause()).isSameAs(operationFailure);
        assertThat(rejection.claimedSession()).isNull();
        assertThat(rejection.projectionFailure()).isNull();
        assertThat(lifecycle.state()).isEqualTo(BootstrapState.CLOSING);
        assertThatThrownBy(lifecycle::failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Bootstrap failure is not recorded");
        verifyNoInteractions(journal);
        lifecycle.markClosed();
        lifecycle.completeClose(acquisition.flight(), null);
    }

    private static CloseResult completeCloseFailure(Fixture fixture,
                                                    Throwable cleanupFailure,
                                                    Throwable priorPublicationFailure) {
        BootstrapLifecycleState.CloseAcquisition acquisition = fixture.lifecycle().acquireClose();
        assertThat(acquisition.owner()).isTrue();
        assertThat(acquisition.reentrant()).isFalse();
        Throwable outcome = fixture.transitions().completeCloseFailure(
                acquisition.context(),
                cleanupFailure,
                priorPublicationFailure,
                false
        );
        fixture.lifecycle().completeClose(acquisition.flight(), outcome);
        return new CloseResult(acquisition, outcome);
    }

    private static void assertFollowerReplays(BootstrapLifecycleState lifecycle,
                                              BootstrapLifecycleState.CloseAcquisition owner,
                                              Throwable outcome) {
        BootstrapLifecycleState.CloseAcquisition follower = lifecycle.acquireClose();
        assertThat(follower.owner()).isFalse();
        assertThat(follower.reentrant()).isFalse();
        assertThat(follower.flight()).isSameAs(owner.flight());
        assertThat(follower.awaitOutcome()).isSameAs(outcome);
    }

    private static void assertNormalCloseOwnership(Fixture fixture, Throwable cleanupFailure) {
        assertThat(fixture.lifecycle().state()).isEqualTo(BootstrapState.FAILED);
        assertThat(fixture.lifecycle().failure().primaryCode()).isEqualTo(SHUTDOWN_CLEANUP_FAILED);
        assertThat(fixture.lifecycle().failure().effectiveCode()).isEqualTo(SHUTDOWN_CLEANUP_FAILED);
        assertThat(fixture.lifecycle().failure().cause()).isSameAs(cleanupFailure);
    }

    private static void verifySingleFailurePublication(Fixture fixture) {
        verify(fixture.journal(), times(1)).publishFailed(
                any(BootstrapFailure.class),
                eq(BATCH_ID),
                same(fixture.evidence())
        );
    }

    private static Fixture activeFixture(BootstrapLifecycleState lifecycle) {
        PlatformExtensionReadiness readiness = readyReadiness();
        AgentInstanceId agentInstanceId = AgentInstanceId.random();
        ExtensionRuntimeEvidence evidence = completeEvidence(readiness);
        BootstrapReadinessJournal journal = new BootstrapReadinessJournal(
                lifecycle.monitor(),
                readiness,
                agentInstanceId
        );
        publishReady(lifecycle, journal, readiness, agentInstanceId, evidence);
        return activeFixture(lifecycle, journal, readiness, evidence);
    }

    private static Fixture activeFixtureWithMockJournal(BootstrapLifecycleState lifecycle) {
        BootstrapReadinessJournal journal = mock(BootstrapReadinessJournal.class);
        Fixture fixture = activeFixture(lifecycle, journal);
        ExtensionReadinessWireSnapshot current = mock(ExtensionReadinessWireSnapshot.class);
        when(current.batchId()).thenReturn(BATCH_ID);
        when(current.evidence()).thenReturn(fixture.evidence());
        when(journal.current()).thenReturn(current);
        return fixture;
    }

    private static Fixture activeFixtureWithJournalSpy(BootstrapLifecycleState lifecycle) {
        PlatformExtensionReadiness readiness = readyReadiness();
        AgentInstanceId agentInstanceId = AgentInstanceId.random();
        ExtensionRuntimeEvidence evidence = completeEvidence(readiness);
        BootstrapReadinessJournal journal = spy(new BootstrapReadinessJournal(
                lifecycle.monitor(),
                readiness,
                agentInstanceId
        ));
        publishReady(lifecycle, journal, readiness, agentInstanceId, evidence);
        return activeFixture(lifecycle, journal, readiness, evidence);
    }

    private static void publishReady(BootstrapLifecycleState lifecycle,
                                     BootstrapReadinessJournal journal,
                                     PlatformExtensionReadiness readiness,
                                     AgentInstanceId agentInstanceId,
                                     ExtensionRuntimeEvidence evidence) {
        synchronized (lifecycle.monitor()) {
            journal.publishReady(ExtensionReadinessWireSnapshot.ready(
                    (PlatformExtensionReadinessSnapshot.Ready) readiness.snapshot(),
                    agentInstanceId.toString(),
                    BATCH_ID,
                    READY_EVIDENCE_GENERATION,
                    evidence
            ));
        }
    }

    private static Fixture activeFixture(BootstrapLifecycleState lifecycle, BootstrapReadinessJournal journal) {
        PlatformExtensionReadiness readiness = readyReadiness();
        return activeFixture(lifecycle, journal, readiness, completeEvidence(readiness));
    }

    private static Fixture activeFixture(BootstrapLifecycleState lifecycle,
                                         BootstrapReadinessJournal journal,
                                         PlatformExtensionReadiness readiness,
                                         ExtensionRuntimeEvidence evidence) {
        BootstrapTerminalTransitions transitions = new BootstrapTerminalTransitions(lifecycle, journal);
        AutoconfigureSession session = mock(AutoconfigureSession.class);
        when(session.evidence()).thenReturn(evidence);
        lifecycle.beginPrepare();
        lifecycle.completePrepare(session);
        lifecycle.beginActivation();
        lifecycle.attachResources(new BootstrapResources());
        lifecycle.commitActive();
        return new Fixture(lifecycle, journal, transitions, readiness, evidence);
    }

    private static FailureAdmission recordActivationFailure(Fixture fixture, Throwable activationFailure) {
        return fixture.transitions().terminalize(
                FailureSite.activation("readiness-publication"),
                ACTIVATION_FAILED,
                activationFailure,
                BATCH_ID,
                fixture.evidence()
        );
    }
    private static ExtensionRuntimeEvidence completeEvidence(PlatformExtensionReadiness readiness) {
        return new ExtensionRuntimeEvidence(
                "0123456789abcdef",
                "2.33.1",
                readiness.snapshot().extensionVersion(),
                "extension-loader",
                "sdk-loader"
        );
    }

    private static PlatformExtensionReadiness readyReadiness() {
        PlatformExtensionReadiness readiness = PlatformExtensionReadiness.forCurrentExtension();
        for (PlatformExtensionCapability capability : PlatformExtensionCapability.values()) {
            readiness.markInstalled(capability);
        }

        readiness.completeInitialization();
        return readiness;
    }

    private record CloseResult(BootstrapLifecycleState.CloseAcquisition acquisition, Throwable outcome) {
    }

    private record Fixture(BootstrapLifecycleState lifecycle,
                           BootstrapReadinessJournal journal,
                           BootstrapTerminalTransitions transitions,
                           PlatformExtensionReadiness readiness,
                           ExtensionRuntimeEvidence evidence) {
    }

    private static final class FailingOutputStream extends OutputStream {

        private final Throwable failure;
        private int writeAttempts;

        private FailingOutputStream(Throwable failure) {
            this.failure = failure;
        }

        @Override
        public void write(int value) throws IOException {
            failWrite();
        }

        @Override
        public void write(@Nonnull byte[] payload) throws IOException {
            failWrite();
        }

        private void failWrite() throws IOException {
            writeAttempts++;
            if (failure instanceof IOException ioFailure) {
                throw ioFailure;
            }
            if (failure instanceof Error fatalFailure) {
                throw fatalFailure;
            }

            throw new IllegalStateException("Unsupported log delivery failure type", failure);
        }

        private int writeAttempts() {
            return writeAttempts;
        }
    }
}
