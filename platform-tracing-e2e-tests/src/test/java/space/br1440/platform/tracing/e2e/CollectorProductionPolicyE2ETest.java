package space.br1440.platform.tracing.e2e;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.attributes.PlatformSamplingReasons;
import space.br1440.platform.tracing.e2e.support.JaegerTestContainerSupport;
import space.br1440.platform.tracing.e2e.support.JaegerV3QueryClient;
import space.br1440.platform.tracing.e2e.support.OtelCollectorTestContainerSupport;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * E2e-паритет с production (Фаза 16, PR-4): Collector поднимается с НАСТОЯЩИМ
 * production gateway YAML ({@code otel-collector-gateway-tail-sampling.yaml} из артефакта
 * {@code platform-tracing-collector-config}), а не с упрощённой e2e-копией.
 * <p>
 * Именно отсутствие такого паритета позволило P0-багу «x_trace_on» жить незамеченным:
 * e2e-конфиг содержал правильные значения, а production — нет (доказано Spike S3 на Gentoo).
 * Здесь конфиг тот же самый файл; различия — только env-переопределения
 * ({@code TAIL_SAMPLING_DECISION_WAIT=5s} для скорости, {@code SUCCESS_BASELINE_PERCENT=0}
 * для детерминизма) и Jaeger-endpoint. Collector запускается с feature gate
 * {@code +processor.tailsamplingprocessor.recordpolicy} — как в production (см. README конфига).
 *
 * <h2>Сценарии (вердикты Spike S3/S2, формализованные в тест)</h2>
 * <ol>
 *   <li>health-check OK-span дропается политикой {@code drop-successful-infra-noise};</li>
 *   <li>health-check ERROR-span сохраняется (drop только not-error);</li>
 *   <li>{@code platform.trace.priority=high} сохраняется ({@code platform-high-priority});</li>
 *   <li>transform backstop: {@code platform.trace.type} backfill по SpanKind и удаление
 *       {@code url.full} на SERVER-span'ах;</li>
 *   <li>redaction (2-я линия): JWT в значении атрибута маскируется до Jaeger;</li>
 *   <li>P0-регресс: span с {@code platform.sampling.reason=force_header} сохраняется при
 *       baseline=0% политикой {@code forced-traces} (доказательство Spike S3 #4).</li>
 * </ol>
 */
@Testcontainers
@DisabledIfSystemProperty(named = "skipE2e", matches = "true",
        disabledReason = "E2E-тесты пропущены через -DskipE2e=true")
class CollectorProductionPolicyE2ETest {

    private static final String SERVICE_NAME = "platform-tracing-prod-policy-e2e";
    /** Production gateway YAML — classpath-ресурс артефакта platform-tracing-collector-config. */
    private static final String PRODUCTION_GATEWAY_YAML =
            "platform-tracing/collector/otel-collector-gateway-tail-sampling.yaml";
    /** Переопределённый через env decision_wait (production default 10s). */
    private static final Duration DECISION_WAIT = Duration.ofSeconds(5);
    private static final Duration TRACE_VISIBILITY_TIMEOUT = DECISION_WAIT.plusSeconds(25);

    /** Синтетический JWT для проверки redaction: три base64url-сегмента, начинается с eyJ. */
    private static final String SYNTHETIC_JWT =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
                    + ".eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6InRlc3QifQ"
                    + ".TJVA95OrM7E2cBab30RMHrHDcEfxjoYZgeFONFh7HgQ";

    private static Network network;
    private static GenericContainer<?> jaeger;
    private static GenericContainer<?> collector;
    private static OpenTelemetrySdk sdk;
    private static Tracer tracer;
    private static JaegerV3QueryClient jaegerClient;

    @BeforeAll
    static void setUpStack() {
        network = Network.newNetwork();

        jaeger = JaegerTestContainerSupport.newJaeger(network);
        jaeger.start();

        // ВАЖНО: монтируется production YAML из модуля collector-config (тот же файл, что
        // уходит SRE), а не копия. Env-переопределения работают благодаря синтаксису
        // ${env:VAR:-default}; легаси-форма ${VAR:default} на 0.123+ ломала парсер (Spike S3).
        collector = new GenericContainer<>(DockerImageName.parse("otel/opentelemetry-collector-contrib:0.154.0"))
                .withNetwork(network)
                .withNetworkAliases("otel-collector-prod")
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource(PRODUCTION_GATEWAY_YAML),
                        "/etc/otelcol-contrib/config.yaml")
                // Feature gate recordpolicy — как в production (аудит через метрики
                // otelcol_processor_tail_sampling_count_traces_sampled; span-атрибут
                // tailsampling.policy не ассертим через Jaeger v3 API — нестабилен в e2e).
                .withCommand(
                        "--feature-gates=+processor.tailsamplingprocessor.recordpolicy",
                        "--config=/etc/otelcol-contrib/config.yaml")
                .withEnv(Map.of(
                        "OTEL_EXPORTER_OTLP_ENDPOINT",
                        JaegerTestContainerSupport.jaegerOtlpGrpcEndpointOnNetwork(jaeger),
                        "TAIL_SAMPLING_DECISION_WAIT", DECISION_WAIT.toSeconds() + "s",
                        // Детерминизм: только «положительные» политики, без шума probabilistic.
                        "TAIL_SAMPLING_SUCCESS_BASELINE_PERCENT", "0"))
                .withExposedPorts(4318, 13133)
                .waitingFor(Wait.forHttp("/").forPort(13133).withStartupTimeout(Duration.ofMinutes(2)))
                .dependsOn(jaeger);
        collector.start();

        String collectorEndpoint = OtelCollectorTestContainerSupport.otlpHttpTracesEndpointFromHost(collector);
        jaegerClient = new JaegerV3QueryClient(JaegerTestContainerSupport.queryBaseUrl(jaeger));

        sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .setResource(Resource.getDefault().toBuilder()
                                .put(AttributeKey.stringKey("service.name"), SERVICE_NAME)
                                .build())
                        .addSpanProcessor(BatchSpanProcessor.builder(
                                        OtlpHttpSpanExporter.builder()
                                                .setEndpoint(collectorEndpoint)
                                                .setTimeout(Duration.ofMillis(500))
                                                .build())
                                .setScheduleDelay(Duration.ofMillis(200))
                                .setMaxExportBatchSize(64)
                                .build())
                        .build())
                .build();
        tracer = sdk.getTracer("platform-tracing-prod-policy-e2e");
    }

    @AfterAll
    static void tearDownStack() {
        if (sdk != null) {
            sdk.getSdkTracerProvider().shutdown().join(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        if (collector != null) {
            collector.stop();
        }
        if (jaeger != null) {
            jaeger.stop();
        }
        if (network != null) {
            network.close();
        }
    }

    @Test
    void health_check_ok_дропается_production_drop_политикой() throws Exception {
        // Сценарий 1: drop-successful-infra-noise (production-политика, не probabilistic=0%
        // как в старом e2e-конфиге) отбрасывает успешный health-check.
        String operationName = "prod-health-ok-" + UUID.randomUUID();
        emitSpan(operationName, span -> {
            span.setAttribute(AttributeKey.stringKey("http.route"), "/actuator/health");
            span.setStatus(StatusCode.OK);
        });

        awaitDecisionWindow();
        assertThat(findOperation(operationName))
                .as("успешный health-check обязан быть отброшен drop-successful-infra-noise")
                .isFalse();
    }

    @Test
    void health_check_error_сохраняется_несмотря_на_drop_политику() {
        // Сценарий 2: not-error sub-policy гарантирует, что ERROR health-check выживает.
        String operationName = "prod-health-error-" + UUID.randomUUID();
        emitSpan(operationName, span -> {
            span.setAttribute(AttributeKey.stringKey("http.route"), "/actuator/health");
            span.setStatus(StatusCode.ERROR, "health degraded");
        });

        await().atMost(TRACE_VISIBILITY_TIMEOUT)
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> assertThat(findOperation(operationName))
                        .as("ERROR health-check обязан сохраниться (errors > drop-policy)")
                        .isTrue());
    }

    @Test
    void platform_high_priority_сохраняется() {
        // Сценарий 3: бизнес-приоритет SDK (ClassificationSpanProcessor) уважается
        // политикой platform-high-priority — при baseline=0% выживание докажет именно её.
        String operationName = "prod-priority-high-" + UUID.randomUUID();
        emitSpan(operationName, span -> {
            span.setAttribute(AttributeKey.stringKey(PlatformAttributes.PLATFORM_TRACE_PRIORITY), "high");
            span.setStatus(StatusCode.OK);
        });

        await().atMost(TRACE_VISIBILITY_TIMEOUT)
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> assertThat(findOperation(operationName))
                        .as("span с platform.trace.priority=high обязан сохраниться")
                        .isTrue());
    }

    @Test
    void transform_backstop_заполняет_trace_type_и_удаляет_url_full() {
        // Сценарий 4: transform/platform-semconv-backstop — backfill platform.trace.type
        // по SpanKind и удаление url.full на SERVER-span'ах (PII в query string).
        String operationName = "prod-backstop-" + UUID.randomUUID();
        emitSpan(operationName, span -> {
            // platform.trace.type сознательно НЕ проставляем — его обязан заполнить backstop.
            span.setAttribute(AttributeKey.stringKey("url.full"),
                    "https://platform.example/api?token=should-not-leak");
            span.setAttribute(AttributeKey.stringKey(PlatformAttributes.PLATFORM_SAMPLING_REASON),
                    PlatformSamplingReasons.FORCE_HEADER);
            span.setStatus(StatusCode.OK);
        });

        await().atMost(TRACE_VISIBILITY_TIMEOUT)
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    Optional<Map<String, String>> attrs = findSpanAttributes(operationName);
                    assertThat(attrs).as("backstop-span обязан дойти до Jaeger").isPresent();
                    assertThat(attrs.get())
                            .as("backstop обязан заполнить platform.trace.type по SpanKind=SERVER")
                            .containsEntry(PlatformAttributes.PLATFORM_TYPE, "http_server");
                    assertThat(attrs.get())
                            .as("url.full на SERVER-span'е обязан быть удалён backstop'ом (PII)")
                            .doesNotContainKey("url.full");
                });
    }

    @Test
    void redaction_маскирует_jwt_в_значении_атрибута() {
        // Сценарий 5: redaction/platform-second-line (Spike S2 GO) — JWT в значении
        // произвольного атрибута маскируется до экспорта в Jaeger. Первая линия (SDK
        // ScrubbingSpanProcessor) здесь сознательно не используется — проверяем именно
        // Collector-слой на «сыром» span'е.
        String operationName = "prod-redaction-" + UUID.randomUUID();
        emitSpan(operationName, span -> {
            span.setAttribute(AttributeKey.stringKey("test.token"), SYNTHETIC_JWT);
            span.setAttribute(AttributeKey.stringKey("test.safe"), "plain-value");
            span.setAttribute(AttributeKey.stringKey(PlatformAttributes.PLATFORM_SAMPLING_REASON),
                    PlatformSamplingReasons.FORCE_HEADER);
            span.setStatus(StatusCode.OK);
        });

        await().atMost(TRACE_VISIBILITY_TIMEOUT)
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    Optional<Map<String, String>> attrs = findSpanAttributes(operationName);
                    assertThat(attrs).as("redaction-span обязан дойти до Jaeger").isPresent();
                    assertThat(attrs.get().get("test.token"))
                            .as("JWT обязан быть замаскирован redaction-процессором")
                            .isNotEqualTo(SYNTHETIC_JWT)
                            .doesNotContain("eyJ");
                    assertThat(attrs.get())
                            .as("легитимные значения не должны затрагиваться redaction'ом")
                            .containsEntry("test.safe", "plain-value");
                });
    }

    @Test
    void force_header_сохраняется_при_baseline_0_процентов() {
        // Сценарий 6 (P0-регресс Spike S3 #4): до Фазы 16 production-политика forced-traces
        // ссылалась на несуществующий x_trace_on, и forced-span при baseline=0% ДРОПАЛСЯ.
        // Этот тест на том же production YAML доказывает исправление.
        String operationName = "prod-forced-" + UUID.randomUUID();
        emitSpan(operationName, span -> {
            span.setAttribute(AttributeKey.stringKey(PlatformAttributes.PLATFORM_SAMPLING_REASON),
                    PlatformSamplingReasons.FORCE_HEADER);
            span.setStatus(StatusCode.OK);
        });

        await().atMost(TRACE_VISIBILITY_TIMEOUT)
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    Optional<Map<String, String>> attrs = findSpanAttributes(operationName);
                    assertThat(attrs)
                            .as("forced-span обязан сохраниться политикой forced-traces при baseline=0%%")
                            .isPresent();
                    // P0-контракт Spike S3 #4: значение на span — force_header, не x_trace_on.
                    assertThat(attrs.get())
                            .as("platform.sampling.reason обязан быть force_header (реальный exported SDK)")
                            .containsEntry(PlatformAttributes.PLATFORM_SAMPLING_REASON,
                                    PlatformSamplingReasons.FORCE_HEADER);
                });
    }

    // -- helpers ------------------------------------------------------------------------------

    private interface SpanCustomizer {
        void customize(Span span);
    }

    private static void emitSpan(String operationName, SpanCustomizer customizer) {
        Span span = tracer.spanBuilder(operationName)
                .setSpanKind(SpanKind.SERVER)
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            customizer.customize(span);
        } finally {
            span.end();
        }
    }

    /** Пассивное ожидание полного decision-окна Collector'а для negative-ассертов. */
    private static void awaitDecisionWindow() {
        try {
            Thread.sleep(DECISION_WAIT.plusSeconds(3).toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean findOperation(String operationName) throws Exception {
        return jaegerClient.hasOperation(SERVICE_NAME, operationName);
    }

    private static Optional<Map<String, String>> findSpanAttributes(String operationName) throws Exception {
        return jaegerClient.findSpanAttributesByName(SERVICE_NAME, operationName);
    }
}
