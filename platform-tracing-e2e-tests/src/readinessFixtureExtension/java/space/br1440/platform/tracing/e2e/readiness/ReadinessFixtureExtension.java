package space.br1440.platform.tracing.e2e.readiness;

import java.lang.management.ManagementFactory;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.StandardMBean;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;

public final class ReadinessFixtureExtension implements AutoConfigurationCustomizerProvider {

    private static final String READINESS_OBJECT_NAME =
            "space.br1440.platform.tracing:type=Readiness,name=PlatformExtension";

    @Override
    public void customize(AutoConfigurationCustomizer customizer) {
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName(READINESS_OBJECT_NAME);
            if (server.isRegistered(name)) {
                server.unregisterMBean(name);
            }
            server.registerMBean(
                    new StandardMBean(new ReadinessFixture(), ReadinessFixtureMBean.class, false),
                    name);
        } catch (Exception failure) {
            throw new IllegalStateException("E1 readiness fixture could not register its MBean", failure);
        }
    }
}
