package space.br1440.platform.tracing.e2e.smoke;

import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CountDownLatch;

/**
 * Эндпоинт G2-MDC-e2e: лог с platform-logging text pattern и active span от Agent.
 */
@RestController
class MdcLoggingSmokeController {

    /** Маркер в stdout для парсинга e2e-тестом. */
    static final String LOG_MARKER = "MDC-E2E-MARKER";

    private static final Logger log = LoggerFactory.getLogger(MdcLoggingSmokeController.class);

    private final CountDownLatch servedLatch;

    MdcLoggingSmokeController(CountDownLatch servedLatch) {
        this.servedLatch = servedLatch;
    }

    @GetMapping("/mdc-test")
    String mdcTest() {
        String traceId = currentTraceId();
        log.info("{} activeTraceId={}", LOG_MARKER, traceId);
        servedLatch.countDown();
        return traceId;
    }

    private static String currentTraceId() {
        Span span = Span.current();
        if (span == null || !span.getSpanContext().isValid()) {
            return "";
        }
        return span.getSpanContext().getTraceId();
    }
}
