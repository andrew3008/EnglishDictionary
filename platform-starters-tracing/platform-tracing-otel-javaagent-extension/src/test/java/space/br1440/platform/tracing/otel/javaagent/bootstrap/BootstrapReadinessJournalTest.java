package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static space.br1440.platform.tracing.otel.javaagent.readiness.ReadinessSnapshotAssertions.failureOf;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionReadinessWireSnapshot;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionRuntimeEvidence;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionCapability;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionLifecycle;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadiness;

class BootstrapReadinessJournalTest {

    private static final AgentInstanceId AGENT_INSTANCE_ID =
            AgentInstanceId.parse("00000000-0000-0000-0000-000000000001");
    private static final AgentInstanceId FOREIGN_AGENT_INSTANCE_ID =
            AgentInstanceId.parse("00000000-0000-0000-0000-000000000002");

    private final Object monitor = new Object();

    @Test
    void failedPublicationRequiresLifecycleMonitor() {
        // given
        PlatformExtensionReadiness readiness = PlatformExtensionReadiness.forCurrentExtension();
        BootstrapReadinessJournal journal = new BootstrapReadinessJournal(monitor, readiness, AGENT_INSTANCE_ID);

        // when
        // then
        assertThatThrownBy(() -> journal.publishFailed(null, null, null))
                .isInstanceOf(IllegalMonitorStateException.class)
                .hasMessage("FAILED readiness publication requires the bootstrap lifecycle monitor");
        assertThat(readiness.snapshot().lifecycle()).isEqualTo(PlatformExtensionLifecycle.INITIALIZING);
        assertThat(journal.current().lifecycle()).isEqualTo(PlatformExtensionLifecycle.INITIALIZING.name());
    }

    @Test
    void repeatedFailurePairPreservesFirstFailedGeneration() {
        // given
        PlatformExtensionReadiness readiness = PlatformExtensionReadiness.forCurrentExtension();
        BootstrapReadinessJournal journal = new BootstrapReadinessJournal(monitor, readiness, AGENT_INSTANCE_ID);
        BootstrapFailure failure = failure("PRIMARY_FAILURE");

        // when
        ExtensionReadinessWireSnapshot first;
        ExtensionReadinessWireSnapshot repeated;
        synchronized (monitor) {
            journal.publishFailed(failure, "batch-first", evidence(readiness, "first"));
            first = journal.current();
            journal.publishFailed(failure, "batch-second", evidence(readiness, "second"));
            repeated = journal.current();
        }

        // then
        assertThat(repeated).isSameAs(first);
        assertThat(repeated.batchId()).isEqualTo("batch-first");
        assertThat(repeated.evidence()).isEqualTo(evidence(readiness, "first"));
        assertThat(repeated.evidenceGeneration()).isEqualTo(1L);
    }

    @Test
    void escalationPreservesMetadataFromFirstFailedSnapshot() {
        // given
        PlatformExtensionReadiness readiness = PlatformExtensionReadiness.forCurrentExtension();
        BootstrapReadinessJournal journal =
                new BootstrapReadinessJournal(monitor, readiness, AGENT_INSTANCE_ID);
        BootstrapFailure primary = failure("PRIMARY_FAILURE");
        BootstrapFailure escalated = failure("FAILED_ROLLBACK_INCOMPLETE");

        // when
        ExtensionReadinessWireSnapshot first;
        ExtensionReadinessWireSnapshot terminal;
        synchronized (monitor) {
            journal.publishFailed(primary, "batch-first", evidence(readiness, "first"));
            first = journal.current();
            journal.publishFailed(escalated, "batch-second", evidence(readiness, "second"));
            terminal = journal.current();
        }

        // then
        assertThat(terminal).isNotSameAs(first);
        assertThat(terminal.primaryFailureCode()).isEqualTo("PRIMARY_FAILURE");
        assertThat(terminal.failureCode()).isEqualTo("FAILED_ROLLBACK_INCOMPLETE");
        assertThat(terminal.batchId()).isEqualTo("batch-first");
        assertThat(terminal.evidence()).isEqualTo(evidence(readiness, "first"));
        assertThat(terminal.evidenceGeneration()).isEqualTo(2L);
        assertThat(failureOf(readiness.snapshot()).primaryCode()).isEqualTo("PRIMARY_FAILURE");
        assertThat(failureOf(readiness.snapshot()).effectiveCode()).isEqualTo("FAILED_ROLLBACK_INCOMPLETE");
    }

