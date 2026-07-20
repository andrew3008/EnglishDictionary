package space.br1440.platform.tracing.e2e.smoke;

import io.opentelemetry.api.trace.Span;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import space.br1440.platform.tracing.api.mdc.RemoteServiceNameSource;
import space.br1440.platform.tracing.autoconfigure.reactive.RemoteServiceReactorContext;

import java.util.concurrent.CountDownLatch;

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

    ReactorPropagationSmokeController(CountDownLatch servedLatch,
                                    RemoteServiceNameSource remoteServiceNameSource) {
        this.servedLatch = servedLatch;
        this.remoteServiceNameSource = remoteServiceNameSource;
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

    private static String currentTraceId() {
        Span span = Span.current();
        if (span == null || !span.getSpanContext().isValid()) {
            return "";
        }
        return span.getSpanContext().getTraceId();
    }
}
