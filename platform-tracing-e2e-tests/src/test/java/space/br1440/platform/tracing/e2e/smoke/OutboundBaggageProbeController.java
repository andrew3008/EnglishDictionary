package space.br1440.platform.tracing.e2e.smoke;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CountDownLatch;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.BaggageEntryMetadata;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

/**
 * Probe-эндпоинт для {@link AgentSpringOutboundBaggageSmokeMain} (отдельный класс для component scan).
 *
 * <p>Кладёт в текущий {@link Context} один allowlisted ({@code tenant_class}, с metadata) и один
 * denied ({@code internal_secret}) baggage entry, затем вызывает локальный test-only receiver
 * через Agent-instrumented {@link HttpClient} — реальный outbound wire baggage header формируется
 * платформенным customizer'ом внутри {@code ExtensionClassLoader}, а не тестовым кодом.
 */
@RestController
class OutboundBaggageProbeController {

    private final CountDownLatch servedLatch;
    private final String receiverUrl;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    OutboundBaggageProbeController(
            CountDownLatch servedLatch,
            @Value("${smoke.baggage.receiver.url}") String receiverUrl) {
        this.servedLatch = servedLatch;
        this.receiverUrl = receiverUrl;
    }

    @GetMapping("/probe")
    String probe() throws Exception {
        try {
            Baggage baggage = Baggage.builder()
                    .put("tenant_class", "DF 28", BaggageEntryMetadata.create("verified=true"))
                    .put("internal_secret", "leak")
                    .build();
            try (Scope ignored = baggage.storeInContext(Context.current()).makeCurrent()) {
                HttpRequest request = HttpRequest.newBuilder(URI.create(receiverUrl)).GET().build();
                httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            }
            return "ok";
        } finally {
            servedLatch.countDown();
        }
    }
}
