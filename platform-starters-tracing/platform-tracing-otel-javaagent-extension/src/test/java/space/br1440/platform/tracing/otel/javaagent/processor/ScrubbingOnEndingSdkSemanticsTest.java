package space.br1440.platform.tracing.otel.javaagent.processor;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import jakarta.annotation.Nonnull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingRuntime;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.circuitbreaker.RuleCircuitBreaker;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.ExecutableRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.RuleOrigin;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.RuntimeRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.rules.BuiltInSpanAttributeScrubbingRules;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.internal.ExtendedSpanProcessor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P5, pinned SDK: фиксирует те свойства {@code onEnding}, на которые опирается гибридная схема —
 * span ещё изменяем в {@code onEnding}, все {@code onEnding}-делегаты завершаются до первого
 * {@code onEnd}, и потому scrubbing, стоящий последним в списке onEnding, действительно видит
 * записи всех предыдущих делегатов.
 */
class ScrubbingOnEndingSdkSemanticsTest {

    private static final AttributeKey<String> PASSWORD = AttributeKey.stringKey("db.password");

    @Test
    @DisplayName("Span written by a preceding onEnding delegate is still scrubbed before any onEnd runs")
    void onEndingIsMutableAndCompletesBeforeOnEnd() {
        List<String> callOrder = new CopyOnWriteArrayList<>();
        List<String> passwordSeenAtOnEnd = new CopyOnWriteArrayList<>();

        ScrubbingRuntime runtime = passwordRuntime();
        InMemorySpanExporter exporter = InMemorySpanExporter.create();

        List<SpanData> finished;
        try (SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(new LateWriter(callOrder))
                .addSpanProcessor(new ScrubbingSpanProcessor(runtime))
                .addSpanProcessor(new OnEndObserver(callOrder, passwordSeenAtOnEnd))
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()) {
            Tracer tracer = provider.get("onending-semantics");
            tracer.spanBuilder("op").startSpan().end();
            // InMemorySpanExporter.shutdown() очищает буфер, поэтому снимок берётся до close().
            finished = List.copyOf(exporter.getFinishedSpanItems());
        }

        assertThat(callOrder).containsExactly("writer.onEnding", "observer.onEnd");

        assertThat(finished).hasSize(1);
        assertThat(finished.get(0).getAttributes().get(PASSWORD))
                .as("an attribute written during onEnding must still be scrubbed before export")
                .isEmpty();
        assertThat(passwordSeenAtOnEnd)
                .as("onEnd observes the already sanitized value, so every onEnding ran first")
                .containsExactly("");
    }

    private static ScrubbingRuntime passwordRuntime() {
        SpanAttributeScrubbingRule password = BuiltInSpanAttributeScrubbingRules.resolve("password");
        return ScrubbingRuntime.create(
                List.of(new RuntimeRule(
                        new ExecutableRule(password, new RuleCircuitBreaker(password.name())), RuleOrigin.BUILT_IN)),
                1L, "test", null, false);
    }

    /** Пишет чувствительный атрибут в {@code onEnding} — до scrubbing-делегата. */
    private static final class LateWriter implements ExtendedSpanProcessor {

        private final List<String> callOrder;

        private LateWriter(List<String> callOrder) {
            this.callOrder = callOrder;
        }

        @Override
        public void onEnding(ReadWriteSpan span) {
            callOrder.add("writer.onEnding");
            span.setAttribute(PASSWORD, "supersecret");
        }

        @Override
        public boolean isOnEndingRequired() {
            return true;
        }

        @Override public void onStart(@Nonnull Context parentContext, @Nonnull ReadWriteSpan span) { }
        @Override public boolean isStartRequired() { return false; }
        @Override public void onEnd(@Nonnull ReadableSpan span) { }
        @Override public boolean isEndRequired() { return false; }
    }

    private static final class OnEndObserver implements ExtendedSpanProcessor {

        private final List<String> callOrder;
        private final List<String> passwordSeen;

        private OnEndObserver(List<String> callOrder, List<String> passwordSeen) {
            this.callOrder = callOrder;
            this.passwordSeen = passwordSeen;
        }

        @Override
        public void onEnd(@Nonnull ReadableSpan span) {
            callOrder.add("observer.onEnd");
            String value = span.toSpanData().getAttributes().get(PASSWORD);
            passwordSeen.add(value);
        }

        @Override
        public boolean isEndRequired() {
            return true;
        }

        @Override public void onStart(@Nonnull Context parentContext, @Nonnull ReadWriteSpan span) { }
        @Override public boolean isStartRequired() { return false; }
        @Override public void onEnding(ReadWriteSpan span) { }
        @Override public boolean isOnEndingRequired() { return false; }
    }
}
