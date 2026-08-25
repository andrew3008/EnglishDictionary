package space.br1440.platform.tracing.otel.javaagent.processor.composite;

import java.util.concurrent.atomic.AtomicLong;

import io.opentelemetry.sdk.trace.SpanProcessor;

record NamedDelegate(String name, SpanProcessor delegate, AtomicLong errorCount) {
}
