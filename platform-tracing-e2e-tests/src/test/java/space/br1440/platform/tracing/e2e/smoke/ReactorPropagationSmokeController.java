package space.br1440.platform.tracing.e2e.smoke;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.mdc.RemoteServiceNameSource;
import space.br1440.platform.tracing.autoconfigure.reactive.RemoteServiceReactorContext;
import space.br1440.platform.tracing.webflux.ReactiveCorrelationOperations;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuple4;

/**
 * Эндпоинт G2-05: проверка OTel Context и {@code platform.remote.service} после {@code publishOn}.
 * <p>
 * Получает {@link RemoteServiceNameSource} напрямую (Spring-bean
 * {@code ReactiveTracingAutoConfiguration#webFluxTraceMirrorSource}), а не через
 * {@code PlatformRemoteServiceNameProvider} — это устраняет coupling e2e-теста к
 * autoconfigure-internal bean (PR-3).
 */
@RestController
class ReactorPropagationSmokeController {

    private static final String E2E_REMOTE_SERVICE = "upstream-e2e-g205";

    private final CountDownLatch servedLatch;
    private final RemoteServiceNameSource remoteServiceNameSource;
    private final TraceOperations traceOperations;
    private final ReactiveCorrelationOperations correlationOperations;

    ReactorPropagationSmokeController(CountDownLatch servedLatch,
                                      RemoteServiceNameSource remoteServiceNameSource,
                                      TraceOperations traceOperations,
                                      ReactiveCorrelationOperations correlationOperations) {
        this.servedLatch = servedLatch;
        this.remoteServiceNameSource = remoteServiceNameSource;
        this.traceOperations = traceOperations;
        this.correlationOperations = correlationOperations;
    }

    /**
     * Формат ответа: {@code callerTraceId|workerTraceId|workerRemoteService|workerThread}.
     */
    @GetMapping("/propagation-test")
    Mono<String> propagationTest() {
        String callerTraceId = currentTraceId();

        return Mono.just(callerTraceId)
                .publishOn(Schedulers.parallel())
                .map(id -> {
                    String workerTraceId = currentTraceId();
                    String workerRemoteService = remoteServiceNameSource.resolve().orElse(null);
                    String workerThread = Thread.currentThread().getName();
                    return id + '|' + workerTraceId + '|' + workerRemoteService + '|' + workerThread;
                })
                .contextWrite(RemoteServiceReactorContext.contextWrite(E2E_REMOTE_SERVICE))
                .doOnSuccess(ignored -> servedLatch.countDown());
    }

    @GetMapping("/identity-reactive")
    Mono<String> identityReactive(@RequestParam("correlationId") String correlationId) {
        return Mono.defer(() -> {
            String requestId = traceOperations.traceContext().requestId().orElseThrow();
            boolean spoofRejected = traceOperations.traceContext().correlationId().isEmpty()
                    && Baggage.current().getEntryValue("platform.correlation.id") == null;
            String parentCorrelation = traceOperations.traceContext().correlationId().orElse("empty");

            Mono<String> primary = observeCorrelation(correlationId, "identity-reactive-" + correlationId);
            Mono<String> sibling = observeCorrelation(
                    correlationId + "-SIBLING",
                    "identity-reactive-" + correlationId + "-SIBLING");
            Mono<String> error = correlationOperations.withCorrelationId(
                    correlationId + "-ERROR",
                    Mono.<String>error(new IllegalStateException("expected"))
                            .onErrorResume(failure -> Mono.fromCallable(() ->
                                    traceOperations.traceContext().correlationId().orElse("missing"))));
            Mono<Void> cancelled = correlationOperations.withCorrelationId(
                            correlationId + "-CANCEL",
                            Mono.<Void>never())
                    .timeout(Duration.ofMillis(50))
                    .onErrorResume(failure -> Mono.empty());
            Mono<String> cancellationCleanup = cancelled.then(Mono.fromCallable(() ->
                    traceOperations.traceContext().correlationId().orElse("empty")));

            return Mono.zip(primary, sibling, error, cancellationCleanup)
                    .map(values -> response(
                            correlationId,
                            requestId,
                            spoofRejected,
                            parentCorrelation,
                            values));
        }).doFinally(ignored -> servedLatch.countDown());
    }

    private Mono<String> observeCorrelation(String correlationId, String spanName) {
        Mono<String> observation = Mono.fromCallable(() ->
                        traceOperations.traceContext().correlationId().orElse("missing"))
                .subscribeOn(Schedulers.boundedElastic())
                .publishOn(Schedulers.parallel())
                .map(beforeSwitch -> {
                    String afterSwitch = traceOperations.traceContext().correlationId().orElse("missing");
                    Span child = GlobalOpenTelemetry.getTracer("slice-m-reactive-e2e")
                            .spanBuilder(spanName)
                            .startSpan();
                    child.end();
                    return beforeSwitch + '~' + afterSwitch + '~' + Thread.currentThread().getName();
                });
        return correlationOperations.withCorrelationId(correlationId, observation);
    }

    private static String response(
            String correlationId,
            String requestId,
            boolean spoofRejected,
            String parentCorrelation,
            Tuple4<String, String, String, String> observations) {
        return correlationId + '|'
                + requestId + '|'
                + spoofRejected + '|'
                + parentCorrelation + '|'
                + observations.getT1() + '|'
                + observations.getT2() + '|'
                + observations.getT3() + '|'
                + observations.getT4();
    }

    private static String currentTraceId() {
        Span span = Span.current();
        if (span == null || !span.getSpanContext().isValid()) {
            return "";
        }
        return span.getSpanContext().getTraceId();
    }
}
