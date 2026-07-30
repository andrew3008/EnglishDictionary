package space.br1440.platform.tracing.e2e.jmx.privateplane;

import com.sun.tools.attach.VirtualMachine;

import javax.management.Attribute;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.util.Set;

/**
 * Отдельный процесс remote/attach клиента S-03.
 */
public final class PrivateMBeanServerRemoteClientMain {

    private static final String WRITER_NAME =
            "space.br1440.platform.tracing.private:type=Control,name=RuntimePolicyWriter";
    private static final String PROJECTION_NAME =
            "space.br1440.platform.tracing:type=SpikeProjection,name=PrivateControlReadModel";
    private static final String SAMPLING_NAME =
            "space.br1440.platform.tracing:type=Sampling,name=PlatformSamplingControl";

    private PrivateMBeanServerRemoteClientMain() {
    }

    public static void main(String[] args) throws Exception {
        String address = args[0].startsWith("pid:")
                ? connectorAddress(args[0].substring("pid:".length()))
                : args[0];
        try (JMXConnector connector = JMXConnectorFactory.connect(new JMXServiceURL(address))) {
            MBeanServerConnection connection = connector.getMBeanServerConnection();
            Set<ObjectName> platformNames = connection.queryNames(
                    new ObjectName("space.br1440.platform.tracing:*"), null);
            ObjectName writer = new ObjectName(WRITER_NAME);
            boolean writerVisible = connection.isRegistered(writer);
            boolean invocationRejected = false;
            try {
                connection.invoke(writer, "apply", new Object[]{null},
                        new String[]{"javax.management.openmbean.CompositeData"});
            } catch (InstanceNotFoundException expected) {
                invocationRejected = true;
            }

            boolean projectionVisible = connection.isRegistered(new ObjectName(PROJECTION_NAME));
            boolean platformWriteRejected = false;
            ObjectName sampling = new ObjectName(SAMPLING_NAME);
            if (connection.isRegistered(sampling)) {
                try {
                    connection.setAttribute(sampling, new Attribute("SamplingRatio", 0.5d));
                } catch (Exception expected) {
                    // P6.6/P6.8: platform SamplingRatio is read-only; remote mutation must fail.
                    platformWriteRejected = true;
                }
            }

            emit("platformNameCount=" + platformNames.size());
            emit("privateWriterVisible=" + writerVisible);
            emit("privateWriterInvocationRejected=" + invocationRejected);
            emit("readOnlyProjectionVisible=" + projectionVisible);
            emit("platformWriteRejected=" + platformWriteRejected);
            if (writerVisible || !invocationRejected || !projectionVisible || !platformWriteRejected) {
                throw new IllegalStateException("Remote isolation assertion failed");
            }
        }
    }

    private static String connectorAddress(String pid) throws Exception {
        VirtualMachine vm = VirtualMachine.attach(pid);
        try {
            String address = vm.getAgentProperties().getProperty(
                    "com.sun.management.jmxremote.localConnectorAddress");
            if (address == null || address.isBlank()) {
                throw new IllegalStateException("Local connector address absent for pid " + pid);
            }
            return address;
        } finally {
            vm.detach();
        }
    }

    private static void emit(String value) {
        System.out.println("S03_REMOTE:" + value);
        System.out.flush();
    }
}
