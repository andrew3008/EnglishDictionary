package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport;

import javax.management.MBeanServerConnection;

public final class JmxReadExecutorTestFactory {

    private JmxReadExecutorTestFactory() {
    }

    public static JmxReadExecutor withoutAgentInstanceVerification(MBeanServerConnection connection) {
        return new JmxReadExecutor(connection);
    }
}
