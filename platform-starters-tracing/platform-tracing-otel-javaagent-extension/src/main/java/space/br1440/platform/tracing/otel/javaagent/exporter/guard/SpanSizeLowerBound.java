package space.br1440.platform.tracing.otel.javaagent.exporter.guard;

import java.io.Serial;
import java.nio.ByteBuffer;
import java.util.List;
import jakarta.annotation.Nullable;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.KeyValue;
import io.opentelemetry.api.common.Value;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;

final class SpanSizeLowerBound {

    static final int MAX_VALUE_DEPTH = 16;

    enum Verdict {
        WITHIN_LIMIT,
        EXCEEDS_LIMIT,
        VALUE_TOO_DEEP
    }

    static Verdict check(SpanData span, int limitBytes) {
        SpanSizeLowerBound bound = new SpanSizeLowerBound(limitBytes);
        try {
            bound.span(span);
        } catch (LimitBreach ignored) {
        }
        return bound.verdict;
    }

    private long remaining;

    private Verdict verdict = Verdict.WITHIN_LIMIT;

    private SpanSizeLowerBound(int limitBytes) {
        this.remaining = limitBytes;
    }

    private void span(SpanData span) {
        text(span.getName());
        text(span.getStatus().getDescription());
        attributes(span.getAttributes());

        Resource resource = span.getResource();
        attributes(resource.getAttributes());
        text(resource.getSchemaUrl());

        InstrumentationScopeInfo scope = span.getInstrumentationScopeInfo();
        text(scope.getName());
        text(scope.getVersion());
        text(scope.getSchemaUrl());
        attributes(scope.getAttributes());

        traceState(span.getSpanContext().getTraceState());

        List<EventData> events = span.getEvents();
        charge(events.size());
        for (EventData event : events) {
            text(event.getName());
            attributes(event.getAttributes());
        }

        List<LinkData> links = span.getLinks();
        charge(links.size());
        for (LinkData link : links) {
            attributes(link.getAttributes());
            traceState(link.getSpanContext().getTraceState());
        }
    }

    private void attributes(Attributes attributes) {
        charge(attributes.size());
        attributes.forEach(this::attribute);
    }

    private void attribute(AttributeKey<?> key, Object value) {
        text(key.getKey());
        switch (key.getType()) {
            case STRING -> text((String) value);
            case STRING_ARRAY -> strings((List<?>) value);
            case LONG_ARRAY, DOUBLE_ARRAY, BOOLEAN_ARRAY -> charge(((List<?>) value).size());
            case VALUE -> value((Value<?>) value, 0);
            case LONG, DOUBLE, BOOLEAN -> {
                // скаляр: учтена только запись и ключ
            }
            default -> throw new IllegalStateException("unsupported attribute type: " + key.getType());
        }
    }

    private void strings(List<?> values) {
        charge(values.size());
        for (Object value : values) {
            text((String) value);
        }
    }

    private void value(Value<?> value, int depth) {
        switch (value.getType()) {
            case STRING -> text((String) value.getValue());
            case BYTES -> charge(((ByteBuffer) value.getValue()).remaining());

            case ARRAY -> {
                List<?> elements = container((List<?>) value.getValue(), depth);
                for (Object element : elements) {
                    value((Value<?>) element, depth + 1);
                }
            }

            case KEY_VALUE_LIST -> {
                List<?> entries = container((List<?>) value.getValue(), depth);
                for (Object entry : entries) {
                    KeyValue keyValue = (KeyValue) entry;
                    text(keyValue.getKey());
                    value(keyValue.getValue(), depth + 1);
                }
            }

            case LONG, DOUBLE, BOOLEAN, EMPTY -> {
                // скаляр или пустое значение: учтена только запись контейнера
            }

            default -> throw new IllegalStateException("unsupported value type: " + value.getType());
        }
    }

    private List<?> container(List<?> elements, int depth) {
        if (depth >= MAX_VALUE_DEPTH) {
            throw breach(Verdict.VALUE_TOO_DEEP);
        }

        charge(elements.size());
        return elements;
    }

    private void traceState(TraceState state) {
        state.forEach((key, value) -> {
            text(key);
            text(value);
        });
    }

    private void text(@Nullable String value) {
        if (value != null) {
            charge(value.length());
        }
    }

    private void charge(long units) {
        remaining -= units;
        if (remaining < 0) {
            throw breach(Verdict.EXCEEDS_LIMIT);
        }
    }

    private LimitBreach breach(Verdict breached) {
        if (verdict == Verdict.WITHIN_LIMIT) {
            verdict = breached;
        }
        return LimitBreach.INSTANCE;
    }

    private static final class LimitBreach extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;
        private static final LimitBreach INSTANCE = new LimitBreach();

        private LimitBreach() {
            super("span size lower bound breached", null, false, false);
        }
    }
}
