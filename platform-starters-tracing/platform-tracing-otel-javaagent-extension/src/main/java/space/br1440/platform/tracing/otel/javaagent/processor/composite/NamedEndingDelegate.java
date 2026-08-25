package space.br1440.platform.tracing.otel.javaagent.processor.composite;

import space.br1440.platform.tracing.otel.javaagent.processor.PlatformEndingSpanProcessor;

record NamedEndingDelegate(NamedDelegate namedDelegate, PlatformEndingSpanProcessor endingDelegate) {
}
