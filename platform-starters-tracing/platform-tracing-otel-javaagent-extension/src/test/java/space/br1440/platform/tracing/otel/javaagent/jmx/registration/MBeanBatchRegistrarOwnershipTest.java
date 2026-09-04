package space.br1440.platform.tracing.otel.javaagent.jmx.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.DynamicMBean;
import javax.management.InstanceAlreadyExistsException;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.NotCompliantMBeanException;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.management.ReflectionException;

import org.junit.jupiter.api.Test;

class MBeanBatchRegistrarOwnershipTest {

    private static final ObjectName FIRST = objectName("First");
    private static final ObjectName SECOND = objectName("Second");
    private static final ObjectName THIRD = objectName("Third");

    @Test
    void registration_collision_does_not_replace_foreign_mbean() throws Exception {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        server.registerMBean(new ValueDynamicMBean("foreign"), FIRST);
        MBeanBatchRegistrar registrar = new MBeanBatchRegistrar(server);

        // when
        assertThatThrownBy(() -> registrar.registerOne(registration(FIRST, "owned")))
                .isInstanceOf(JmxRegistrationException.class)
                .hasCauseInstanceOf(InstanceAlreadyExistsException.class)
                .hasMessageContaining("ownership collision");

        // then
        assertThat(server.getAttribute(FIRST, "Value")).isEqualTo("foreign");
    }

    @Test
    void foreign_deregistration_is_rejected_but_owner_cleanup_succeeds() throws Exception {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        MBeanBatchRegistrar registrar = new MBeanBatchRegistrar(server);
        MBeanBatchRegistrar.RegisteredBatch batch = registrar.registerOne(registration(FIRST, "owned"));

        // when
        assertThatThrownBy(() -> server.unregisterMBean(FIRST))
                .isInstanceOf(MBeanRegistrationException.class)
                .rootCause()
                .hasMessageContaining("ownership mismatch");

        // then
        assertThat(server.isRegistered(FIRST)).isTrue();
        batch.close();
        assertThat(server.isRegistered(FIRST)).isFalse();
    }

    @Test
    void failed_batch_rolls_back_owned_registrations_in_reverse_order() {
        // given
        MBeanServer target = MBeanServerFactory.newMBeanServer();
        List<ObjectName> removed = new ArrayList<>();
        MBeanServer failingServer = new DelegatingMBeanServer(target) {
            @Override
            public ObjectInstance registerMBean(Object object, ObjectName name)
                    throws InstanceAlreadyExistsException, MBeanRegistrationException,
                    NotCompliantMBeanException {
                if (THIRD.equals(name)) {
                    throw new NotCompliantMBeanException("simulated registration failure");
                }
                return super.registerMBean(object, name);
            }

            @Override
            public void unregisterMBean(ObjectName name)
                    throws InstanceNotFoundException, MBeanRegistrationException {
                removed.add(name);
                super.unregisterMBean(name);
            }
        };
        MBeanBatchRegistrar registrar = new MBeanBatchRegistrar(failingServer);

        // when
        assertThatThrownBy(() -> registrar.registerAllOrRollback(List.of(
                registration(FIRST, "first"),
                registration(SECOND, "second"),
                registration(THIRD, "third"))))
                .isInstanceOf(JmxRegistrationException.class)
                .hasMessageContaining(THIRD.toString());

        // then
        assertThat(removed).containsExactly(SECOND, FIRST);
        assertThat(target.isRegistered(FIRST)).isFalse();
        assertThat(target.isRegistered(SECOND)).isFalse();
    }

