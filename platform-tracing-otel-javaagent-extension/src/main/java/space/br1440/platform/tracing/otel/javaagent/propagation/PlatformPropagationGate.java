package space.br1440.platform.tracing.otel.javaagent.propagation;

import space.br1440.platform.tracing.otel.javaagent.control.state.AcceptedPolicyAccess;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class PlatformPropagationGate {

    private static final PlatformPropagationGate SHARED = new PlatformPropagationGate();

    private final AtomicReference<AcceptedPolicyAccess> acceptedPolicy = new AtomicReference<>(AcceptedPolicyAccess.isolatedDefaults());

    PlatformPropagationGate() {
    }

    public static PlatformPropagationGate shared() {
        return SHARED;
    }

    public boolean isEnabled() {
        return acceptedPolicy.get().current().traceControlHeadersEnabled();
    }

    public void bindAcceptedPolicy(AcceptedPolicyAccess policy) {
        acceptedPolicy.set(Objects.requireNonNull(policy, "policy"));
    }
}
