package space.br1440.platform.tracing.e2e;

import io.opentelemetry.api.common.AttributeKey;
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
import org.testcontainers.junit.jupiter.Testcontainers;
import space.br1440.platform.tracing.core.exception.ExceptionMessagePolicy;
import space.br1440.platform.tracing.core.exception.ExceptionRecorder;
import space.br1440.platform.tracing.core.facade.DefaultTraceOperations;
import space.br1440.platform.tracing.core.runtime.otel.OtelTracingRuntimeFactory;
import space.br1440.platform.tracing.core.semconv.policy.AttributePolicy;
import space.br1440.platform.tracing.e2e.support.JaegerTestContainerSupport;
import space.br1440.platform.tracing.e2e.support.JaegerV3QueryClient;
import space.br1440.platform.tracing.e2e.support.OtelCollectorTestContainerSupport;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end проверка скрабинга exception-event'ов на app-side пути (Wave A3).
 * <p>
 * Закрывает gap H2: фасад {@code TraceOperations} раньше вызывал raw {@code Span.recordException},
 * который пишет НЕскрабленный exception-event ({@code exception.message}/{@code exception.stacktrace}
 * мимо {@code ScrubbingSpanProcessor} — events не скрабятся). После wiring через
 * {@link ExceptionRecorder} при секьюр-дефолте в экспортированном event'е остаётся только
 * {@code exception.type}.
 * <p>
 * Запуск в JVM теста (без javaagent) поверх реальной цепочки SDK → Collector → Jaeger: span со
 * {@code StatusCode.ERROR} (его ставит {@code ExceptionRecorder}) сохраняется политикой
 * {@code errors-always} e2e-конфига Collector'а. Активируется при наличии Docker и {@code -PrunE2e}.
 */
@Testcontainers
@DisabledIfSystemProperty(named = "skipE2e", matches = "true",
        disabledReason = "E2E-тесты пропущены через -DskipE2e=true")
class ExceptionEventScrubbingE2ETest {

    private static final String SERVICE_NAME = "platform-exception-scrubbing-e2e";
    private static final String EXCEPTION_EVENT = "exception";
    /** PII-маркер в message исключения: НЕ должен утечь при секьюр-дефолте. */
    private static final String SECRET_MESSAGE = "ssn=000-11-2222 token=supersecret";
    private static final Duration DECISION_WAIT = Duration.ofSeconds(5);
    private static final Duration TRACE_VISIBILITY_TIMEOUT = DECISION_WAIT.plusSeconds(20);

    private static Network network;
    private static GenericContainer<?> jaeger;
    private static GenericContainer<?> collector;
    private static OpenTelemetrySdk sdk;
    private static JaegerV3QueryClient jaegerClient;

    /** Секьюр-дефолт: message/stacktrace off (как в production по умолчанию). */
    private static DefaultTraceOperations secureTracing;
    /** Verbose-режим: message включён осознанно — негативный контроль. */
    private static DefaultTraceOperations verboseTracing;

    @BeforeAll
    static void setUpStack() {
        network = Network.newNetwork();

        jaeger = JaegerTestContainerSupport.newJaeger(network);
        jaeger.start();

        collector = OtelCollectorTestContainerSupport.newE2eCollector(network, jaeger);
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

        // Фасады поверх одного SDK: отличаются только ExceptionMessagePolicy.
        secureTracing = new DefaultTraceOperations(OtelTracingRuntimeFactory.create(sdk, new AttributePolicy(), new ExceptionRecorder(ExceptionMessagePolicy.secureDefault())));
        verboseTracing = new DefaultTraceOperations(OtelTracingRuntimeFactory.create(sdk, new AttributePolicy(), new ExceptionRecorder(new ExceptionMessagePolicy(true, false))));
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
    void manualSpan_секьюрДефолт_exceptionEventБезRawMessageИStacktrace() {
        String operationName = "exc-manual-secure-" + UUID.randomUUID();

        // Manual span path: startSpan -> recordException -> close (OwningSpanScope).
        try (var scope = secureTracing.manual().operation(operationName).start()) {
            scope.recordException(new IllegalStateException(SECRET_MESSAGE));
        }

        await().atMost(TRACE_VISIBILITY_TIMEOUT).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
            List<JaegerV3QueryClient.SpanEvent> events =
                    jaegerClient.findSpanEventsByName(SERVICE_NAME, operationName);
            assertThat(events)
                    .as("span %s должен иметь exception-event", operationName)
                    .anyMatch(e -> EXCEPTION_EVENT.equals(e.name()));

            JaegerV3QueryClient.SpanEvent exception = events.stream()
                    .filter(e -> EXCEPTION_EVENT.equals(e.name()))
                    .findFirst().orElseThrow();

            // exception.type — безопасный FQN — присутствует.
            assertThat(exception.attributes().get("exception.type"))
                    .isEqualTo("java.lang.IllegalStateException");
            // Секьюр-дефолт: message/stacktrace НЕ утекли.
            assertThat(exception.attributes()).doesNotContainKey("exception.message");
            assertThat(exception.attributes()).doesNotContainKey("exception.stacktrace");
            // Ни один атрибут event'а не содержит PII-подстроку.
            assertThat(exception.attributes().values())
                    .noneMatch(v -> v != null && v.contains("supersecret"));
        });
    }

    @Test
    void inSpan_секьюрДефолт_exceptionEventБезRawMessage() {
        String operationName = "exc-inspan-secure-" + UUID.randomUUID();

        // App inSpan path: при RuntimeException фасад сам вызывает scope.recordException и пробрасывает.
        try {
            secureTracing.manual().operation(operationName).run(() -> {
                throw new IllegalArgumentException(SECRET_MESSAGE);
            });
        } catch (IllegalArgumentException expected) {
            // ожидаемо: исключение пробрасывается caller'у без изменений
        }

        await().atMost(TRACE_VISIBILITY_TIMEOUT).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
            List<JaegerV3QueryClient.SpanEvent> events =
                    jaegerClient.findSpanEventsByName(SERVICE_NAME, operationName);
            JaegerV3QueryClient.SpanEvent exception = events.stream()
                    .filter(e -> EXCEPTION_EVENT.equals(e.name()))
                    .findFirst().orElseThrow(() ->
                            new AssertionError("exception-event не найден для " + operationName));
            assertThat(exception.attributes().get("exception.type"))
                    .isEqualTo("java.lang.IllegalArgumentException");
            assertThat(exception.attributes()).doesNotContainKey("exception.message");
        });
    }

    @Test
    void manualSpan_messageВключёнPolicy_exceptionEventСодержитSanitizedMessage() {
        String operationName = "exc-manual-verbose-" + UUID.randomUUID();
        String visibleMessage = "boom-visible-" + UUID.randomUUID();

        try (var scope = verboseTracing.manual().operation(operationName).start()) {
            scope.recordException(new IllegalStateException(visibleMessage));
        }

        await().atMost(TRACE_VISIBILITY_TIMEOUT).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
            List<JaegerV3QueryClient.SpanEvent> events =
                    jaegerClient.findSpanEventsByName(SERVICE_NAME, operationName);
            JaegerV3QueryClient.SpanEvent exception = events.stream()
                    .filter(e -> EXCEPTION_EVENT.equals(e.name()))
                    .findFirst().orElseThrow(() ->
                            new AssertionError("exception-event не найден для " + operationName));
            // Негативный контроль: при include-message=true message публикуется (policy реально управляет event'ом).
            assertThat(exception.attributes().get("exception.message")).isEqualTo(visibleMessage);
        });
    }
}
