package space.br1440.platform.tracing.otel.javaagent.jmx.registration;

import static space.br1440.platform.tracing.otel.javaagent.readiness.ReadinessSnapshotAssertions.initializingOf;

import java.util.function.Supplier;

import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionReadinessWireSnapshot;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadiness;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class JmxRegistrationTestFixtures {

    public static Supplier<ExtensionReadinessWireSnapshot> initializingReadinessProjection(
            AgentInstanceId agentInstanceId) {
        ExtensionReadinessWireSnapshot snapshot = ExtensionReadinessWireSnapshot.initializing(
                initializingOf(PlatformExtensionReadiness.forCurrentExtension().snapshot()),
                agentInstanceId.toString()
        );
        return () -> snapshot;
    }
}
