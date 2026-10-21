package space.br1440.platform.tracing.otel.javaagent.jmx.registration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.mock;

import java.util.List;
import javax.management.DynamicMBean;
import javax.management.MBeanServerFactory;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.jmx.DomainObjectNames;

class MBeanRegistrationScopeTest {

    @Test
    void batchMutationsFailClosedWithoutSerializationLock() {
        // given
        MBeanRegistrationScope scope = newScope();
        MBeanBatchRegistrar.Registration registration = MBeanBatchRegistrar.Registration.dynamic(
                DomainObjectNames.EXTENSION_READINESS,
                mock(DynamicMBean.class));

        // when
        // then
        assertThatThrownBy(() -> {
            try (MBeanBatchRegistrar.RegisteredBatch batch = scope.registerOne(registration)) {
                fail("registration without the serialization lock returned " + batch.batchId());
            }
        })
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MBean batch mutation requires MBeanRegistrationScope serialization lock");
        assertThatThrownBy(() -> {
            try (MBeanBatchRegistrar.RegisteredBatch batch = scope.registerAllOrRollback(List.of(registration))) {
                fail("registration without the serialization lock returned " + batch.batchId());
            }
        })
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MBean batch mutation requires MBeanRegistrationScope serialization lock");
        assertThatThrownBy(scope::reclaimUnreclaimed)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MBean batch mutation requires MBeanRegistrationScope serialization lock");
    }

    @Test
    void emptyReclaimSucceedsUnderSerializationLock() {
        // given
        MBeanRegistrationScope scope = newScope();

        // when
        // then
        assertThatCode(() -> scope.runSerialized(scope::reclaimUnreclaimed)).doesNotThrowAnyException();
    }

    private static MBeanRegistrationScope newScope() {
        return new MBeanRegistrationScope(MBeanServerFactory.newMBeanServer());
    }
}
