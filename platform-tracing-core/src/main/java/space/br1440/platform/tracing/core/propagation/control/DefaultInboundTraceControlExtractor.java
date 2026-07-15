package space.br1440.platform.tracing.core.propagation.control;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import space.br1440.platform.tracing.api.attributes.PlatformSamplingReasons;
import space.br1440.platform.tracing.api.propagation.RequestIdSupports;
import space.br1440.platform.tracing.api.propagation.control.InboundTraceControl;
import space.br1440.platform.tracing.api.propagation.control.InboundTraceControlExtractor;

/**
 * Реализация {@link InboundTraceControlExtractor} без singleton-поля INSTANCE.
 */
public final class DefaultInboundTraceControlExtractor implements InboundTraceControlExtractor {

    public DefaultInboundTraceControlExtractor() {
    }

    @Override
    @Nonnull
    public InboundTraceControl fromHeaders(
            @Nullable String traceOn,
            @Nullable String qaTrace,
            @Nullable String requestId) {
        boolean isForceTrace = "on".equalsIgnoreCase(traceOn);
        boolean isQaTrace = (qaTrace != null) && !qaTrace.isBlank();

        String reason = null;
        if (isForceTrace) {
            reason = PlatformSamplingReasons.FORCE_HEADER;
        } else if (isQaTrace) {
            reason = PlatformSamplingReasons.QA_TRACE;
        }

        String validRequestId = RequestIdSupports.get().sanitizeOrNull(requestId);
        return new InboundTraceControl(isForceTrace, isQaTrace, validRequestId, reason, traceOn);
    }
}
