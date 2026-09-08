package space.br1440.platform.tracing.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import space.br1440.platform.tracing.api.mdc.TracingMdcKeys;
import space.br1440.platform.tracing.autoconfigure.identity.RequestIdentityBoundarySupport;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;
import space.br1440.platform.tracing.otel.runtime.otel.OtelTracingRuntimeFactory;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.Context;

class ReactiveIdentityMdcPropagationIntegrationTest {

    private SdkTracerProvider tracerProvider;
    private ReactiveIdentityContextPropagation propagation;

    @BeforeEach
    void setUp() {
        Hooks.enableAutomaticContextPropagation();

        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();
        TracingRuntime runtime = OtelTracingRuntimeFactory.create(openTelemetry);

        propagation = new ReactiveIdentityContextPropagation(new RequestIdentityBoundarySupport(runtime));
        propagation.afterSingletonsInstantiated();
    }

    @AfterEach
    void tearDown() {
        propagation.destroy();
        tracerProvider.shutdown();
        MDC.clear();
        Hooks.disableAutomaticContextPropagation();
    }

    @Test
    void identityKeysSurvivePublishOnAndSubscribeOn() {
        // given / when
        IdentityKeys onParallel = Mono.fromCallable(IdentityKeys::capture)
                .publishOn(Schedulers.parallel())
                .contextWrite(identityContext("request-42", "correlation-42"))
                .block(Duration.ofSeconds(5));

        IdentityKeys onBoundedElastic = Mono.fromCallable(IdentityKeys::capture)
                .subscribeOn(Schedulers.boundedElastic())
                .contextWrite(identityContext("request-42", "correlation-42"))
                .block(Duration.ofSeconds(5));

        // then
        assertThat(onParallel).isEqualTo(new IdentityKeys("request-42", "correlation-42"));
        assertThat(onBoundedElastic).isEqualTo(new IdentityKeys("request-42", "correlation-42"));
    }

    @Test
    void identityKeysSurviveRetryAfterFailure() {
        // given
        AtomicInteger attempts = new AtomicInteger();

        // when
        IdentityKeys captured = Mono.fromCallable(() -> {
                    if (attempts.getAndIncrement() == 0) {
                        throw new IllegalStateException("first attempt fails intentionally");
                    }
                    return IdentityKeys.capture();
                })
                .publishOn(Schedulers.parallel())
                .retry(1)
                .contextWrite(identityContext("request-retry", "correlation-retry"))
                .block(Duration.ofSeconds(5));

        // then
        assertThat(attempts.get()).isEqualTo(2);
        assertThat(captured).isEqualTo(new IdentityKeys("request-retry", "correlation-retry"));
    }

    private static Function<Context, Context> identityContext(String requestId, String correlationId) {
        return context -> context.put(
                ReactiveIdentityContextPropagation.KEY,
                new ReactiveIdentityContextPropagation.IdentityState(requestId, correlationId, false, false));
    }

    private record IdentityKeys(String requestId, String correlationId) {

        static IdentityKeys capture() {
            return new IdentityKeys(
                    MDC.get(TracingMdcKeys.REQUEST_ID),
                    MDC.get(TracingMdcKeys.CORRELATION_ID));
        }
    }
}
