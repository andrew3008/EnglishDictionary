package space.br1440.platform.tracing.e2e.smoke;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;

import space.br1440.platform.tracing.e2e.support.AgentHttpSpringSmokeProcessRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Packaged Agent E2E (план §6.5): доказывает реальную активацию platform baggage customizer в
 * Agent {@code ExtensionClassLoader} и корректный W3C outbound wire encoding через independent
 * raw JDK {@code HttpServer} receiver (без OTel API на стороне observer).
 *
 * <p>Дополняет, но не заменяет {@code PlatformSpiAgentSmokeTest}: тот доказывает named
 * sampler/control-propagator discovery, этот тест — outbound baggage callback и wire-байты.
 *
 * <p>Single-variable constraint (§6.5): positive и negative прогоны обязаны иметь идентичную
 * Spring/JVM-конфигурацию, кроме {@code otel.javaagent.extensions}. Текущий
 * {@code ControlledAgentSpringFixture.addSdkAutoConfigurationExclusion(...)} добавляет
 * {@code spring.autoconfigure.exclude=...} только когда {@code extensionLocation} не пуст, поэтому
 * этот тест передаёт тот же exclusion-набор явно через {@code extraJvmSystemProperties} в обоих
 * прогонах, минуя эту особенность package-private fixture.
 */
@DisabledIfSystemProperty(named = "skipE2e", matches = "true",
        disabledReason = "Smoke-тесты пропущены через -DskipE2e=true")
class OutboundBaggageAgentE2ETest {

    private static final String SERVICE_NAME = "outbound-baggage-agent-smoke";
    private static final Duration AGENT_PROCESS_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration RECEIVER_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Идентичен {@code ControlledAgentSpringFixture.BOOT_OTEL_SDK_AUTO_CONFIGURATIONS}: явно
     * дублируется здесь, чтобы positive и negative прогоны получали одинаковый
     * {@code spring.autoconfigure.exclude} независимо от package-private conditional поведения
     * fixture (см. §6.5 плана — единственной переменной между прогонами обязан быть
     * {@code otel.javaagent.extensions}).
     */
    private static final String BOOT_OTEL_SDK_AUTO_CONFIGURATIONS = String.join(",",
            "org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.logging.OpenTelemetryLoggingAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.logging.otlp.OtlpLoggingAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryTracingAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpTracingAutoConfiguration");

