package space.br1440.platform.tracing.autoconfigure.reactive;

import io.opentelemetry.api.trace.Span;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * In-process smoke для bridge-otel Reactor propagation path.
 * <p>
 * <b>Tests bridge-otel path ONLY.</b> Agent path covered by E2E G2-05-e2e
 * ({@code ReactorContextPropagationAgentE2ETest}). Green здесь — не substitute для sign-off.
 */
@Tag("bridge-otel-path")
@SpringBootTest(
        classes = BridgeOtelReactorContextPropagationIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "platform.tracing.enabled=true",
                "platform.tracing.suppression.suppress-micrometer-tracing=false",
                "spring.reactor.context-propagation=AUTO"
        })
class BridgeOtelReactorContextPropagationIntegrationTest {

    @LocalServerPort
    private int port;

    @Test
    void publishOn_сохраняет_traceId_через_bridge_otel_path() {
        WebTestClient client = WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port)
                .build();

        client.get()
                .uri("/bridge-propagation-test")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> {
                    String[] parts = body.split("\\|", -1);
                    assertThat(parts).hasSize(2);
                    assertThat(parts[0]).isNotBlank();
                    assertThat(parts[1]).isEqualTo(parts[0]);
                });
    }

    @SpringBootApplication
    static class TestApplication {

        @Bean
        BridgePropagationController bridgePropagationController() {
            return new BridgePropagationController();
        }
    }

    @RestController
    static class BridgePropagationController {

        @GetMapping("/bridge-propagation-test")
        Mono<String> bridgePropagationTest() {
            String callerTraceId = Span.current().getSpanContext().getTraceId();
            return Mono.fromCallable(() -> Span.current().getSpanContext().getTraceId())
                    .publishOn(Schedulers.parallel())
                    .map(workerTraceId -> callerTraceId + '|' + workerTraceId);
        }
    }
}
