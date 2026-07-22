package space.br1440.platform.tracing.otel.javaagent.readiness;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PlatformExtensionReadinessTest {

    @Test
    void readyRequiresEverySecureProfileCapability() {
        PlatformExtensionReadiness readiness = new PlatformExtensionReadiness();

        for (PlatformExtensionCapability capability : PlatformExtensionCapability.values()) {
            if (capability != PlatformExtensionCapability.EXPORT_PATH_PROTECTED) {
                readiness.markInstalled(capability);
            }
        }

        assertThat(readiness.lifecycle()).isEqualTo(PlatformExtensionLifecycle.INITIALIZING);

        readiness.markInstalled(PlatformExtensionCapability.EXPORT_PATH_PROTECTED);

        assertThat(readiness.lifecycle()).isEqualTo(PlatformExtensionLifecycle.READY);
        assertThat(readiness.capabilities()).containsExactlyInAnyOrder(readiness.requiredCapabilities());
    }

    @Test
    void failedStateIsStickyAndMessageIsSafeAndBounded() {
        PlatformExtensionReadiness readiness = new PlatformExtensionReadiness();

        readiness.fail("SANITIZER_INIT_FAILED", new IllegalStateException("rule load failed"));
        readiness.markInstalled(PlatformExtensionCapability.SANITIZER_INSTALLED);

        assertThat(readiness.lifecycle()).isEqualTo(PlatformExtensionLifecycle.FAILED);
        assertThat(readiness.failureCode()).isEqualTo("SANITIZER_INIT_FAILED");
        assertThat(readiness.failureMessage()).isEqualTo("IllegalStateException: rule load failed");
        assertThat(readiness.has(PlatformExtensionCapability.SANITIZER_INSTALLED)).isFalse();
    }
}