    private static final TextMapGetter<Map<String, String>> RAW_HEADER_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    };

    @Test
    void positive_run_с_extension_фильтрует_allowlist_и_кодирует_percent20() throws Exception {
        RawBaggageReceiver receiver = new RawBaggageReceiver();
        try {
            RunOutcome outcome = runChild(receiver, true);

            assertThat(outcome.receiverHeader())
                    .as("receiver обязан получить raw baggage header от positive run; child output:\n%s",
                            outcome.childOutput())
                    .isNotNull();
            String header = outcome.receiverHeader();
            // Постоянный causal marker для Slice 5 evidence report (не только assertion).
            System.out.println("OUTBOUND_BAGGAGE_EVIDENCE positive raw_header=" + header);
            outcome.childOutput().lines()
                    .filter(line -> line.startsWith("SMOKE_JVM_PROPERTIES="))
                    .forEach(line -> System.out.println("OUTBOUND_BAGGAGE_EVIDENCE positive " + line));

            assertThat(header)
                    .as("allowlisted key закодирован через stock W3C %%20 (не '+'); raw header=%s", header)
                    .contains("tenant_class=DF%2028")
                    .doesNotContain("DF+28");
            assertThat(header)
                    .as("denied key отсутствует в сыром заголовке целиком; raw header=%s", header)
                    .doesNotContain("internal_secret")
                    .doesNotContain("leak");

            Context extracted = W3CBaggagePropagator.getInstance()
                    .extract(Context.root(), Map.of("baggage", header), RAW_HEADER_GETTER);
            assertThat(Baggage.fromContext(extracted).getEntryValue("tenant_class"))
                    .as("stock extraction обязана вернуть исходное значение без изменения")
                    .isEqualTo("DF 28");
        } finally {
            receiver.close();
        }
    }

    @Test
    void negative_control_run_без_extension_отдаёт_unfiltered_stock_encoded_header() throws Exception {
        RawBaggageReceiver receiver = new RawBaggageReceiver();
        try {
            RunOutcome outcome = runChild(receiver, false);

            String header = outcome.receiverHeader();
            assertThat(header)
                    .as("negative control обязан получить полноценный stock-encoded baggage header, "
                            + "а не отсутствие заголовка (единственный причинный сигнал 'customizer не сработал'); "
                            + "child output:\n%s", outcome.childOutput())
                    .isNotNull();
            // Постоянный causal marker для Slice 5 evidence report (не только assertion).
            System.out.println("OUTBOUND_BAGGAGE_EVIDENCE negative raw_header=" + header);
            outcome.childOutput().lines()
                    .filter(line -> line.startsWith("SMOKE_JVM_PROPERTIES="))
                    .forEach(line -> System.out.println("OUTBOUND_BAGGAGE_EVIDENCE negative " + line));

            // then: без platform extension фильтрация не применяется — оба ключа присутствуют
            // с тем же stock %20-кодированием, что и в positive run.
            assertThat(header)
                    .as("negative run обязан содержать И allowed, И denied ключи без фильтрации; raw header=%s", header)
                    .contains("tenant_class=DF%2028")
                    .contains("internal_secret");
        } finally {
            receiver.close();
        }
    }

    private RunOutcome runChild(RawBaggageReceiver receiver, boolean withExtension) throws Exception {
        String otelAgentJar = System.getProperty("otel.javaagent.jar");
        String extensionJar = System.getProperty("smoke.otel.extension.jar");
        String testRuntimeClasspath = System.getProperty("smoke.test.runtime.classpath");
        assertThat(otelAgentJar).isNotBlank();
        assertThat(extensionJar).isNotBlank();
        assertThat(testRuntimeClasspath).isNotBlank();
        assertThat(new File(otelAgentJar)).exists().isFile();
        assertThat(new File(extensionJar)).exists().isFile();

        int httpPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            httpPort = socket.getLocalPort();
        }

        String childOutput = AgentHttpSpringSmokeProcessRunner.run(
                AgentSpringOutboundBaggageSmokeMain.class.getName(),
                otelAgentJar,
                testRuntimeClasspath,
                "http://127.0.0.1:1",
                SERVICE_NAME,
                httpPort,
                withExtension ? extensionJar : "",
                "/probe",
                Map.of(),
                Map.of(),
                List.of(
                        "otel.traces.exporter=none",
                        "otel.propagators=tracecontext,baggage",
                        "platform.tracing.javaagent.propagation.baggage.allowed-keys=tenant_class",
                        // явный exclusion в обоих прогонах: single-variable constraint (см. class Javadoc).
                        "spring.autoconfigure.exclude=" + BOOT_OTEL_SDK_AUTO_CONFIGURATIONS,
                        "smoke.baggage.receiver.url=" + receiver.uri()),
                AGENT_PROCESS_TIMEOUT,
                2_000L);

        String header = receiver.awaitHeader(RECEIVER_TIMEOUT);
        return new RunOutcome(childOutput, header);
    }

    private record RunOutcome(String childOutput, String receiverHeader) {
    }

    /**
     * Независимый observer сырых wire-байтов: читает {@code baggage} заголовок как есть, без
     * использования OTel API — единственный способ доказать реальный wire-encoding (§6.5 плана).
     */
    private static final class RawBaggageReceiver implements AutoCloseable {

        private final HttpServer server;
        private final AtomicReference<String> header = new AtomicReference<>();
        private final CountDownLatch received = new CountDownLatch(1);

        RawBaggageReceiver() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/receive", this::handle);
            server.setExecutor(Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "outbound-baggage-e2e-receiver");
                thread.setDaemon(true);
                return thread;
            }));
            server.start();
        }

        String uri() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/receive";
        }

        String awaitHeader(Duration timeout) throws InterruptedException {
            received.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return header.get();
        }

        private void handle(HttpExchange exchange) throws IOException {
            try {
                header.set(exchange.getRequestHeaders().getFirst("baggage"));
                exchange.sendResponseHeaders(200, -1);
            } finally {
                exchange.close();
                received.countDown();
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
