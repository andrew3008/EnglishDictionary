package space.br1440.platform.tracing.otel.javaagent.processor;

import jakarta.annotation.Nonnull;

import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;

public final class RequestIdPropagatingSpanProcessor implements SpanProcessor, ProcessorDiagnosticIdentity {

    static final String DIAGNOSTIC_ID = JmxWireContract.KEY_PROCESSOR_ERRORS_BY_NAME_REQUEST_ID_PROPAGATION;

    private static final AttributeKey<String> REQUEST_ID = AttributeKey.stringKey(PlatformAttributes.PLATFORM_REQUEST_ID);

    @Override
    public String diagnosticId() {
        return DIAGNOSTIC_ID;
    }

    @Override
    public void onStart(@Nonnull Context parentContext, @Nonnull ReadWriteSpan child) {
        if (child.getAttribute(REQUEST_ID) != null) {
            return;
        }

        Span parent = Span.fromContext(parentContext);
        if (!(parent instanceof ReadableSpan readableParent)) {
            return;
        }

        String requestId = readableParent.getAttribute(REQUEST_ID);
        if (!StringUtils.isNullOrEmpty(requestId)) {
            child.setAttribute(REQUEST_ID, requestId);
        }
    }

    @Override
    public boolean isStartRequired() {
        return true;
    }

    @Override
    public void onEnd(@Nonnull ReadableSpan span) {
    }

    @Override
    public boolean isEndRequired() {
        return false;
    }
}
