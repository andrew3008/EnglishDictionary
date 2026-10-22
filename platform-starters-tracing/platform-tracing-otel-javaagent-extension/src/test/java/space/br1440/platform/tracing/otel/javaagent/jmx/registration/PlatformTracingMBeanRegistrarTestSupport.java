package space.br1440.platform.tracing.otel.javaagent.jmx.registration;

import javax.management.MBeanServer;

import lombok.experimental.UtilityClass;

import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadiness;

@UtilityClass
public final class PlatformTracingMBeanRegistrarTestSupport {

    public static PlatformTracingMBeanRegistrar create(MBeanServer server,
                                                       PlatformExtensionReadiness readiness,
                                                       AgentInstanceId agentInstanceId) {
        return new PlatformTracingMBeanRegistrar(server, readiness, agentInstanceId);
    }
}
