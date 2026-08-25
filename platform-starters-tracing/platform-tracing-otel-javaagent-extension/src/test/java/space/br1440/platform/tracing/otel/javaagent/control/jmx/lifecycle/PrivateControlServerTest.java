package space.br1440.platform.tracing.otel.javaagent.control.jmx.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.DynamicMBean;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.MBeanRegistration;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;
import javax.management.ReflectionException;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.jmx.protocol.PrivateControlObjectNames;
import space.br1440.platform.tracing.otel.javaagent.control.jmx.protocol.PrivateControlWriterMBean;
import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsStore;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsValidationConstraints;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingLogLevel;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.LastSettingsUpdate;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.TracingSettingsUpdater;

class PrivateControlServerTest {

    @Test
    void registerMarkerAndWriterExposesExactMBeanInfoClassNames() throws Exception {
        PrivateControlServer server = new PrivateControlServer();
        try {
            server.registerMarker(new PrivateControlMarkerMBean(UUID.randomUUID().toString()));

            TracingSettingsStore store = store();
            TracingSettingsUpdater updater = new TracingSettingsUpdater(
                    store, new SettingsValidationConstraints(true), new LastSettingsUpdate());
            server.registerWriter(new PrivateControlWriterMBean(updater, store));

            MBeanServer jmx = server.server();
            assertThat(jmx.isRegistered(PrivateControlObjectNames.MARKER)).isTrue();
            assertThat(jmx.isRegistered(PrivateControlObjectNames.WRITER)).isTrue();
            assertThat(jmx.getMBeanInfo(PrivateControlObjectNames.MARKER).getClassName())
                    .isEqualTo(PrivateControlMarkerMBean.class.getName());
            assertThat(jmx.getMBeanInfo(PrivateControlObjectNames.WRITER).getClassName())
                    .isEqualTo(PrivateControlWriterMBean.class.getName());
        } finally {
            server.close();
        }
    }

    @Test
    void closeReleasesServerAndIsIdempotent() throws Exception {
        PrivateControlServer server = new PrivateControlServer();
        MBeanServer jmx = server.server();
        server.registerMarker(new PrivateControlMarkerMBean(UUID.randomUUID().toString()));

        assertThat(server.close()).isEmpty();
        assertThat(MBeanServerFactory.findMBeanServer(null)).doesNotContain(jmx);

        assertThat(server.close()).isEmpty();
    }

    @Test
    void closeAttemptsEveryStepIndependentlyAndAggregatesFailures() throws Exception {
        MBeanServer injected = MBeanServerFactory.createMBeanServer(PrivateControlObjectNames.PRIVATE_DOMAIN);
        injected.registerMBean(new RejectingUnregisterMBean(), PrivateControlObjectNames.WRITER);
        injected.registerMBean(new RejectingUnregisterMBean(), PrivateControlObjectNames.MARKER);
        PrivateControlServer server = new PrivateControlServer(injected);

        List<Exception> failures = server.close();

        assertThat(failures).hasSize(2);
        assertThat(MBeanServerFactory.findMBeanServer(null)).doesNotContain(injected);
    }

    private static TracingSettingsStore store() {
        return new TracingSettingsStore(UUID.randomUUID().toString(), TracingSettings.of(
                true, 1.0d, Map.of(), true, false, true, true, TracingLogLevel.INFO));
    }

    private static final class RejectingUnregisterMBean implements DynamicMBean, MBeanRegistration {

        private static final MBeanInfo INFO = new MBeanInfo(
                RejectingUnregisterMBean.class.getName(),
                "Rejects unregister for cleanup testing",
                null,
                null,
                null,
                null);

        @Override
        public ObjectName preRegister(MBeanServer server, ObjectName name) {
            return name;
        }

        @Override
        public void postRegister(Boolean registrationDone) {
        }

        @Override
        public void preDeregister() {
            throw new IllegalStateException("Cleanup blocked by test MBean");
        }

        @Override
        public void postDeregister() {
        }

        @Override
        public Object getAttribute(String attribute) {
            throw new IllegalArgumentException("Unknown attribute: " + attribute);
        }

        @Override
        public void setAttribute(Attribute attribute) {
            throw new UnsupportedOperationException("Test MBean is read-only");
        }

        @Override
        public AttributeList getAttributes(String[] attributes) {
            return new AttributeList();
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
            return INFO;
        }
    }
}