    @Test
    void failureAfterForeignReadyAdvancesObservedEvidenceGeneration() {
        // given
        PlatformExtensionReadiness readiness = PlatformExtensionReadiness.forCurrentExtension();
        for (PlatformExtensionCapability capability : PlatformExtensionCapability.values()) {
            readiness.markInstalled(capability);
        }

        BootstrapReadinessJournal journal = new BootstrapReadinessJournal(monitor, readiness, AGENT_INSTANCE_ID);
        journal.publishReady(ExtensionReadinessWireSnapshot.ready(
                readiness.completeInitialization(),
                FOREIGN_AGENT_INSTANCE_ID.toString(),
                "foreign-batch",
                7L,
                evidence(readiness, "foreign")
        ));

        // when
        ExtensionReadinessWireSnapshot failed;
        synchronized (monitor) {
            journal.publishFailed(failure("PRIMARY_FAILURE"), "local-batch", evidence(readiness, "local"));
            failed = journal.current();
        }

        // then
        assertThat(failed.batchId()).isEqualTo("local-batch");
        assertThat(failed.evidenceGeneration()).isEqualTo(8L);
    }

    @Test
    void failureWithoutBatchHasNoEvidenceGeneration() {
        // given
        PlatformExtensionReadiness readiness = PlatformExtensionReadiness.forCurrentExtension();
        BootstrapReadinessJournal journal = new BootstrapReadinessJournal(monitor, readiness, AGENT_INSTANCE_ID);

        // when
        ExtensionReadinessWireSnapshot failed;
        synchronized (monitor) {
            journal.publishFailed(failure("PRIMARY_FAILURE"), "", evidence(readiness, "first"));
            failed = journal.current();
        }

        // then
        assertThat(failed.lifecycle()).isEqualTo(PlatformExtensionLifecycle.FAILED.name());
        assertThat(failed.batchId()).isEmpty();
        assertThat(failed.evidenceGeneration()).isZero();
    }

    @Test
    void failedPublicationAlignsUnavailableEvidenceWithReadinessVersion() {
        // given
        PlatformExtensionReadiness readiness = PlatformExtensionReadiness.forCurrentExtension();
        BootstrapReadinessJournal journal = new BootstrapReadinessJournal(monitor, readiness, AGENT_INSTANCE_ID);

        // when
        synchronized (monitor) {
            journal.publishFailed(
                    failure("PRIMARY_FAILURE"),
                    "",
                    ExtensionRuntimeEvidence.unavailable("unknown-version")
            );
        }

        // then
        assertThat(journal.current().evidence().extensionVersion())
                .isEqualTo(readiness.snapshot().extensionVersion());
        assertThat(journal.current().evidence().complete()).isFalse();
    }

    private static BootstrapFailure failure(String effectiveCode) {
        return new BootstrapFailure("PRIMARY_FAILURE", effectiveCode, "test", new IllegalStateException("test failure"));
    }

    private static ExtensionRuntimeEvidence evidence(PlatformExtensionReadiness readiness, String loaderSuffix) {
        return new ExtensionRuntimeEvidence(
                "a".repeat(64),
                "2.30.0",
                readiness.snapshot().extensionVersion(),
                "extension-loader@" + loaderSuffix,
                "sdk-loader@" + loaderSuffix
        );
    }
}
