package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.WIRE_FAILED;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.WIRE_READY;

import org.assertj.core.api.AbstractAssert;

import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionReadinessWireSnapshot;

@SuppressWarnings("UnusedReturnValue")
final class BootstrapCoordinatorAssert extends AbstractAssert<BootstrapCoordinatorAssert, BootstrapCoordinator> {

    private BootstrapCoordinatorAssert(BootstrapCoordinator actual) {
        super(actual, BootstrapCoordinatorAssert.class);
    }

    static BootstrapCoordinatorAssert assertThatCoordinator(BootstrapCoordinator actual) {
        return new BootstrapCoordinatorAssert(actual);
    }

    BootstrapCoordinatorAssert isTerminallyFailedWith(String expectedCode) {
        isNotNull();
        assertThat(actual.state()).as("bootstrap state").isEqualTo(BootstrapState.FAILED);
        return hasWireFailure(expectedCode);
    }

    BootstrapCoordinatorAssert hasWireFailure(String expectedCode) {
        isNotNull();
        ExtensionReadinessWireSnapshot wire = actual.currentWire();
        assertThat(wire.lifecycle()).as("wire lifecycle").isEqualTo(WIRE_FAILED);
        assertThat(wire.failureCode()).as("wire failureCode").isEqualTo(expectedCode);
        return myself;
    }

    BootstrapCoordinatorAssert isActiveAndReady() {
        isNotNull();
        assertThat(actual.state()).as("bootstrap state").isEqualTo(BootstrapState.ACTIVE);
        assertThat(actual.currentWire().lifecycle()).as("wire lifecycle").isEqualTo(WIRE_READY);
        return myself;
    }
}
