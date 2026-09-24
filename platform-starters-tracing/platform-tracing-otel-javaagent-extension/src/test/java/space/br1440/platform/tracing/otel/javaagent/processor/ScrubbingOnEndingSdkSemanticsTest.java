package space.br1440.platform.tracing.otel.javaagent.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import jakarta.annotation.Nonnull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntimeTestFixtures;
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

class ScrubbingOnEndingSdkSemanticsTest {

    private static final AttributeKey<String> PASSWORD = AttributeKey.stringKey("db.password");

    @Test
    @DisplayName("Span written by a preceding onEnding delegate is still scrubbed before any onEnd runs")
    void onEndingIsMutableAndCompletesBeforeOnEnd() {
        // given
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
            // when
            tracer.spanBuilder("op").startSpan().end();
            finished = List.copyOf(exporter.getFinishedSpanItems());
        }

        // then
        assertThat(callOrder).containsExactly("writer.onEnding", "observer.onEnd");

        assertThat(finished).hasSize(1);
        assertThat(finished.getFirst().getAttributes().get(PASSWORD))
                .as("an attribute written during onEnding must still be scrubbed before export")
                .isEmpty();
        assertThat(passwordSeenAtOnEnd)
                .as("onEnd observes the already sanitized value, so every onEnding ran first")
                .containsExactly("");
    }

    private static ScrubbingRuntime passwordRuntime() {
        SpanAttributeScrubbingRule password = BuiltInSpanAttributeScrubbingRules.PASSWORD_KEY.create();
        return ScrubbingRuntimeTestFixtures.create(
                List.of(
                        new RuntimeRule(
                                new ExecutableRule(password, new RuleCircuitBreaker(password.name())),
                                RuleOrigin.BUILT_IN
                        )
                ),
                1L, null, false);
    }

    private record LateWriter(List<String> callOrder) implements ExtendedSpanProcessor {

        @Override
            public void onEnding(ReadWriteSpan span) {
                callOrder.add("writer.onEnding");
                span.setAttribute(PASSWORD, "supersecret");
            }

            @Override
            public boolean isOnEndingRequired() {
                return true;
            }

            @Override
            public void onStart(@Nonnull Context parentContext, @Nonnull ReadWriteSpan span) {
            }

            @Override
            public boolean isStartRequired() {
                return false;
            }

            @Override
            public void onEnd(@Nonnull ReadableSpan span) {
            }

            @Override
            public boolean isEndRequired() {
                return false;
            }
        }

    private record OnEndObserver(List<String> callOrder, List<String> passwordSeen) implements ExtendedSpanProcessor {

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

            @Override
            public void onStart(@Nonnull Context parentContext, @Nonnull ReadWriteSpan span) {
            }

            @Override
            public boolean isStartRequired() {
                return false;
            }

            @Override
            public void onEnding(ReadWriteSpan span) {
            }

            @Override
            public boolean isOnEndingRequired() {
                return false;
            }
        }
}
