package space.br1440.platform.tracing.otel.javaagent.jmx.readiness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;

import javax.management.openmbean.CompositeData;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.GeneratedJmxOpenTypes;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.GeneratedJmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionCapability;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadiness;

class ExtensionReadinessWireSnapshotTest {

    @Test
    void readyRejectsAReadinessThatIsStillInitializing() {
        // given
        PlatformExtensionReadiness readiness = new PlatformExtensionReadiness();

        // when
        Throwable failure = catchThrowable(() -> ExtensionReadinessWireSnapshot.ready(
                readiness, "epoch-1", "batch-1", 1L, evidence(readiness.extensionVersion())));

        // then
        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INITIALIZING");
    }

    @Test
    void readyRejectsAReadinessThatHasFailed() {
        // given
        PlatformExtensionReadiness readiness = new PlatformExtensionReadiness();
        readiness.fail("SOME_FAILURE", new IllegalStateException("boom"));

        // when
        Throwable failure = catchThrowable(() -> ExtensionReadinessWireSnapshot.ready(
                readiness, "epoch-1", "batch-1", 1L, evidence(readiness.extensionVersion())));

        // then
        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FAILED");
    }

    @Test
    void initializingComputesMissingRequiredIndependentlyFromLifecycle() {
        // given
        PlatformExtensionReadiness readiness = new PlatformExtensionReadiness();
        readiness.markInstalled(PlatformExtensionCapability.CONFIGURATION_LOADED);

        // when
        ExtensionReadinessWireSnapshot snapshot =
                ExtensionReadinessWireSnapshot.initializing(readiness, "epoch-1");

        // then
        assertThat(snapshot.capabilities()).containsExactly("CONFIGURATION_LOADED");
        assertThat(snapshot.missingRequired())
                .doesNotContain("CONFIGURATION_LOADED")
                .contains("SANITIZER_INSTALLED", "EXPORT_PATH_PROTECTED");
        assertThat(snapshot.failureCode()).isEmpty();
    }

    @Test
    void failedRequiresStrictBoundedMachineReadableFailureCode() {
        // given
        PlatformExtensionReadiness readiness = new PlatformExtensionReadiness();
        ExtensionRuntimeEvidence runtimeEvidence = evidence(readiness.extensionVersion());

        // when
        Throwable nullCode = catchThrowable(() -> ExtensionReadinessWireSnapshot.failed(
                readiness, "epoch-1", "batch-1", 1L, null, runtimeEvidence));
        Throwable blankCode = catchThrowable(() -> ExtensionReadinessWireSnapshot.failed(
                readiness, "epoch-1", "batch-1", 1L, " ", runtimeEvidence));
        Throwable malformedCode = catchThrowable(() -> ExtensionReadinessWireSnapshot.failed(
                readiness, "epoch-1", "batch-1", 1L, "lowercase-code", runtimeEvidence));
        Throwable injectionCode = catchThrowable(() -> ExtensionReadinessWireSnapshot.failed(
                readiness, "epoch-1", "batch-1", 1L, "FAILURE\r\nFORGED", runtimeEvidence));
        Throwable oversizedCode = catchThrowable(() -> ExtensionReadinessWireSnapshot.failed(
                readiness, "epoch-1", "batch-1", 1L, "A".repeat(129), runtimeEvidence));

        // then
        assertThat(List.of(nullCode, blankCode, malformedCode, injectionCode, oversizedCode))
                .allSatisfy(failure -> assertThat(failure)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("FAILED failureCode must match [A-Z][A-Z0-9_]{0,127}"));
        assertThat(injectionCode.getMessage()).doesNotContain("FORGED", "\r", "\n");
    }

    @Test
    void failedPublishesValidMachineReadableFailureCode() {
        // given
        PlatformExtensionReadiness readiness = new PlatformExtensionReadiness();
        String failureCode = "REMOTE_JMX_EVIDENCE_UNKNOWN";
        readiness.fail(failureCode, new IllegalStateException("evidence unavailable"));

        // when
        ExtensionReadinessWireSnapshot snapshot = ExtensionReadinessWireSnapshot.failed(
                readiness,
                "epoch-1",
                "batch-1",
                1L,
                failureCode,
                evidence(readiness.extensionVersion()));

        // then
        assertThat(snapshot.lifecycle()).isEqualTo("FAILED");
        assertThat(snapshot.failureCode()).isEqualTo(failureCode);
        assertThat(snapshot.readinessSnapshot().get(
                GeneratedJmxWireContract.FIELD_READINESS_SNAPSHOT_FAILURE_CODE))
                .isEqualTo(failureCode);
    }

    @Test
    void generatedManifestDefinesRequiredCapabilities() {
        // given
        PlatformExtensionReadiness readiness = new PlatformExtensionReadiness();
        ExtensionReadinessWireSnapshot initial =
                ExtensionReadinessWireSnapshot.initializing(readiness);

        // when
        for (String capabilityName : GeneratedJmxWireContract.REQUIRED_CAPABILITIES) {
            readiness.markInstalled(PlatformExtensionCapability.valueOf(capabilityName));
        }

        // then
        assertThat(initial.missingRequired())
                .containsExactlyInAnyOrderElementsOf(
                        GeneratedJmxWireContract.REQUIRED_CAPABILITIES);
        assertThat(readiness.lifecycle().name()).isEqualTo("READY");
    }

    @Test
    void readyPublishesOneGeneratedCoherentCompositeDataEnvelope() {
        // given
        PlatformExtensionReadiness readiness = completeReadiness();

        // when
        ExtensionReadinessWireSnapshot snapshot = ExtensionReadinessWireSnapshot.ready(
                readiness,
                "epoch-1",
                "batch-1",
                1L,
                evidence(readiness.extensionVersion())
        );
        CompositeData wire = snapshot.readinessSnapshot();

        // then
        assertThat(snapshot.lifecycle()).isEqualTo("READY");
        assertThat(snapshot.missingRequired()).isEmpty();
        assertThat(wire.getCompositeType()).isEqualTo(GeneratedJmxOpenTypes.READINESS_SNAPSHOT);
        assertThat(wire.get(GeneratedJmxWireContract.FIELD_READINESS_SNAPSHOT_CONTRACT_ID))
                .isEqualTo(GeneratedJmxWireContract.SCHEMA_ID);
        assertThat(wire.get(GeneratedJmxWireContract.FIELD_READINESS_SNAPSHOT_SCHEMA_DIGEST))
                .isEqualTo(GeneratedJmxWireContract.SCHEMA_DIGEST_SHA256);
        assertThat(wire.get(GeneratedJmxWireContract.FIELD_READINESS_SNAPSHOT_RUNTIME_EPOCH))
                .isEqualTo("epoch-1");
        assertThat(wire.get(GeneratedJmxWireContract.FIELD_READINESS_SNAPSHOT_EVIDENCE_GENERATION))
                .isEqualTo(1L);
        assertThat(wire.get(GeneratedJmxWireContract.FIELD_READINESS_SNAPSHOT_BATCH_ID))
                .isEqualTo("batch-1");
        assertThat((String[]) wire.get(GeneratedJmxWireContract.FIELD_READINESS_SNAPSHOT_MISSING_REQUIRED))
                .isEmpty();
    }

    private static PlatformExtensionReadiness completeReadiness() {
        PlatformExtensionReadiness readiness = new PlatformExtensionReadiness();
        for (PlatformExtensionCapability capability : PlatformExtensionCapability.values()) {
            readiness.markInstalled(capability);
        }
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
