package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.ManagementFactory;
import java.util.Set;
import java.util.stream.Collectors;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.junit.jupiter.api.Test;

/**
 * S6 characterization: constructing either SPI entrypoint must not install process-global
 * resources (MBeans, shutdown hooks, gate binds). All side effects belong to prepare/activate.
 */
class BootstrapSpiConstructionCharacterizationTest {

    @Test
    void constructing_provider_and_listener_installs_zero_platform_mbeans() throws Exception {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        Set<ObjectName> before = platformTracingNames(server);

        new BootstrapAutoConfigurationCustomizerProvider(BootstrapCoordinator.create());
        new ActivationAgentListener(BootstrapCoordinator.create());

        assertThat(platformTracingNames(server))
                .as("SPI construction must be side-effect free (S6 characterization)")
                .isEqualTo(before);
    }

    private static Set<ObjectName> platformTracingNames(MBeanServer server) throws Exception {
        return server.queryNames(new ObjectName("space.br1440.platform.tracing:*"), null)
                .stream()
                .map(ObjectName::getCanonicalName)
                .map(name -> {
                    try {
                        return new ObjectName(name);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })
                .collect(Collectors.toSet());
    }
}
