package space.br1440.platform.tracing.otel.javaagent.control.jmx.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ReflectionException;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.OpenMBeanInfoSupport;
import javax.management.openmbean.OpenMBeanOperationInfo;
import javax.management.openmbean.OpenMBeanParameterInfo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsStore;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.TracingSettingsUpdater;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;

final class PlatformSettingsMutationInvokeContractTest {

    private TracingSettingsStore store;
    private MBeanServer server;

    @BeforeEach
    void setUp() throws Exception {
        store = JmxSettingsMutationFixtures.store();
        TracingSettingsUpdater updater = new TracingSettingsUpdater(store, level -> { });
        server = MBeanServerFactory.createMBeanServer();
        server.registerMBean(
                new PlatformSettingsMutationMBean(updater),
                JmxWireContract.SETTINGS_MUTATION_OBJECT_NAME);
    }

    @AfterEach
    void tearDown() {
        MBeanServerFactory.releaseMBeanServer(server);
    }

    @Test
    void canonicalApplySnapshotInvocationSucceeds() throws Exception {
        // given
        CompositeData request = validRequest();

        // when
        Object result = server.invoke(
                JmxWireContract.SETTINGS_MUTATION_OBJECT_NAME,
                JmxWireContract.OPERATION_SETTINGS_MUTATION_APPLY_SNAPSHOT,
                new Object[]{request},
                new String[]{CompositeData.class.getName()});

        // then
        assertThat(result).isInstanceOf(CompositeData.class);
    }

    @Test
    void nonCanonicalApplySnapshotInvocationsAreRejected() throws Exception {
        // given
        CompositeData request = validRequest();

        // when
        // then
        assertThatThrownBy(() -> invoke(null, new String[]{CompositeData.class.getName()}))
                .isInstanceOf(ReflectionException.class);
        assertThatThrownBy(() -> invoke(new Object[0], new String[0]))
                .isInstanceOf(ReflectionException.class);
        assertThatThrownBy(() -> invoke(
                new Object[]{request, request},
                new String[]{CompositeData.class.getName(), CompositeData.class.getName()}))
                .isInstanceOf(ReflectionException.class);
        assertThatThrownBy(() -> invoke(new Object[]{"invalid"}, new String[]{String.class.getName()}))
                .isInstanceOf(ReflectionException.class);
        assertThatThrownBy(() -> invoke(new Object[]{request}, null))
                .isInstanceOf(ReflectionException.class);
        assertThatThrownBy(() -> invoke(new Object[]{request}, new String[0]))
                .isInstanceOf(ReflectionException.class);
        assertThatThrownBy(() -> invoke(
                new Object[]{request},
                new String[]{Object.class.getName()}))
                .isInstanceOf(ReflectionException.class);
    }

    @Test
    void unknownOperationUsesFixedBootstrapException() {
        // given
        String untrustedOperation = "deletePolicy\r\nforged";

        // when
        // then
        assertThatThrownBy(() -> server.invoke(
                JmxWireContract.SETTINGS_MUTATION_OBJECT_NAME,
                untrustedOperation,
                new Object[0],
                new String[0]))
                .isInstanceOf(ReflectionException.class)
                .hasCauseInstanceOf(NoSuchMethodException.class)
                .cause()
                .hasMessage("Unsupported JMX settings mutation operation");
    }

    @Test
    void mBeanInfoPublishesOneExactOpenMutationOperation() throws Exception {
        // given
        MBeanInfo info = server.getMBeanInfo(
                JmxWireContract.SETTINGS_MUTATION_OBJECT_NAME);

        // when
        MBeanOperationInfo operation = info.getOperations()[0];

        // then
        assertThat(info).isInstanceOf(OpenMBeanInfoSupport.class);
        assertThat(info.getClassName()).isEqualTo(PlatformSettingsMutationMBean.class.getName());
        assertThat(info.getAttributes()).isEmpty();
        assertThat(info.getConstructors()).isEmpty();
        assertThat(info.getNotifications()).isEmpty();
        assertThat(info.getOperations()).hasSize(1);
        assertThat(operation.getName())
                .isEqualTo(JmxWireContract.OPERATION_SETTINGS_MUTATION_APPLY_SNAPSHOT);
        assertThat(operation.getImpact()).isEqualTo(MBeanOperationInfo.ACTION);
        assertThat(operation).isInstanceOf(OpenMBeanOperationInfo.class);
        OpenMBeanOperationInfo openOperation = (OpenMBeanOperationInfo) operation;
        assertThat(openOperation.getReturnOpenType())
                .isEqualTo(JmxOpenTypes.APPLY_SNAPSHOT_RESPONSE);
        assertThat(operation.getSignature()).singleElement()
                .isInstanceOf(OpenMBeanParameterInfo.class);
        OpenMBeanParameterInfo request = (OpenMBeanParameterInfo) operation.getSignature()[0];
        assertThat(operation.getSignature()[0].getName()).isEqualTo(
                JmxWireContract.PARAMETER_SETTINGS_MUTATION_APPLY_SNAPSHOT_REQUEST);
        assertThat(request.getOpenType()).isEqualTo(JmxOpenTypes.APPLY_SNAPSHOT_REQUEST);
    }

    private void invoke(Object[] params, String[] signature) throws Exception {
        server.invoke(
                JmxWireContract.SETTINGS_MUTATION_OBJECT_NAME,
                JmxWireContract.OPERATION_SETTINGS_MUTATION_APPLY_SNAPSHOT,
                params,
                signature);
    }

    private CompositeData validRequest() throws Exception {
        return JmxSettingsMutationFixtures.request(
                store.current().agentInstanceId().toString(),
                store.current().acceptedSequence(),
                JmxSettingsMutationFixtures.settings(0.5d),
                UUID.randomUUID().toString());
    }
}
