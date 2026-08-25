package space.br1440.platform.tracing.otel.javaagent.jmx.readiness;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionCapability;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadiness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-scrubbing-hybrid-processor-export-boundary.md BLOCKER 3: {@link ExtensionReadinessWireSnapshot#ready}
 * must never be constructable while the underlying {@link PlatformExtensionReadiness} lifecycle disagrees,
 * even if a caller forgets to invoke {@code requireReady()} first.
 */
class ExtensionReadinessWireSnapshotTest {

    @Test
    void readyRejectsAReadinessThatIsStillInitializing() {
        PlatformExtensionReadiness readiness = new PlatformExtensionReadiness();

        assertThatThrownBy(() -> ExtensionReadinessWireSnapshot.ready(readiness))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INITIALIZING");
    }

    @Test
    void readyRejectsAReadinessThatHasFailed() {
        PlatformExtensionReadiness readiness = new PlatformExtensionReadiness();
        readiness.fail("SOME_FAILURE", new IllegalStateException("boom"));

        assertThatThrownBy(() -> ExtensionReadinessWireSnapshot.ready(readiness))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FAILED");
    }

    @Test
    void readySucceedsOnceEveryCapabilityIsInstalled() {
        PlatformExtensionReadiness readiness = new PlatformExtensionReadiness();
        for (PlatformExtensionCapability capability : PlatformExtensionCapability.values()) {
            readiness.markInstalled(capability);
        }

        ExtensionReadinessWireSnapshot snapshot = ExtensionReadinessWireSnapshot.ready(readiness);

        assertThat(snapshot.lifecycle()).isEqualTo("READY");
    }
}
