package space.br1440.platform.tracing.otel.javaagent.jmx.readiness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import javax.management.openmbean.CompositeData;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionCapability;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionLifecycle;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadiness;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadinessSnapshot;

class ExtensionReadinessWireSnapshotTest {

    @Test
    void initializingRejectsAReadySnapshot() {
        // given
        PlatformExtensionReadinessSnapshot ready = completeReadiness().snapshot();

        // when
        Throwable failure = catchThrowable(() ->
                ExtensionReadinessWireSnapshot.initializing(ready, "epoch-1"));

        // then
        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("underlying lifecycle=READY");
    }

    @Test
    void readyRejectsAnInitializingSnapshot() {
        // given
        PlatformExtensionReadinessSnapshot initializing = new PlatformExtensionReadiness().snapshot();

        // when
        Throwable failure = catchThrowable(() -> ExtensionReadinessWireSnapshot.ready(
                initializing,
                "epoch-1",
                "batch-1",
                1L,
                evidence(initializing.extensionVersion())
        ));

        // then
        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("underlying lifecycle=INITIALIZING");
    }

    @Test
    void readyRejectsAFailedSnapshot() {
        // given
        PlatformExtensionReadiness readiness = new PlatformExtensionReadiness();
        PlatformExtensionReadinessSnapshot failed = readiness.fail("SOME_FAILURE");

        // when
        Throwable failure = catchThrowable(() -> ExtensionReadinessWireSnapshot.ready(
                failed,
                "epoch-1",
                "batch-1",
                1L,
                evidence(failed.extensionVersion())
        ));

        // then
        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("underlying lifecycle=FAILED");
    }

    @Test
    void failedRejectsAnInitializingSnapshot() {
        // given
        PlatformExtensionReadinessSnapshot initializing = new PlatformExtensionReadiness().snapshot();

        // when
        Throwable failure = catchThrowable(() -> ExtensionReadinessWireSnapshot.failed(
                initializing,
                "epoch-1",
                "batch-1",
                1L,
                evidence(initializing.extensionVersion())
        ));

        // then
        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("underlying lifecycle=INITIALIZING");
    }

    @Test
    void initializingUsesOneCapturedCapabilityGeneration() {
        // given
        PlatformExtensionReadiness readiness = new PlatformExtensionReadiness();
        readiness.markInstalled(PlatformExtensionCapability.CONFIGURATION_LOADED);
        PlatformExtensionReadinessSnapshot captured = readiness.snapshot();

        for (PlatformExtensionCapability capability : PlatformExtensionCapability.values()) {
            readiness.markInstalled(capability);
        }
        readiness.completeInitialization();
        assertThat(readiness.snapshot().lifecycle()).isEqualTo(PlatformExtensionLifecycle.READY);

        // when
        ExtensionReadinessWireSnapshot snapshot =
                ExtensionReadinessWireSnapshot.initializing(captured, "epoch-1");

        // then
        assertThat(snapshot.capabilities()).containsExactly("CONFIGURATION_LOADED");
        assertThat(snapshot.missingRequired())
                .doesNotContain("CONFIGURATION_LOADED")
                .contains("SANITIZER_INSTALLED", "EXPORT_PATH_PROTECTED");
        assertThat(snapshot.failureCode()).isEmpty();
    }

    @Test
    void failedUsesTheFailureCodeFromTheCapturedGeneration() {
        // given
        PlatformExtensionReadiness readiness = new PlatformExtensionReadiness();
        PlatformExtensionReadinessSnapshot captured = readiness.fail("REMOTE_JMX_EVIDENCE_UNKNOWN");
        readiness.recordTerminalCleanupFailure("FAILED_ROLLBACK_INCOMPLETE");

        // when
        ExtensionReadinessWireSnapshot snapshot = ExtensionReadinessWireSnapshot.failed(
                captured,
                "epoch-1",
                "batch-1",
                1L,
                evidence(captured.extensionVersion())
        );

        // then
        assertThat(snapshot.lifecycle()).isEqualTo("FAILED");
        assertThat(snapshot.failureCode()).isEqualTo("REMOTE_JMX_EVIDENCE_UNKNOWN");
        assertThat(snapshot.readinessSnapshot().get(
                JmxWireContract.FIELD_READINESS_SNAPSHOT_FAILURE_CODE))
                .isEqualTo("REMOTE_JMX_EVIDENCE_UNKNOWN");
        assertThat(readiness.snapshot().failureCode()).isEqualTo("FAILED_ROLLBACK_INCOMPLETE");
    }

    @Test
    void generatedManifestDefinesRequiredCapabilities() {
        // given
        PlatformExtensionReadiness readiness = new PlatformExtensionReadiness();
        ExtensionReadinessWireSnapshot initial =
                ExtensionReadinessWireSnapshot.initializing(readiness.snapshot(), "");

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
        PlatformExtensionReadinessSnapshot captured = readiness.snapshot();
        readiness.fail("LATE_FAILURE");

        // when
        ExtensionReadinessWireSnapshot snapshot = ExtensionReadinessWireSnapshot.ready(
                captured,
                "epoch-1",
                "batch-1",
                1L,
                evidence(captured.extensionVersion())
        );
        CompositeData wire = snapshot.readinessSnapshot();

        // then
        assertThat(readiness.snapshot().lifecycle()).isEqualTo(PlatformExtensionLifecycle.FAILED);
        assertThat(snapshot.lifecycle()).isEqualTo("READY");
        assertThat(snapshot.missingRequired()).isEmpty();
        assertThat(wire.getCompositeType()).isEqualTo(JmxOpenTypes.READINESS_SNAPSHOT);
        assertThat(wire.get(JmxWireContract.FIELD_READINESS_SNAPSHOT_CONTRACT_ID))
                .isEqualTo(JmxWireContract.SCHEMA_ID);
        assertThat(wire.get(JmxWireContract.FIELD_READINESS_SNAPSHOT_SCHEMA_DIGEST))
                .isEqualTo(JmxWireContract.SCHEMA_DIGEST_SHA256);
        assertThat(wire.get(JmxWireContract.FIELD_READINESS_SNAPSHOT_RUNTIME_EPOCH))
                .isEqualTo("epoch-1");
        assertThat(wire.get(JmxWireContract.FIELD_READINESS_SNAPSHOT_EVIDENCE_GENERATION))
                .isEqualTo(1L);
        assertThat(wire.get(JmxWireContract.FIELD_READINESS_SNAPSHOT_BATCH_ID))
                .isEqualTo("batch-1");
        assertThat((String[]) wire.get(JmxWireContract.FIELD_READINESS_SNAPSHOT_MISSING_REQUIRED))
                .isEmpty();
    }

    private static PlatformExtensionReadiness completeReadiness() {
        PlatformExtensionReadiness readiness = new PlatformExtensionReadiness();
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
