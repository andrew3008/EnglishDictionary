package space.br1440.platform.tracing.e2e.collision;

import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.DynamicMBean;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.ObjectName;

public final class JmxCollisionPremainAgent {

    private static final String SETTINGS_STATUS_OBJECT_NAME =
            "space.br1440.platform.tracing:type=Control,name=PlatformSettingsStatus,role=Observation";

    private JmxCollisionPremainAgent() {
    }

    public static void premain(String arguments, Instrumentation instrumentation) throws Exception {
        ManagementFactory.getPlatformMBeanServer()
                .registerMBean(new ForeignOwnerMBean(), new ObjectName(SETTINGS_STATUS_OBJECT_NAME));
    }

    private static final class ForeignOwnerMBean implements DynamicMBean {

        private static final MBeanInfo INFO = new MBeanInfo(
                ForeignOwnerMBean.class.getName(),
                "foreign owner",
                new MBeanAttributeInfo[0],
                null,
                new MBeanOperationInfo[0],
                null
        );

        @Override
        public Object getAttribute(String attribute) {
            throw new UnsupportedOperationException(attribute);
        }

        @Override
        public void setAttribute(Attribute attribute) {
            throw new UnsupportedOperationException();
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
        public Object invoke(String actionName, Object[] parameters, String[] signature) {
            throw new UnsupportedOperationException(actionName);
        }

        @Override
        public MBeanInfo getMBeanInfo() {
            return INFO;
        }
    }
}
