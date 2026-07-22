package space.br1440.platform.tracing.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuple2;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.autoconfigure.support.RequestIdentityBoundarySupport;
import space.br1440.platform.tracing.otel.facade.DefaultTraceOperations;
import space.br1440.platform.tracing.otel.runtime.NoOpTracingRuntime;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;
import space.br1440.platform.tracing.otel.runtime.otel.OtelTracingRuntimeFactory;
import space.br1440.platform.tracing.webflux.ReactiveCorrelationOperations;

class DefaultReactiveCorrelationOperationsTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private TracingRuntime runtime;
    private ReactiveIdentityContextPropagation propagation;
    private ReactiveCorrelationOperations operations;

    @BeforeAll
    static void enableAutomaticContextPropagation() {
        Hooks.enableAutomaticContextPropagation();
    }

    @BeforeEach
    void setUp() {
        runtime = NoOpTracingRuntime.disabledByConfiguration("reactive-test");
        RequestIdentityBoundarySupport boundary = new RequestIdentityBoundarySupport(runtime);
        propagation = new ReactiveIdentityContextPropagation(boundary);
        propagation.afterSingletonsInstantiated();
        operations = new DefaultReactiveCorrelationOperations(boundary);
    }

    @AfterEach
    void tearDown() throws Exception {
        propagation.destroy();
    }

    @Test
    void validatesArgumentsAtInvocationTime() {
        assertThatThrownBy(() -> operations.withCorrelationId(" invalid ", Mono.just("x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> operations.withCorrelationId(null, Mono.just("x")))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> operations.withCorrelationId("VALID", (Mono<String>) null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void coldMonoAndFluxSeePerSubscriptionValue() {
        Mono<String> read = Mono.deferContextual(context ->
                Mono.just(context.<ReactiveIdentityContextPropagation.IdentityState>get(
                        ReactiveIdentityContextPropagation.KEY).correlationId()));

        assertThat(operations.withCorrelationId("MONO", read).block(TIMEOUT)).isEqualTo("MONO");
        assertThat(operations.withCorrelationId("FLUX", Flux.range(1, 3)
                .flatMap(ignored -> read)).collectList().block(TIMEOUT))
                .containsExactly("FLUX", "FLUX", "FLUX");
    }

    @Test
    void retryRepeatAndNestedScopesKeepLexicalIsolation() {
        AtomicInteger attempts = new AtomicInteger();
        Mono<String> retrying = Mono.deferContextual(context -> {
            String value = context.<ReactiveIdentityContextPropagation.IdentityState>get(
                    ReactiveIdentityContextPropagation.KEY).correlationId();
            return attempts.getAndIncrement() == 0
                    ? Mono.error(new IllegalStateException(value))
                    : Mono.just(value);
        }).retry(1);
        Mono<String> read = Mono.deferContextual(context ->
                Mono.just(context.<ReactiveIdentityContextPropagation.IdentityState>get(
                        ReactiveIdentityContextPropagation.KEY).correlationId()));
        Mono<Tuple2<String, String>> nested = operations.withCorrelationId(
                "OUTER",
                Mono.zip(read, operations.withCorrelationId("INNER", read)));

        assertThat(operations.withCorrelationId("RETRY", retrying).block(TIMEOUT)).isEqualTo("RETRY");
        assertThat(operations.withCorrelationId("REPEAT", read.repeat(2))
                .collectList().block(TIMEOUT)).containsExactly("REPEAT", "REPEAT", "REPEAT");
        Tuple2<String, String> nestedValues = nested.block(TIMEOUT);
        assertThat(nestedValues.getT1()).isEqualTo("OUTER");
        assertThat(nestedValues.getT2()).isEqualTo("INNER");
    }

    @Test
    void concurrentSubscribersAndSchedulerSwitchesDoNotLeak() {
        Mono<String> readStore = Mono.fromCallable(() ->
                runtime.currentCorrelationId().orElse("none"));
        Mono<String> first = operations.withCorrelationId(
                "FIRST",
                readStore.publishOn(Schedulers.parallel()).subscribeOn(Schedulers.boundedElastic()));
        Mono<String> second = operations.withCorrelationId(
                "SECOND",
                readStore.publishOn(Schedulers.parallel()).subscribeOn(Schedulers.boundedElastic()));

        Tuple2<String, String> values = Mono.zip(first, second).block(TIMEOUT);

        assertThat(values.getT1()).isEqualTo("FIRST");
        assertThat(values.getT2()).isEqualTo("SECOND");
        assertThat(runtime.currentCorrelationId()).isEmpty();
    }

    @Test
    void errorAndCancellationRestoreBridgeState() {
        String onError = operations.withCorrelationId(
                "ERROR",
                Mono.<String>error(new IllegalStateException("boom"))
                        .onErrorResume(exception -> Mono.fromCallable(() ->
                                runtime.currentCorrelationId().orElse("none"))))
                .block(TIMEOUT);
        operations.withCorrelationId("CANCEL", Mono.never())
                .timeout(Duration.ofMillis(50))
                .onErrorResume(exception -> Mono.empty())
                .block(TIMEOUT);

        assertThat(onError).isEqualTo("ERROR");
        assertThat(runtime.currentCorrelationId()).isEmpty();
    }

    @Test
    void childSpanGetsCorrelationAtBirthWithoutMutatingExistingParent() throws Exception {
        propagation.destroy();
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        try {
            OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
            runtime = OtelTracingRuntimeFactory.create(sdk);
            TraceOperations traceOperations = new DefaultTraceOperations(runtime);
            RequestIdentityBoundarySupport boundary = new RequestIdentityBoundarySupport(runtime);
            propagation = new ReactiveIdentityContextPropagation(boundary);
            propagation.afterSingletonsInstantiated();
            operations = new DefaultReactiveCorrelationOperations(boundary);

            operations.withCorrelationId("REACTIVE-CHILD", Mono.fromRunnable(() ->
                    traceOperations.spans().operation("reactive-child").run(() -> { })))
                    .subscribeOn(Schedulers.parallel())
                    .block(TIMEOUT);

            List<SpanData> spans = exporter.getFinishedSpanItems();
            AttributeKey<String> key = AttributeKey.stringKey(PlatformAttributes.PLATFORM_CORRELATION_ID);
            assertThat(spans).hasSize(1);
            assertThat(spans.getFirst().getAttributes().get(key)).isEqualTo("REACTIVE-CHILD");
        } finally {
            provider.shutdown();
        }
    }
}
