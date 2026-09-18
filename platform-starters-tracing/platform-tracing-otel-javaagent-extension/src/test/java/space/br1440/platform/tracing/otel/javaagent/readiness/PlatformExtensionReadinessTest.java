package space.br1440.platform.tracing.otel.javaagent.readiness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PlatformExtensionReadinessTest {

    @Test
    void readyRequiresEverySecureProfileCapability() {
        // given
        PlatformExtensionReadiness readiness = new PlatformExtensionReadiness();

        for (PlatformExtensionCapability capability : PlatformExtensionCapability.values()) {
            if (capability != PlatformExtensionCapability.EXPORT_PATH_PROTECTED) {
                readiness.markInstalled(capability);
            }
        }

        // then
        assertThat(readiness.lifecycle()).isEqualTo(PlatformExtensionLifecycle.INITIALIZING);

        // when
        readiness.markInstalled(PlatformExtensionCapability.EXPORT_PATH_PROTECTED);

        // then
        assertThat(readiness.lifecycle()).isEqualTo(PlatformExtensionLifecycle.READY);
        assertThat(readiness.capabilities()).containsExactlyInAnyOrder(
                "CONFIGURATION_LOADED",
                "PLATFORM_SAMPLER_INSTALLED",
                "REQUIRED_SPAN_PROCESSORS_INSTALLED",
                "SANITIZER_INSTALLED",
                "PROPAGATION_HOOKS_INSTALLED",
                "SAFE_EXPORTER_INSTALLED",
                "EXPORT_PATH_PROTECTED");
    }

    @Test
    void failedStateIsStickyAndMessageIsSafeAndBounded() {
        // given
        PlatformExtensionReadiness readiness = new PlatformExtensionReadiness();

        // when
        readiness.fail("SANITIZER_INIT_FAILED", new IllegalStateException("rule load failed"));
        readiness.markInstalled(PlatformExtensionCapability.SANITIZER_INSTALLED);

        // then
        assertThat(readiness.lifecycle()).isEqualTo(PlatformExtensionLifecycle.FAILED);
        assertThat(readiness.failureCode()).isEqualTo("SANITIZER_INIT_FAILED");
        assertThat(readiness.failureMessage()).isEqualTo("IllegalStateException: rule load failed");
        assertThat(readiness.has(PlatformExtensionCapability.SANITIZER_INSTALLED)).isFalse();
    }

    @Test
    void failIsWriteOnceAndPreservesTheOriginalRootCause() {
        // given
        PlatformExtensionReadiness readiness = new PlatformExtensionReadiness();

        // when
        readiness.fail("FIRST_FAILURE", new IllegalStateException("first cause"));
        readiness.fail("SECOND_FAILURE", new IllegalArgumentException("second cause"));

        // then
        assertThat(readiness.lifecycle()).isEqualTo(PlatformExtensionLifecycle.FAILED);
        assertThat(readiness.failureCode()).isEqualTo("FIRST_FAILURE");
        assertThat(readiness.failureMessage()).isEqualTo("IllegalStateException: first cause");
    }

    @Test
    void terminalCleanupFailureOverridesPreliminaryTransportStaleFailure() {
        // given
        PlatformExtensionReadiness readiness = new PlatformExtensionReadiness();
        readiness.fail(
                "JMX_TRANSPORT_STALE",
                new IllegalStateException("JMX runtime controls are being deregistered")
        );

        // when
        readiness.failTerminalCleanup(
                "FAILED_ROLLBACK_INCOMPLETE",
                new NoClassDefFoundError("simulated cleanup linkage failure")
        );

        // then
        assertThat(readiness.lifecycle()).isEqualTo(PlatformExtensionLifecycle.FAILED);
        assertThat(readiness.failureCode()).isEqualTo("FAILED_ROLLBACK_INCOMPLETE");
        assertThat(readiness.failureMessage())
                .isEqualTo("NoClassDefFoundError: simulated cleanup linkage failure");
    }

    @Test
    void markInstalledAndHasRejectNullCapability() {
        // given
        PlatformExtensionReadiness readiness = new PlatformExtensionReadiness();

        // when/then
        assertThatThrownBy(() -> readiness.markInstalled(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> readiness.has(null)).isInstanceOf(NullPointerException.class);
    }

    /**
     * ADR-scrubbing-hybrid-processor-export-boundary.md BLOCKER 3: {@code requireReady()} is the
     * defensive completeness gate that {@code BootstrapCoordinator.activate()} must call before
     * constructing a READY wire snapshot — it must reject any incomplete capability set, even one
     * capability short.
     */
    @Test
    void requireReadyThrowsWhileAnySingleCapabilityIsMissing() {
        // given
        PlatformExtensionReadiness readiness = new PlatformExtensionReadiness();

        for (PlatformExtensionCapability capability : PlatformExtensionCapability.values()) {
            if (capability != PlatformExtensionCapability.EXPORT_PATH_PROTECTED) {
                readiness.markInstalled(capability);
            }
        }

        // when/then
        assertThatThrownBy(readiness::requireReady)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EXPORT_PATH_PROTECTED");

        // when
        readiness.markInstalled(PlatformExtensionCapability.EXPORT_PATH_PROTECTED);

        // then
        assertThatCode(readiness::requireReady).doesNotThrowAnyException();
    }

    @Test
    void requireReadyThrowsAfterFailEvenIfCapabilitiesWerePreviouslyComplete() {
        // given
        PlatformExtensionReadiness readiness = new PlatformExtensionReadiness();
        for (PlatformExtensionCapability capability : PlatformExtensionCapability.values()) {
            readiness.markInstalled(capability);
        }
        assertThat(readiness.lifecycle()).isEqualTo(PlatformExtensionLifecycle.READY);

        // when
        readiness.fail("LATE_FAILURE", new IllegalStateException("late failure"));

        // then
        assertThatThrownBy(readiness::requireReady).isInstanceOf(IllegalStateException.class);
    }
}
