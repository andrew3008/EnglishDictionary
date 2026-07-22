package space.br1440.platform.tracing.otel.javaagent.processor;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;

import java.util.concurrent.atomic.LongAdder;

public class MetricsSpanProcessor implements SpanProcessor {

    private static final AttributeKey<String> TIMEOUT_KEY = AttributeKey.stringKey(PlatformAttributes.PLATFORM_TIMEOUT);

    private final LongAdder endedSpans = new LongAdder();
    private final LongAdder errorSpans = new LongAdder();
    private final LongAdder timeoutSpans = new LongAdder();
    private final LongAdder droppedAttributes = new LongAdder();
    private final LongAdder droppedEvents = new LongAdder();
    private final LongAdder droppedLinks = new LongAdder();

    @Override
    public void onStart(@Nonnull Context parentContext, @Nonnull ReadWriteSpan span) {
    }

    @Override
    public boolean isStartRequired() {
        return false;
    }

    @Override
    public void onEnd(ReadableSpan span) {
        endedSpans.increment();

        SpanData spanData = span.toSpanData();

        if (spanData.getStatus().getStatusCode() == StatusCode.ERROR) {
            errorSpans.increment();
        }

        if (span.getAttribute(TIMEOUT_KEY) != null) {
            timeoutSpans.increment();
        }

        long attrsDropped = Math.max(0L, spanData.getTotalAttributeCount() - spanData.getAttributes().size());
        if (attrsDropped > 0) {
            droppedAttributes.add(attrsDropped);
        }

        long eventsDropped = Math.max(0L, spanData.getTotalRecordedEvents() - spanData.getEvents().size());
        if (eventsDropped > 0) {
            droppedEvents.add(eventsDropped);
        }

        long linksDropped = Math.max(0L, spanData.getTotalRecordedLinks() - spanData.getLinks().size());
        if (linksDropped > 0) {
            droppedLinks.add(linksDropped);
        }
    }

    @Override
    public boolean isEndRequired() {
        return true;
    }

    public long getEndedSpans() {
        return endedSpans.sum();
    }

    public long getErrorSpans() {
        return errorSpans.sum();
    }

    public long getTimeoutSpans() {
        return timeoutSpans.sum();
    }

    public long getDroppedAttributes() {
        return droppedAttributes.sum();
    }

    public long getDroppedEvents() {
        return droppedEvents.sum();
    }

    public long getDroppedLinks() {
        return droppedLinks.sum();
    }

}
