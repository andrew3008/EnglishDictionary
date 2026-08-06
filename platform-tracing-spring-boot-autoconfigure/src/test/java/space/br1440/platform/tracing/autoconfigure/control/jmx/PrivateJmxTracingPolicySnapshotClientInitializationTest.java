package space.br1440.platform.tracing.autoconfigure.control.jmx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.control.authority.TracingConfigurationGuardException;

class PrivateJmxTracingPolicySnapshotClientInitializationTest {

    @Test
    void initializeWrapsLocatorFailurePreservingRootCause() {
        MBeanServerSource failingSource = () -> {
            throw new SecurityException("denied by test policy");
        };
        PrivateControlLocator locator = new PrivateControlLocator(failingSource, new PrivateControlMarkerInspector());
        PrivateJmxTracingPolicySnapshotClient client = new PrivateJmxTracingPolicySnapshotClient(locator);

        assertThatThrownBy(client::initialize)
                .isInstanceOf(TracingConfigurationGuardException.class)
                .hasMessage("Cannot initialize snapshot client: private MBeanServer unavailable")
                .satisfies(startupFailure -> {
                    Throwable directCause = startupFailure.getCause();
                    assertThat(directCause).isInstanceOf(IllegalStateException.class);

                    Throwable rootCause = directCause.getCause();
                    assertThat(rootCause)
                            .isInstanceOf(SecurityException.class)
                            .hasMessage("denied by test policy");
                });
    }

    @Test
    void initializeSucceedsWhenExactlyOneCompatibleControlPlaneExists() {
        MBeanServerSource emptySource = List::of;
        PrivateControlLocator locator = new PrivateControlLocator(emptySource, new PrivateControlMarkerInspector());
        PrivateJmxTracingPolicySnapshotClient client = new PrivateJmxTracingPolicySnapshotClient(locator);

        assertThatThrownBy(client::initialize)
                .isInstanceOf(TracingConfigurationGuardException.class)
                .hasMessage("Cannot initialize snapshot client: private MBeanServer unavailable")
                .satisfies(startupFailure -> assertThat(startupFailure.getCause())
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("Expected exactly one compatible private control plane, found 0"));
    }
}
