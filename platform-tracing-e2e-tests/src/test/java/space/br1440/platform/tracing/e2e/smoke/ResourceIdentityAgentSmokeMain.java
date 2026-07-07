package space.br1440.platform.tracing.e2e.smoke;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;

/**
 * Минимальный эмиттер одного span'а для smoke-теста resource-идентичности (Фаза 9).
 * <p>
 * Запускается в отдельной JVM под {@code -javaagent} + {@code platform-tracing-otel-extension}.
 * Идентичность ({@code service.name}/{@code version}/{@code environment}/{@code c_group}) задаётся
 * через {@code -Dplatform.tracing.service.*} и собирается {@code PlatformResourceProvider};
 * этот main лишь создаёт span, чтобы Resource долетел до Jaeger.
 *
 * <p>Аргументы: {@code [flushDelayMs]}.
 */
public final class ResourceIdentityAgentSmokeMain {

    private ResourceIdentityAgentSmokeMain() {
    }

    public static void main(String[] args) throws Exception {
        long flushDelayMs = args.length >= 1 ? Long.parseLong(args[0]) : 4_000L;

        Tracer tracer = GlobalOpenTelemetry.getTracer("resource-identity-smoke");
        Span span = tracer.spanBuilder("resource-smoke-op")
                .setAttribute("platform.trace.type", "internal")
                .setAttribute("platform.trace.result", "success")
                .startSpan();
        span.end();

        // Даём BatchSpanProcessor Agent'а время экспортировать span (с Resource) в OTLP/Jaeger.
        Thread.sleep(flushDelayMs);
    }
}
