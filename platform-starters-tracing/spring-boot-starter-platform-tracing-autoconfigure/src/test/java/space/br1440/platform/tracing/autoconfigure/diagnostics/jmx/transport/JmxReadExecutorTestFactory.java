package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport;

import javax.management.MBeanServerConnection;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class JmxReadExecutorTestFactory {

    public static JmxReadExecutor withoutAgentInstanceVerification(MBeanServerConnection connection) {
        return new JmxReadExecutor(connection);
    }
}
