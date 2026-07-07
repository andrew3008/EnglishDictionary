package space.br1440.platform.tracing.e2e.smoke;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CountDownLatch;

/**
 * Probe-эндпоинт для {@link AgentSpringForceSamplingSmokeMain} (отдельный класс для component scan).
 */
@RestController
class ProbeSmokeController {

    private final CountDownLatch servedLatch;

    ProbeSmokeController(CountDownLatch servedLatch) {
        this.servedLatch = servedLatch;
    }

    @GetMapping("/probe")
    String probe() {
        servedLatch.countDown();
        return "ok";
    }

    @GetMapping("/error")
    String error() {
        servedLatch.countDown();
        throw new RuntimeException("boom");
    }
}
