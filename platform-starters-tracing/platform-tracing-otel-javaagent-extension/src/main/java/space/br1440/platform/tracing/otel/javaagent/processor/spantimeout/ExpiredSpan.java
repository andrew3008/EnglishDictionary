package space.br1440.platform.tracing.otel.javaagent.processor.spantimeout;

import io.opentelemetry.sdk.trace.ReadWriteSpan;

record ExpiredSpan(ReadWriteSpan span, String traceId, String spanId, TimeoutReason reason) {
}
