package space.br1440.platform.tracing.autoconfigure.control.jmx;

import java.util.Objects;
import javax.management.InstanceNotFoundException;
import javax.management.IntrospectionException;
import javax.management.MBeanServer;
import javax.management.ReflectionException;

import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceGuard;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxMBeanInfoShape;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

final class JmxSettingsMetadataGate {

    private final MBeanServer platformMBeanServer;
    private final AgentReadinessCoherenceGuard coherenceGuard;

    JmxSettingsMetadataGate(MBeanServer platformMBeanServer,
                            AgentReadinessCoherenceGuard coherenceGuard) {
        this.platformMBeanServer = Objects.requireNonNull(platformMBeanServer, "platformMBeanServer");
        this.coherenceGuard = Objects.requireNonNull(coherenceGuard, "coherenceGuard");
    }

    void verifyContractMetadata() throws Exception {
        coherenceGuard.readCoherently(() -> {
            validateStatusShape();
            validateMutationShape();
            return null;
        });
    }

    void verifyStatusMetadata() throws Exception {
        coherenceGuard.readCoherently(() -> {
            validateStatusShape();
            return null;
        });
    }

    private void validateStatusShape() throws InstanceNotFoundException, IntrospectionException, ReflectionException {
        JmxMBeanInfoShape.requireReadOnlySnapshot(
                platformMBeanServer.getMBeanInfo(JmxWireContract.SETTINGS_STATUS_OBJECT_NAME),
                JmxWireContract.ATTRIBUTE_SETTINGS_STATUS_ACCEPTED_POLICY_SNAPSHOT,
                JmxOpenTypes.ACCEPTED_POLICY_SNAPSHOT
        );
    }

    private void validateMutationShape() throws InstanceNotFoundException, IntrospectionException, ReflectionException {
        JmxMBeanInfoShape.requireMutation(
                platformMBeanServer.getMBeanInfo(JmxWireContract.SETTINGS_MUTATION_OBJECT_NAME),
                JmxWireContract.OPERATION_SETTINGS_MUTATION_APPLY_SNAPSHOT,
                JmxWireContract.PARAMETER_SETTINGS_MUTATION_APPLY_SNAPSHOT_REQUEST,
                JmxOpenTypes.APPLY_SNAPSHOT_REQUEST,
                JmxOpenTypes.APPLY_SNAPSHOT_RESPONSE
        );
    }
}