    @Test
    void fatalRegistrationFailureRollsBackOwnedRegistrationsAndPropagatesOriginalError() {
        // given
        MBeanServer target = MBeanServerFactory.newMBeanServer();
        List<ObjectName> removed = new ArrayList<>();
        NoClassDefFoundError fatalFailure = new NoClassDefFoundError("simulated fatal registration failure");
        MBeanServer failingServer = new DelegatingMBeanServer(target) {
            @Override
            public ObjectInstance registerMBean(Object object, ObjectName name)
                    throws InstanceAlreadyExistsException, MBeanRegistrationException,
                    NotCompliantMBeanException {
                if (THIRD.equals(name)) {
                    throw fatalFailure;
                }
                return super.registerMBean(object, name);
            }

            @Override
            public void unregisterMBean(ObjectName name)
                    throws InstanceNotFoundException, MBeanRegistrationException {
                removed.add(name);
                super.unregisterMBean(name);
            }
        };
        MBeanBatchRegistrar registrar = new MBeanBatchRegistrar(failingServer);

        // when
        assertThatThrownBy(() -> registrar.registerAllOrRollback(List.of(
                registration(FIRST, "first"),
                registration(SECOND, "second"),
                registration(THIRD, "third"))))
                .isSameAs(fatalFailure);

        // then
        assertThat(removed).containsExactly(SECOND, FIRST);
        assertThat(target.isRegistered(FIRST)).isFalse();
        assertThat(target.isRegistered(SECOND)).isFalse();
        assertThat(target.isRegistered(THIRD)).isFalse();
    }

    @Test
    void batch_identity_is_internal_unique_and_stable() {
        // given
        MBeanServer firstServer = MBeanServerFactory.newMBeanServer();
        MBeanServer secondServer = MBeanServerFactory.newMBeanServer();

        // when
        MBeanBatchRegistrar.RegisteredBatch firstBatch = new MBeanBatchRegistrar(firstServer)
                .registerAllOrRollback(List.of(registration(FIRST, "first"), registration(SECOND, "second")));
        MBeanBatchRegistrar.RegisteredBatch secondBatch = new MBeanBatchRegistrar(secondServer)
                .registerOne(registration(FIRST, "first"));

        // then
        assertThat(firstBatch.batchId()).isNotEqualTo(secondBatch.batchId());
        assertThat(firstBatch.registrationIds()).hasSize(2).doesNotHaveDuplicates();
        assertThat(FIRST.getKeyPropertyList()).doesNotContainKeys("batchId", "registrationId");
        firstBatch.close();
        secondBatch.close();
    }

    private static MBeanBatchRegistrar.Registration registration(ObjectName name, String value) {
        return MBeanBatchRegistrar.Registration.dynamic(name, new ValueDynamicMBean(value));
    }

    private static ObjectName objectName(String name) {
        try {
            return new ObjectName("space.br1440.platform.tracing.test:type=Ownership,name=" + name);
        } catch (Exception exception) {
            throw new IllegalStateException("Test ObjectName is invalid", exception);
        }
    }

    private static final class ValueDynamicMBean implements DynamicMBean {

        private final String value;

        private ValueDynamicMBean(String value) {
            this.value = value;
        }

        @Override
        public Object getAttribute(String attribute) throws AttributeNotFoundException {
            if (!"Value".equals(attribute)) {
                throw new AttributeNotFoundException(attribute);
            }
            return value;
        }

        @Override
        public void setAttribute(Attribute attribute) throws AttributeNotFoundException {
            throw new AttributeNotFoundException(attribute == null ? "<null>" : attribute.getName());
        }

        @Override
        public AttributeList getAttributes(String[] attributes) {
            AttributeList values = new AttributeList();
            for (String attribute : attributes) {
                if ("Value".equals(attribute)) {
                    values.add(new Attribute("Value", value));
                }
            }
            return values;
        }

        @Override
        public AttributeList setAttributes(AttributeList attributes) {
            return new AttributeList();
        }

        @Override
        public Object invoke(String actionName, Object[] params, String[] signature)
                throws MBeanException, ReflectionException {
            throw new ReflectionException(new NoSuchMethodException(actionName));
        }

        @Override
        public MBeanInfo getMBeanInfo() {
            return new MBeanInfo(
                    ValueDynamicMBean.class.getName(),
                    "Test value",
                    new MBeanAttributeInfo[]{new MBeanAttributeInfo(
                            "Value", String.class.getName(), "Value", true, false, false)},
                    null,
                    null,
                    null);
        }
    }
}
