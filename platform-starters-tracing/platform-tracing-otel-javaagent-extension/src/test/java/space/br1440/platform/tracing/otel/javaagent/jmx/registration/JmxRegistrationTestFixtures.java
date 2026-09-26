package space.br1440.platform.tracing.otel.javaagent.jmx.registration;

import java.util.function.Supplier;

import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionReadinessWireSnapshot;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadiness;

public final class JmxRegistrationTestFixtures {

    private JmxRegistrationTestFixtures() {
        throw new AssertionError("No instances");
    }

    public static Supplier<ExtensionReadinessWireSnapshot> initializingReadinessProjection(
            AgentInstanceId agentInstanceId) {
        ExtensionReadinessWireSnapshot snapshot = ExtensionReadinessWireSnapshot.initializing(
                new PlatformExtensionReadiness().snapshot(),
                agentInstanceId.toString()
        );
        return () -> snapshot;
    }
}
