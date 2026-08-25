package space.br1440.platform.tracing.otel.javaagent.processor.dropoldest;

import io.opentelemetry.sdk.trace.data.SpanData;

record SequencedSpan(long sequence, SpanData snapshot) {
}
