package space.br1440.platform.tracing.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import space.br1440.platform.tracing.autoconfigure.TracingRuntimeAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.diagnostics.TracingRuntimeDiagnosticsAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.status.TracingStatusAutoConfiguration;

import io.opentelemetry.api.trace.Span;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Tag("bridge-otel-path")
@AutoConfigureObservability
@SpringBootTest(
        classes = BridgeOtelReactorContextPropagationIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@TestPropertySource(properties = """
        platform.tracing.enabled=false
        platform.tracing.suppression.suppress-micrometer-http-observations=false
        spring.reactor.context-propagation=AUTO
        """)
class BridgeOtelReactorContextPropagationIntegrationTest {

    private static final String ENDPOINT = "/bridge-propagation-test";
    private static final String TRACEPARENT_HEADER = "traceparent";
    private static final String INCOMING_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String INCOMING_TRACEPARENT = "00-" + INCOMING_TRACE_ID + "-00f067aa0ba902b7-01";

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void publishOnRestoresServerTraceContextOnParallelWorker() {
        // given
        // when
        TraceSnapshot snapshot = webTestClient.get()
                .uri(ENDPOINT)
                .header(TRACEPARENT_HEADER, INCOMING_TRACEPARENT)
                .exchange()
                .expectStatus().isOk()
                .expectBody(TraceSnapshot.class)
                .returnResult()
                .getResponseBody();

        // then
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.callerTraceId())
                .as("server span must continue the incoming W3C trace")
                .isEqualTo(INCOMING_TRACE_ID);
        assertThat(snapshot.workerThread())
                .as("operator after publishOn must run on Schedulers.parallel()")
                .startsWith("parallel-");
        assertThat(snapshot.workerTraceId())
                .as("trace context must be restored on the parallel worker")
                .isEqualTo(snapshot.callerTraceId());
    }

    record TraceSnapshot(String callerTraceId, String workerTraceId, String workerThread) {
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
            TracingRuntimeAutoConfiguration.class,
            TracingRuntimeDiagnosticsAutoConfiguration.class,
            TracingStatusAutoConfiguration.class
    })
    @Import(TraceSnapshotController.class)
    static class TestApplication {
    }

    @RestController
    static class TraceSnapshotController {

        @GetMapping(ENDPOINT)
        Mono<TraceSnapshot> traceSnapshot() {
            String callerTraceId = currentTraceId();
            return Mono.just(callerTraceId)
                    .publishOn(Schedulers.parallel())
                    .map(caller -> new TraceSnapshot(caller, currentTraceId(), Thread.currentThread().getName()));
        }

        private static String currentTraceId() {
            return Span.current().getSpanContext().getTraceId();
        }
    }
}
