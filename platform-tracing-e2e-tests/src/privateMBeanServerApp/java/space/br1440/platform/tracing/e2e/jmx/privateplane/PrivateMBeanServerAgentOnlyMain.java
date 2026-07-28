package space.br1440.platform.tracing.e2e.jmx.privateplane;

import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;
import java.util.ArrayList;
import java.util.List;

/**
 * JDK-only application observer для Agent-only варианта S-03.
 */
public final class PrivateMBeanServerAgentOnlyMain {

    private static final String MARKER_NAME =
            "space.br1440.platform.tracing.private:type=Marker,name=RuntimeControl";
    private static final String WRITER_NAME =
            "space.br1440.platform.tracing.private:type=Control,name=RuntimePolicyWriter";

    private PrivateMBeanServerAgentOnlyMain() {
    }

    public static void main(String[] args) throws Exception {
        ObjectName marker = new ObjectName(MARKER_NAME);
        ObjectName writer = new ObjectName(WRITER_NAME);
        List<MBeanServer> matches = new ArrayList<>();
        for (MBeanServer server : MBeanServerFactory.findMBeanServer(null)) {
            if (server.isRegistered(marker)
                    && "WRITER_READY".equals(server.getAttribute(marker, "LifecycleState"))
                    && server.isRegistered(writer)) {
                matches.add(server);
            }
        }
        if (matches.size() != 1) {
            throw new IllegalStateException("Expected one Agent-owned private server: " + matches.size());
        }
        MBeanServer server = matches.get(0);
        System.out.println("S03_APP:M7=PASS");
        System.out.println("S03_APP:agentOnlySpringVisible="
                + classVisible("org.springframework.context.ApplicationContext"));
        server.invoke(writer, "shutdown", new Object[0], new String[0]);
        if (MBeanServerFactory.findMBeanServer(null).contains(server)) {
            throw new IllegalStateException("Agent-only private server leaked");
        }
        System.out.println("S03_APP:M5_AGENT_ONLY=PASS");
    }

    private static boolean classVisible(String name) {
        try {
            Class.forName(name, false, PrivateMBeanServerAgentOnlyMain.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException expected) {
            return false;
        }
    }
}
