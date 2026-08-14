package space.br1440.platform.tracing.autoconfigure.control.jmx;

import java.util.List;

import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;

@FunctionalInterface
interface MBeanServerSource {

    List<MBeanServer> snapshot();

    static MBeanServerSource factoryRetained() {
        return () -> List.copyOf(MBeanServerFactory.findMBeanServer(null));
    }
}
