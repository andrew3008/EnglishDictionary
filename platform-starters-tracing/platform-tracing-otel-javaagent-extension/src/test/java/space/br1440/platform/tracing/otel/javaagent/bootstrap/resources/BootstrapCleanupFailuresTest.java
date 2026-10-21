package space.br1440.platform.tracing.otel.javaagent.bootstrap.resources;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BootstrapCleanupFailuresTest {

    @Test
    void suppressPreservingPrimaryAddsExactFatalWithoutPromotion() {
        // given
        IllegalStateException primary = new IllegalStateException("Primary failure");
        AssertionError secondary = new AssertionError("Fatal secondary failure");

        // when
        BootstrapCleanupFailures.suppressPreservingPrimary(primary, secondary);

        // then
        assertThat(primary.getSuppressed()).containsExactly(secondary);
        assertThat(secondary.getSuppressed()).isEmpty();
    }

    @Test
    void suppressPreservingPrimaryRejectsSelfDuplicateAndCycle() {
        // given
        IllegalStateException primary = new IllegalStateException("Primary failure");
        UnsupportedOperationException secondary = new UnsupportedOperationException("Secondary failure");
        BootstrapCleanupFailures.suppressPreservingPrimary(primary, secondary);
        IllegalArgumentException cyclicSecondary = new IllegalArgumentException("Cyclic secondary failure", primary);

        // when
        BootstrapCleanupFailures.suppressPreservingPrimary(primary, primary);
        BootstrapCleanupFailures.suppressPreservingPrimary(primary, secondary);
        BootstrapCleanupFailures.suppressPreservingPrimary(primary, cyclicSecondary);

        // then
        assertThat(primary.getSuppressed()).containsExactly(secondary);
        assertThat(cyclicSecondary.getCause()).isSameAs(primary);
    }
}
