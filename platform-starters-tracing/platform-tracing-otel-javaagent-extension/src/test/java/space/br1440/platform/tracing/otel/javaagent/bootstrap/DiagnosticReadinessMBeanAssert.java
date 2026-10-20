package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.WIRE_FAILED;

import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.openmbean.CompositeData;

import org.assertj.core.api.AbstractAssert;

import space.br1440.platform.tracing.otel.javaagent.jmx.DomainObjectNames;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;

@SuppressWarnings("UnusedReturnValue")
final class DiagnosticReadinessMBeanAssert extends AbstractAssert<DiagnosticReadinessMBeanAssert, MBeanServer> {

    private DiagnosticReadinessMBeanAssert(MBeanServer actual) {
        super(actual, DiagnosticReadinessMBeanAssert.class);
    }

    static DiagnosticReadinessMBeanAssert assertThatDiagnosticMBean(MBeanServer actual) {
        return new DiagnosticReadinessMBeanAssert(actual);
    }

    DiagnosticReadinessMBeanAssert isRegistered() {
        isNotNull();
        assertThat(actual.isRegistered(DomainObjectNames.EXTENSION_READINESS))
                .as("diagnostic readiness MBean registered")
                .isTrue();
        return myself;
    }

    DiagnosticReadinessMBeanAssert isNotRegistered() {
        isNotNull();
        assertThat(actual.isRegistered(DomainObjectNames.EXTENSION_READINESS))
                .as("diagnostic readiness MBean registered")
                .isFalse();
        return myself;
    }

    DiagnosticReadinessMBeanAssert isFailedWith(String expectedCode) {
        return hasSnapshotEntry(JmxWireContract.FIELD_READINESS_SNAPSHOT_LIFECYCLE, WIRE_FAILED)
                .hasSnapshotEntry(JmxWireContract.FIELD_READINESS_SNAPSHOT_FAILURE_CODE, expectedCode);
    }

    DiagnosticReadinessMBeanAssert hasSnapshotEntry(String field, Object expectedValue) {
        isRegistered();
        assertThat(snapshot().get(field)).as("diagnostic snapshot field %s", field).isEqualTo(expectedValue);
        return myself;
    }

    @SuppressWarnings("SameParameterValue")
    DiagnosticReadinessMBeanAssert isOwnedBy(Class<?> expectedType) {
        isRegistered();
        String className;
        try {
            className = actual.getObjectInstance(DomainObjectNames.EXTENSION_READINESS).getClassName();
        } catch (JMException failure) {
            throw new AssertionError("Cannot resolve diagnostic readiness MBean owner", failure);
        }

        assertThat(className).as("diagnostic readiness MBean class").isEqualTo(expectedType.getName());
        return myself;
    }

    private CompositeData snapshot() {
        try {
            return (CompositeData) actual.getAttribute(
                    DomainObjectNames.EXTENSION_READINESS,
                    JmxWireContract.ATTRIBUTE_EXTENSION_READINESS_READINESS_SNAPSHOT
            );
        } catch (JMException failure) {
            throw new AssertionError("Cannot read diagnostic readiness snapshot", failure);
        }
    }
}
