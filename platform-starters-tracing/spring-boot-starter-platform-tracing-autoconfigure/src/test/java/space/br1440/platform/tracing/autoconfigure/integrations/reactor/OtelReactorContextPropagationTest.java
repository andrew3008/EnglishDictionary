package space.br1440.platform.tracing.autoconfigure.integrations.reactor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import reactor.core.Disposable;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

class OtelReactorContextPropagationTest {

    private SdkTracerProvider tracerProvider;
    private Tracer tracer;
    private OtelReactorContextPropagation propagation;

    @BeforeEach
    void setUp() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();

        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();

        tracer = tracerProvider.get("otel-reactor-propagation-test");

        Hooks.enableAutomaticContextPropagation();
        propagation = new OtelReactorContextPropagation();
        propagation.afterSingletonsInstantiated();
    }

    @AfterEach
    void tearDown() {
        propagation.destroy();
        tracerProvider.close();
    }

    @Test
    void publishOnPreservesRecordingSpan() {
        // given
        Span inbound = tracer.spanBuilder("inbound").startSpan();

        // when
        String workerTraceId;
        try (Scope ignored = inbound.makeCurrent()) {
            String inboundTraceId = Span.current().getSpanContext().getTraceId();
            workerTraceId = Mono.fromCallable(() -> Span.current().getSpanContext().getTraceId())
                    .publishOn(Schedulers.parallel())
                    .block();
            assertThat(inboundTraceId).isEqualTo(inbound.getSpanContext().getTraceId());
        } finally {
            inbound.end();
        }

        // then
        assertThat(workerTraceId).isEqualTo(inbound.getSpanContext().getTraceId());
    }

    @Test
    void sequentialHopsThenSecondRequestDoNotReuseEndedSpan() {
        // given
        Span first = tracer.spanBuilder("first-request").startSpan();
        String firstTraceId = first.getSpanContext().getTraceId();
        try (Scope ignored = first.makeCurrent()) {
            String afterHops = Mono.fromCallable(() -> "first")
                    .publishOn(Schedulers.parallel())
                    .flatMap(hop -> Mono.fromCallable(() -> Span.current().getSpanContext().getTraceId())
                            .publishOn(Schedulers.parallel()))
                    .block();
            assertThat(afterHops).isEqualTo(firstTraceId);
        }
        first.end();

        // when
        Span second = tracer.spanBuilder("second-request").startSpan();
        String secondTraceId;
        try (Scope ignored = second.makeCurrent()) {
            secondTraceId = Mono.fromCallable(() -> Span.current().getSpanContext().getTraceId())
                    .publishOn(Schedulers.parallel())
                    .block();
        } finally {
            second.end();
        }

        // then
        assertThat(secondTraceId).isEqualTo(second.getSpanContext().getTraceId());
        assertThat(secondTraceId).isNotEqualTo(firstTraceId);
    }

    @Test
    void accessorReturnsCurrentContextWhenRecording() {
        // given
        Span inbound = tracer.spanBuilder("snapshot").startSpan();
        OtelContextThreadLocalAccessor accessor = new OtelContextThreadLocalAccessor();

        // when
        Context snapshot;
        try (Scope ignored = inbound.makeCurrent()) {
            snapshot = accessor.getValue();
        } finally {
            inbound.end();
        }

        // then
        assertThat(snapshot).isNotNull();
        assertThat(accessor.getValue()).isNull();
    }

    @Test
    void emptySetValueDoesNotWipeRecordingSpan() {
        // given
        Span inbound = tracer.spanBuilder("inbound-keep").startSpan();

        // when
        String observed;
        try (Scope ignored = inbound.makeCurrent()) {
            OtelContextThreadLocalAccessor accessor = new OtelContextThreadLocalAccessor();
            accessor.setValue();
            observed = Span.current().getSpanContext().getTraceId();
            accessor.restore();
        } finally {
            inbound.end();
        }

        // then
        assertThat(observed).isEqualTo(inbound.getSpanContext().getTraceId());
        assertThat(observed).isNotEqualTo("00000000000000000000000000000000");
    }

    @Test
    void accessorRestoresNestedContexts() {
        // given
        ContextKey<String> owner = ContextKey.named("r1.outer");
        Span ended = tracer.spanBuilder("ended-outer").startSpan();
        ended.end();
        Context before = Context.current();
        Context outer = before.with(owner, "outer");
        Context inner = outer.with(ended);
        OtelContextThreadLocalAccessor accessor = new OtelContextThreadLocalAccessor();

        // when
        try {
            accessor.setValue(outer);
            accessor.setValue(inner);

            // then
            assertThat(accessor.getValue()).isSameAs(inner);
            accessor.restore(outer);
            assertThat(Context.current()).isSameAs(outer);
            assertThat(Context.current().get(owner)).isEqualTo("outer");
            accessor.restore(before);
            assertThat(Context.current()).isSameAs(before);
        } finally {
            accessor.clearScopes();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void nonRecordingContextSurvivesHandoff(boolean sampled) {
        // given
        ContextKey<String> owner = ContextKey.named("r1.nonrecording");
        SpanContext remote = SpanContext.create(
                "4bf92f3577b34da6a3ce929d0e0e4736",
                "00f067aa0ba902b7",
                sampled ? TraceFlags.getSampled() : TraceFlags.getDefault(),
                TraceState.getDefault()
        );
        Span wrapped = Span.wrap(remote);
        Context current = Context.current().with(wrapped).with(owner, "outer");
        Thread caller = Thread.currentThread();

        // when
        Context observed;
        try (Scope ignored = current.makeCurrent()) {
            observed = Mono.just(true)
                    .publishOn(Schedulers.parallel())
                    .map(value -> {
                        assertThat(Thread.currentThread()).isNotSameAs(caller);
                        return Context.current();
                    })
                    .block(Duration.ofSeconds(5));
        }

        // then
        assertThat(observed).isNotNull();
        assertThat(Span.fromContext(observed).getSpanContext()).isEqualTo(remote);
        assertThat(Span.fromContext(observed).isRecording()).isFalse();
        assertThat(observed.get(owner)).isEqualTo("outer");
    }

    @Test
    void cancellationDoesNotLeakContextToNextTask() throws Exception {
        // given
        CompletableFuture<Context> onWorker = new CompletableFuture<>();
        CompletableFuture<Void> cancelled = new CompletableFuture<>();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Scheduler worker = Schedulers.fromExecutorService(executor);
            Span first = tracer.spanBuilder("cancel-first").startSpan();
            try {
                // when
                Context observed;
                try (Scope ignored = first.makeCurrent()) {
                    Disposable subscription = Mono.just(true)
                            .publishOn(worker)
                            .flatMap(value -> {
                                onWorker.complete(Context.current());
                                return Mono.never();
                            })
                            .doOnCancel(() -> cancelled.complete(null))
                            .subscribe(value -> { }, failure -> {
                                onWorker.completeExceptionally(failure);
                                cancelled.completeExceptionally(failure);
                            });
                    try {
                        observed = onWorker.get(5, TimeUnit.SECONDS);
                    } finally {
                        subscription.dispose();
                    }
                }
                cancelled.get(5, TimeUnit.SECONDS);
                Context remaining = executor.submit(Context::current).get(5, TimeUnit.SECONDS);

                // then
                assertThat(Span.fromContext(observed).getSpanContext()).isEqualTo(first.getSpanContext());
                assertThat(remaining).isSameAs(Context.root());
            } finally {
                first.end();
                worker.dispose();
            }
        }
    }
}
