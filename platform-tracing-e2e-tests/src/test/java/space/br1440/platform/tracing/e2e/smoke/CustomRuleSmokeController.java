package space.br1440.platform.tracing.e2e.smoke;

import io.opentelemetry.api.trace.Span;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CountDownLatch;

/**
 * Probe-эндпоинт для {@link CustomRuleSmokeMain} (отдельный класс, чтобы не конфликтовать
 * с другими smoke-main при component scan в in-process {@code @SpringBootTest}).
 */
@RestController
class CustomRuleSmokeController {

    private final CountDownLatch servedLatch;

    CustomRuleSmokeController(CountDownLatch servedLatch) {
        this.servedLatch = servedLatch;
    }

    @GetMapping("/probe")
    String probe() {
        Span.current().setAttribute("e2e.custom.marker", "my-super-secret-value");
        servedLatch.countDown();
        return "ok";
    }
}
