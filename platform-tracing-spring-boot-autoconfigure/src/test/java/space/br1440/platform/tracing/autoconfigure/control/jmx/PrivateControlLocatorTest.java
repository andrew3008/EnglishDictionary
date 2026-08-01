package space.br1440.platform.tracing.autoconfigure.control.jmx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.DynamicMBean;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import java.util.Map;
import java.util.UUID;

class PrivateControlLocatorTest {

    @Test
    void locatesExactlyOneCompatiblePrivateControlPlane() throws Exception {
        MBeanServer server = createCompatibleServer();
        try {
            PrivateControlLocator.LocatedPrivateControl located =
                    new PrivateControlLocator().locate();

            assertThat(located.server()).isSameAs(server);
            assertThat(located.writerObjectName().getCanonicalName())
                    .isEqualTo(new ObjectName(PrivateControlLocator.WRITER_NAME)
                            .getCanonicalName());
            assertThat(located.runtimeInstanceId()).isNotBlank();
        } finally {
            MBeanServerFactory.releaseMBeanServer(server);
        }
    }

    @Test
    void rejectsMissingCompatiblePrivateControlPlane() {
        assertThatThrownBy(() -> new PrivateControlLocator().locate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Expected exactly one compatible private control plane, found 0");
    }

    @Test
    void rejectsAmbiguousCompatiblePrivateControlPlanes() throws Exception {
        MBeanServer first = createCompatibleServer();
        MBeanServer second = createCompatibleServer();
        try {
            assertThatThrownBy(() -> new PrivateControlLocator().locate())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Expected exactly one compatible private control plane, found 2");
        } finally {
            MBeanServerFactory.releaseMBeanServer(second);
            MBeanServerFactory.releaseMBeanServer(first);
        }
    }

    private static MBeanServer createCompatibleServer() throws Exception {
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        String runtimeInstanceId = UUID.randomUUID().toString();
        server.registerMBean(
                new ReadOnlyDynamicMBean(Map.of(
                        "ProtocolId", PrivateControlLocator.PROTOCOL_ID,
                        "ProtocolVersion", PrivateControlLocator.PROTOCOL_VERSION,
                        "RuntimeInstanceId", runtimeInstanceId,
                        "WriterObjectName", PrivateControlLocator.WRITER_NAME,
                        "LifecycleState", "WRITER_READY",
                        "MarkerSignature", PrivateControlLocator.MARKER_SIGNATURE)),
                new ObjectName(PrivateControlLocator.MARKER_NAME));
        server.registerMBean(
                new ReadOnlyDynamicMBean(Map.of()),
                new ObjectName(PrivateControlLocator.WRITER_NAME));
        return server;
    }

    private static final class ReadOnlyDynamicMBean implements DynamicMBean {

        private final Map<String, Object> attributes;
        private final MBeanInfo mBeanInfo;

        private ReadOnlyDynamicMBean(Map<String, Object> attributes) {
            this.attributes = Map.copyOf(attributes);
            this.mBeanInfo = new MBeanInfo(
                    getClass().getName(),
                    "Тестовая read-only модель private control plane",
                    attributes.entrySet().stream()
                            .map(entry -> new MBeanAttributeInfo(
                                    entry.getKey(),
                                    entry.getValue().getClass().getName(),
                                    entry.getKey(),
                                    true,
                                    false,
                                    false))
                            .toArray(MBeanAttributeInfo[]::new),
                    null,
                    new MBeanOperationInfo[0],
                    null);
        }

        @Override
        public Object getAttribute(String attribute) {
            return attributes.get(attribute);
        }

        @Override
        public void setAttribute(Attribute attribute) {
            throw new UnsupportedOperationException("MBean доступен только для чтения");
        }

        @Override
        public AttributeList getAttributes(String[] requestedAttributes) {
            AttributeList result = new AttributeList();
            for (String attribute : requestedAttributes) {
                if (attributes.containsKey(attribute)) {
                    result.add(new Attribute(attribute, attributes.get(attribute)));
                }
            }
            return result;
        }

        @Override
        public AttributeList setAttributes(AttributeList attributes) {
            return new AttributeList();
        }

        @Override
        public Object invoke(String actionName, Object[] params, String[] signature)
                throws ReflectionException {
            throw new ReflectionException(
                    new NoSuchMethodException(actionName),
                    "Операции не поддерживаются");
        }

        @Override
        public MBeanInfo getMBeanInfo() {
            return mBeanInfo;
        }
    }
}
