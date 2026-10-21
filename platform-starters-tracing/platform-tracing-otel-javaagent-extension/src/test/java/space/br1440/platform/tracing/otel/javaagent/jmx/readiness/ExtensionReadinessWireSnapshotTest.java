package space.br1440.platform.tracing.otel.javaagent.jmx.readiness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static space.br1440.platform.tracing.otel.javaagent.readiness.ReadinessSnapshotAssertions.failureOf;

import javax.management.openmbean.CompositeData;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionCapability;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionLifecycle;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadiness;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadinessSnapshot;

class ExtensionReadinessWireSnapshotTest {

    private static final String AGENT_INSTANCE_ID = "00000000-0000-0000-0000-000000000001";

    @Test
    void initializingUsesOneCapturedCapabilityGeneration() {
        // given
        PlatformExtensionReadiness readiness = PlatformExtensionReadiness.forCurrentExtension();
        PlatformExtensionReadinessSnapshot.Initializing captured = (PlatformExtensionReadinessSnapshot.Initializing)
                readiness.markInstalled(PlatformExtensionCapability.CONFIGURATION_LOADED);

        for (PlatformExtensionCapability capability : PlatformExtensionCapability.values()) {
            readiness.markInstalled(capability);
        }
        readiness.completeInitialization();
        assertThat(readiness.snapshot().lifecycle()).isEqualTo(PlatformExtensionLifecycle.READY);

        // when
        ExtensionReadinessWireSnapshot snapshot =
                ExtensionReadinessWireSnapshot.initializing(captured, AGENT_INSTANCE_ID);

        // then
        assertThat(snapshot.capabilities()).containsExactly("CONFIGURATION_LOADED");
        assertThat(snapshot.missingRequired())
                .doesNotContain("CONFIGURATION_LOADED")
                .contains("SANITIZER_INSTALLED", "EXPORT_PATH_PROTECTED");
        assertThat(snapshot.failureCode()).isEmpty();
        assertThat(snapshot.primaryFailureCode()).isEmpty();
    }

    @Test
    void failedUsesTheFailureCodeFromTheCapturedGeneration() {
        // given
        PlatformExtensionReadiness readiness = PlatformExtensionReadiness.forCurrentExtension();
        PlatformExtensionReadinessSnapshot.Failed captured = readiness.fail(
                "REMOTE_JMX_EVIDENCE_UNKNOWN",
                "REMOTE_JMX_EVIDENCE_UNKNOWN");
        readiness.fail("REMOTE_JMX_EVIDENCE_UNKNOWN", "FAILED_ROLLBACK_INCOMPLETE");

        // when
        ExtensionReadinessWireSnapshot snapshot = ExtensionReadinessWireSnapshot.failed(
                captured,
                AGENT_INSTANCE_ID,
                "batch-1",
                1L,
                evidence(captured.extensionVersion())
        );

        // then
        assertThat(snapshot.lifecycle()).isEqualTo("FAILED");
        assertThat(snapshot.failureCode()).isEqualTo("REMOTE_JMX_EVIDENCE_UNKNOWN");
        assertThat(snapshot.primaryFailureCode()).isEqualTo("REMOTE_JMX_EVIDENCE_UNKNOWN");
        assertThat(snapshot.readinessSnapshot().get(
                JmxWireContract.FIELD_READINESS_SNAPSHOT_FAILURE_CODE))
                .isEqualTo("REMOTE_JMX_EVIDENCE_UNKNOWN");
        assertThat(snapshot.readinessSnapshot().get(
                JmxWireContract.FIELD_READINESS_SNAPSHOT_PRIMARY_FAILURE_CODE))
                .isEqualTo("REMOTE_JMX_EVIDENCE_UNKNOWN");
        assertThat(failureOf(readiness.snapshot()).effectiveCode()).isEqualTo("FAILED_ROLLBACK_INCOMPLETE");
        assertThat(failureOf(readiness.snapshot()).primaryCode()).isEqualTo("REMOTE_JMX_EVIDENCE_UNKNOWN");
    }

    @Test
    void failedPublishesPrimaryAndEscalatedEffectiveFailureCodes() {
        // given
        PlatformExtensionReadiness readiness = PlatformExtensionReadiness.forCurrentExtension();
        PlatformExtensionReadinessSnapshot.Failed failed = readiness.fail(
                "ACTIVATION_FAILED",
                "FAILED_ROLLBACK_INCOMPLETE");

        // when
        ExtensionReadinessWireSnapshot snapshot = ExtensionReadinessWireSnapshot.failed(
                failed,
                AGENT_INSTANCE_ID,
                "batch-1",
                1L,
                evidence(failed.extensionVersion())
        );

        // then
        assertThat(snapshot.failureCode()).isEqualTo("FAILED_ROLLBACK_INCOMPLETE");
        assertThat(snapshot.primaryFailureCode()).isEqualTo("ACTIVATION_FAILED");
        assertThat(snapshot.readinessSnapshot().get(
                JmxWireContract.FIELD_READINESS_SNAPSHOT_FAILURE_CODE))
                .isEqualTo("FAILED_ROLLBACK_INCOMPLETE");
        assertThat(snapshot.readinessSnapshot().get(
                JmxWireContract.FIELD_READINESS_SNAPSHOT_PRIMARY_FAILURE_CODE))
                .isEqualTo("ACTIVATION_FAILED");
    }

