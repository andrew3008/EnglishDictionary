package space.br1440.platform.tracing.otel.javaagent.jmx.registration;

import java.util.function.Supplier;

import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionReadinessWireSnapshot;

public final class JmxRegistrationTestFixtures {

    private JmxRegistrationTestFixtures() {
        throw new AssertionError("No instances");
    }

    public static Supplier<ExtensionReadinessWireSnapshot> initializingReadinessProjection() {
        ExtensionReadinessWireSnapshot snapshot = ExtensionReadinessWireSnapshot.initializing();
        return () -> snapshot;
    }
}
