package space.br1440.platform.tracing.e2e.smoke;

import io.opentelemetry.api.trace.Span;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import space.br1440.platform.tracing.autoconfigure.servicename.PlatformRemoteServiceNameProvider;
import space.br1440.platform.tracing.core.mdc.remote.RemoteServiceMdc;

import java.util.concurrent.CountDownLatch;

/**
 * Эндпоинт G2-05: проверка OTel Context и {@code platform.remote.service} после {@code publishOn}.
 */
@RestController
class ReactorPropagationSmokeController {

    private static final String E2E_REMOTE_SERVICE = "upstream-e2e-g205";

    private final CountDownLatch servedLatch;
    private final PlatformRemoteServiceNameProvider remoteServiceNameProvider;

    ReactorPropagationSmokeController(CountDownLatch servedLatch,
                                    PlatformRemoteServiceNameProvider remoteServiceNameProvider) {
        this.servedLatch = servedLatch;
        this.remoteServiceNameProvider = remoteServiceNameProvider;
    }

    /**
     * Формат ответа: {@code callerTraceId|workerTraceId|workerRemoteService|workerThread}.
     */
    @GetMapping("/propagation-test")
    Mono<String> propagationTest() {
        String callerTraceId = currentTraceId();
        RemoteServiceMdc.putIfPresent(E2E_REMOTE_SERVICE, callerTraceId);

        return Mono.just(callerTraceId)
                .publishOn(Schedulers.parallel())
                .map(id -> {
                    String workerTraceId = currentTraceId();
                    String workerRemoteService = remoteServiceNameProvider.get().orElse(null);
                    String workerThread = Thread.currentThread().getName();
                    return id + '|' + workerTraceId + '|' + workerRemoteService + '|' + workerThread;
                })
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
