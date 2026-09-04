package space.br1440.platform.tracing.test.harness;

import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.api.propagation.control.InboundTraceControl;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class InboundTraceControls {

    public static InboundTraceControl of(boolean forceTrace,
                                         boolean qaTrace,
                                         @Nullable String requestId) {
        return of(forceTrace, qaTrace, requestId, null, null);
    }

    public static InboundTraceControl of(boolean forceTrace,
                                         boolean qaTrace,
                                         @Nullable String requestId,
                                         @Nullable String samplingReason,
                                         @Nullable String rawForceTraceValue) {
        return new InboundTraceControl(forceTrace, qaTrace, requestId, samplingReason, rawForceTraceValue);
    }
}