    @Test
    void generatedManifestDefinesRequiredCapabilities() {
        // given
        PlatformExtensionReadiness readiness = PlatformExtensionReadiness.forCurrentExtension();
        ExtensionReadinessWireSnapshot initial = ExtensionReadinessWireSnapshot.initializing();

        // when
        for (String capabilityName : JmxWireContract.REQUIRED_CAPABILITIES) {
            readiness.markInstalled(PlatformExtensionCapability.valueOf(capabilityName));
        }
        readiness.completeInitialization();

        // then
        assertThat(initial.missingRequired())
                .containsExactlyInAnyOrderElementsOf(
                        JmxWireContract.REQUIRED_CAPABILITIES);
        assertThat(readiness.snapshot().lifecycle()).isEqualTo(PlatformExtensionLifecycle.READY);
    }

    @Test
    void readyPublishesOneCapturedCoherentCompositeDataEnvelope() {
        // given
        PlatformExtensionReadiness readiness = completeReadiness();
        PlatformExtensionReadinessSnapshot.Ready captured = readiness.completeInitialization();
        readiness.fail("LATE_FAILURE", "LATE_FAILURE");

        // when
        ExtensionReadinessWireSnapshot snapshot = ExtensionReadinessWireSnapshot.ready(
                captured,
                AGENT_INSTANCE_ID,
                "batch-1",
                1L,
                evidence(captured.extensionVersion())
        );
        CompositeData wire = snapshot.readinessSnapshot();

        // then
        assertThat(readiness.snapshot().lifecycle()).isEqualTo(PlatformExtensionLifecycle.FAILED);
        assertThat(snapshot.lifecycle()).isEqualTo("READY");
        assertThat(snapshot.missingRequired()).isEmpty();
        assertThat(snapshot.failureCode()).isEmpty();
        assertThat(snapshot.primaryFailureCode()).isEmpty();
        assertThat(wire.getCompositeType()).isEqualTo(JmxOpenTypes.READINESS_SNAPSHOT);
        assertThat(wire.get(JmxWireContract.FIELD_READINESS_SNAPSHOT_CONTRACT_ID))
                .isEqualTo(JmxWireContract.SCHEMA_ID);
        assertThat(wire.get(JmxWireContract.FIELD_READINESS_SNAPSHOT_SCHEMA_DIGEST))
                .isEqualTo(JmxWireContract.SCHEMA_DIGEST_SHA256);
        assertThat(wire.get(JmxWireContract.FIELD_READINESS_SNAPSHOT_AGENT_INSTANCE_ID))
                .isEqualTo(AGENT_INSTANCE_ID);
        assertThat(wire.get(JmxWireContract.FIELD_READINESS_SNAPSHOT_EVIDENCE_GENERATION))
                .isEqualTo(1L);
        assertThat(wire.get(JmxWireContract.FIELD_READINESS_SNAPSHOT_BATCH_ID))
                .isEqualTo("batch-1");
        assertThat((String[]) wire.get(JmxWireContract.FIELD_READINESS_SNAPSHOT_MISSING_REQUIRED))
                .isEmpty();
    }

    @Test
    void failedRejectsBlankAgentInstanceId() {
        // given
        PlatformExtensionReadiness readiness = PlatformExtensionReadiness.forCurrentExtension();
        PlatformExtensionReadinessSnapshot.Failed failed = readiness.fail("SOME_FAILURE", "SOME_FAILURE");

        // when
        Throwable failure = catchThrowable(() -> ExtensionReadinessWireSnapshot.failed(
                failed,
                "",
                "",
                0L,
                ExtensionRuntimeEvidence.unavailable(failed.extensionVersion())
        ));

        // then
        assertThat(failure)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("agentInstanceId must not be blank");
    }

    private static PlatformExtensionReadiness completeReadiness() {
        PlatformExtensionReadiness readiness = PlatformExtensionReadiness.forCurrentExtension();
        for (PlatformExtensionCapability capability : PlatformExtensionCapability.values()) {
            readiness.markInstalled(capability);
        }
        readiness.completeInitialization();
        return readiness;
    }

    private static ExtensionRuntimeEvidence evidence(String extensionVersion) {
        return new ExtensionRuntimeEvidence(
                "a".repeat(64),
                "2.30.0",
                extensionVersion,
                "test-extension-loader@1",
                "test-sdk-loader@2"
        );
    }
}
