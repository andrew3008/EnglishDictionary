package space.br1440.platform.tracing.otel.javaagent.propagation;

import space.br1440.platform.tracing.otel.javaagent.control.state.AcceptedPolicyAccess;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

public final class PlatformPropagationGate {

    private static final PlatformPropagationGate SHARED = new PlatformPropagationGate();

    private final AtomicReference<AcceptedPolicyAccess> acceptedPolicy = new AtomicReference<>(AcceptedPolicyAccess.isolatedDefaults());
    private final LongAdder gatedInjects = new LongAdder();
    private final LongAdder gatedExtracts = new LongAdder();

    public static PlatformPropagationGate shared() {
        return SHARED;
    }

    public boolean isEnabled() {
        return acceptedPolicy.get().current().traceControlHeadersEnabled();
    }

    public void setEnabled(boolean value) {
        acceptedPolicy.get().updateTraceControlHeaders(value);
    }

    public void bindAcceptedPolicy(AcceptedPolicyAccess policy) {
        acceptedPolicy.set(Objects.requireNonNull(policy, "policy"));
    }

    void recordGatedInject() {
        gatedInjects.increment();
    }

    void recordGatedExtract() {
        gatedExtracts.increment();
    }

    public long getGatedInjects() {
        return gatedInjects.sum();
    }

    public long getGatedExtracts() {
        return gatedExtracts.sum();
    }

}
