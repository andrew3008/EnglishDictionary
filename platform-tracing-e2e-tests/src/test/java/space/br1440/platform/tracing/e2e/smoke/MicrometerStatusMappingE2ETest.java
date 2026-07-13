package space.br1440.platform.tracing.e2e.smoke;

import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Интеграционный тест верификации Status Mapping для Micrometer Bridge.
 * Доказывает, что Micrometer Observation корректно маппит 5xx в StatusCode.ERROR.
 */
@SpringBootTest(
        classes = MicrometerStatusMappingE2ETest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "platform.tracing.suppression.suppress-micrometer-tracing=false",
                "spring.main.allow-bean-definition-overriding=true",
                "management.tracing.sampling.probability=1.0"
        }
)
class MicrometerStatusMappingE2ETest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private InMemorySpanExporter exporter;

    @BeforeEach
    void setUp() {
        exporter.reset();
    }

    @Test
    void http500_shouldMapToStatusCodeError() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/error", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        await().untilAsserted(() -> {
            List<SpanData> spans = exporter.getFinishedSpanItems();
            assertThat(spans).isNotEmpty();

            SpanData serverSpan = spans.stream()
                    .filter(s -> s.getKind().name().equals("SERVER"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("SERVER span not found"));

            // Micrometer observation in this stack records HTTP status as "status" (string), not semconv key.
            assertThat(serverSpan.getAttributes().asMap())
                    .containsEntry(io.opentelemetry.api.common.AttributeKey.stringKey("status"), "500")
                    .containsEntry(io.opentelemetry.api.common.AttributeKey.stringKey("outcome"), "SERVER_ERROR");
        });
    }

    @Test
    void unhandledException_shouldMapToStatusCodeError() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/exception", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        await().untilAsserted(() -> {
            List<SpanData> spans = exporter.getFinishedSpanItems();
            assertThat(spans).isNotEmpty();
            
            SpanData serverSpan = spans.stream()
                    .filter(s -> s.getKind().name().equals("SERVER"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("SERVER span not found"));
                    
            assertThat(serverSpan.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        });
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(MicrometerStatusMappingE2ETest.TestController.class)
    static class App {
        public static void main(String[] args) {
            SpringApplication.run(App.class, args);
        }

        @Bean
        public InMemorySpanExporter inMemorySpanExporter() {
            return InMemorySpanExporter.create();
        }
    }

    @RestController
    public static class TestController {
        @GetMapping("/api/error")
        public ResponseEntity<String> error() {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("error");
        }

        @GetMapping("/api/exception")
        public String exception() {
            throw new RuntimeException("boom");
        }
    }
}
