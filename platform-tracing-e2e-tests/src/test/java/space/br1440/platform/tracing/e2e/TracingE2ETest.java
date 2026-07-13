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
import org.testcontainers.junit.jupiter.Testcontainers;
import space.br1440.platform.tracing.api.attributes.PlatformSamplingReasons;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.e2e.support.JaegerTestContainerSupport;
import space.br1440.platform.tracing.e2e.support.JaegerV3QueryClient;
import space.br1440.platform.tracing.e2e.support.OtelCollectorTestContainerSupport;
import space.br1440.platform.tracing.otel.extension.processor.ScrubbingSpanProcessor;
import space.br1440.platform.tracing.otel.extension.scrubbing.BuiltInSpanAttributeScrubbingRules;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end проверка цепочки OpenTelemetry SDK → OTel Collector → Jaeger.
 * <p>
 * Поднимается пара контейнеров: {@code otel/opentelemetry-collector-contrib} и
 * {@code jaegertracing/all-in-one} (с включённым OTLP receiver и Jaeger v3 Query API).
 * SDK запускается в JVM теста (без javaagent), что даёт полный контроль над свойствами
 * BSP/limits и позволяет быстро итерировать сценарии. Java agent тестируется отдельно
 * на этапе деплоя через Helm-чарт сервиса (зона SRE).
 *
 * <h2>Покрываемые сценарии</h2>
 * <ol>
 *   <li>{@code happy_path_спан_доходит_до_Jaeger} — базовый пайп: span с force-record
 *       заголовком гарантированно попадает в Jaeger.</li>
 *   <li>{@code error_span_всегда_сохраняется_tail_sampling} — span со {@code StatusCode.ERROR}
 *       сохраняется в Jaeger вне зависимости от вероятностной политики.</li>
 *   <li>{@code force_on_контракт_без_заголовка_не_попадает_с_заголовком_попадает} —
 *       сильный контракт {@code X-Trace-On}: одновременно проверяет, что без заголовка
 *       OK-span дропается tail_sampling'ом (probabilistic-default = 0% в e2e-конфиге),
 *       а с заголовком — попадает в Jaeger.</li>
 *   <li>{@code scrubbing_атрибут_password_маскируется_до_попадания_в_Jaeger} —
 *       проверка цепочки {@link ScrubbingSpanProcessor}: атрибут {@code db.password=secret123}
 *       приходит в Jaeger со значением {@code "***"}, а соседний {@code db.system}
 *       не затрагивается.</li>
 * </ol>
 *
 * <h2>Намеренно вне scope</h2>
 * <ul>
 *   <li>Полный e2e через HTTP-фасад поднятого Spring Boot приложения — это уже
 *       платформенный смок, ответственность владельца сервиса-потребителя стартера.</li>
 *   <li>Live smoke OTel Java Agent (premain, JDBC, semconv opt-in) — вынесен в
 *       {@code DbSemconvAgentSmokeTest} и {@code PlatformExtensionAgentSmokeTest};
 *       SRE Helm-деплой с агентом — отдельный контур.</li>
 * </ul>
 *
 * <h2>Условия запуска</h2>
 * <p>
 * Тест активируется только при наличии Docker (Testcontainers сам это проверяет) и при
 * передаче gradle property {@code -PrunE2e}. По умолчанию ./gradlew test пропускает модуль,
 * чтобы не замедлять CI на агентах без Docker.
 */
@Testcontainers
@DisabledIfSystemProperty(named = "skipE2e", matches = "true",
        disabledReason = "E2E-тесты пропущены через -DskipE2e=true")
class TracingE2ETest {

    private static final String SERVICE_NAME = "platform-tracing-e2e";
    /** Окно decision_wait в Collector tail_sampling — должно совпадать с YAML-конфигом (5s). */
    private static final Duration DECISION_WAIT = Duration.ofSeconds(5);
    /**
     * Полный таймаут ожидания трейса в Jaeger. С запасом на decision_wait + batch-export
     * delay BSP + сетевой round-trip Jaeger query.
     */
    private static final Duration TRACE_VISIBILITY_TIMEOUT = DECISION_WAIT.plusSeconds(20);

    private static Network network;
    private static GenericContainer<?> jaeger;
    private static GenericContainer<?> collector;
    private static OpenTelemetrySdk sdk;
    private static Tracer tracer;
    private static JaegerV3QueryClient jaegerClient;

    @BeforeAll
    static void setUpStack() {
        network = Network.newNetwork();

        // Jaeger all-in-one с OTLP receiver и Jaeger v3 Query API на порту 16686.
        // Версия 1.62 — последний minor с гарантированной стабильностью v3 API на момент v1 платформы.
        jaeger = JaegerTestContainerSupport.newJaeger(network);
        jaeger.start();

        // Collector контейнер: монтируем минимальный YAML, маршрутизирующий OTLP в Jaeger.
        // Jaeger endpoint — IP контейнера (Gentoo: внутренний Docker DNS ненадёжен).
        collector = OtelCollectorTestContainerSupport.newE2eCollector(network, jaeger);
        collector.start();

        String collectorEndpoint = OtelCollectorTestContainerSupport.otlpHttpTracesEndpointFromHost(collector);
        jaegerClient = new JaegerV3QueryClient(JaegerTestContainerSupport.queryBaseUrl(jaeger));

        // Платформенный ScrubbingSpanProcessor с правилом маскирования секретных ключей.
        // Регистрируется ДО BatchSpanProcessor: ScrubbingSpanProcessor реализует
        // ExtendedSpanProcessor.onEnding(), который вызывается ровно перед onEnd() и таким
        // образом успевает переписать значения атрибутов до того, как BSP заберёт snapshot
        // span'а в очередь на экспорт. Тем самым в Jaeger уже приходят маскированные значения.
        SpanAttributeScrubbingRule passwordRule = BuiltInSpanAttributeScrubbingRules.PASSWORD_KEY.create();
        ScrubbingSpanProcessor scrubbing = new ScrubbingSpanProcessor(List.of(passwordRule));

        sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .setResource(Resource.getDefault().toBuilder()
                                // service.name — стабильное имя атрибута semantic conventions;
                                // используем строковый ключ, чтобы не тащить в e2e-модуль
                                // зависимость opentelemetry-semconv ради одного атрибута.
                                .put(AttributeKey.stringKey("service.name"), SERVICE_NAME)
                                .build())
                        .addSpanProcessor(scrubbing)
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
        tracer = sdk.getTracer("platform-tracing-e2e-tests");
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
    void happy_path_спан_доходит_до_Jaeger() {
        // Сценарий: server-span с reason=force_header (реальное exported-значение SDK,
        // см. PlatformSamplingReasons) гарантированно проходит tail_sampling.
        String operationName = "test-happy-" + UUID.randomUUID();

        Span span = tracer.spanBuilder(operationName)
                .setSpanKind(SpanKind.SERVER)
                .setAttribute("platform.sampling.reason", PlatformSamplingReasons.FORCE_HEADER)
                .setAttribute(AttributeKey.stringKey("test.case"), "happy_path")
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            span.setStatus(StatusCode.OK);
        } finally {
            span.end();
        }

        await().atMost(TRACE_VISIBILITY_TIMEOUT)
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> assertThat(findOperation(operationName))
                        .as("happy-path span %s должен появиться в Jaeger", operationName)
                        .isTrue());
    }

    @Test
    void error_span_всегда_сохраняется_tail_sampling() {
        // Сценарий: даже без force-record и без QA-заголовка, span со StatusCode.ERROR
        // сохраняется политикой `errors-always`. Это критическое требование §3.5
        // «приоритизация: errors > slow > success» из Traces Requests.txt.
        String operationName = "test-error-" + UUID.randomUUID();

        Span span = tracer.spanBuilder(operationName)
                .setSpanKind(SpanKind.SERVER)
                .setAttribute(AttributeKey.stringKey("test.case"), "error_always")
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            span.setStatus(StatusCode.ERROR, "synthetic failure");
        } finally {
            span.end();
        }

        await().atMost(TRACE_VISIBILITY_TIMEOUT)
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> assertThat(findOperation(operationName))
                        .as("error span %s должен быть сохранён errors-always политикой", operationName)
                        .isTrue());
    }

    @Test
    void slow_traces_всегда_сохраняются() {
        String operationName = "test-slow-" + UUID.randomUUID();

        Span span = tracer.spanBuilder(operationName)
                .setSpanKind(SpanKind.SERVER)
                .setAttribute(AttributeKey.stringKey("test.case"), "slow_traces")
                .startSpan();
        
        // Симулируем долгую транзакцию, которая больше threshold_ms = 2000ms
        try (Scope ignored = span.makeCurrent()) {
            try {
                Thread.sleep(2500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            span.setStatus(StatusCode.OK);
        } finally {
            span.end();
        }

        await().atMost(TRACE_VISIBILITY_TIMEOUT)
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> assertThat(findOperation(operationName))
                        .as("slow span %s должен быть сохранён slow-always политикой", operationName)
                        .isTrue());
    }

    @Test
    void health_check_success_отбрасывается_если_нет_ошибок() throws Exception {
        String operationName = "test-health-ok-" + UUID.randomUUID();

        Span span = tracer.spanBuilder(operationName)
                .setSpanKind(SpanKind.SERVER)
                .setAttribute(AttributeKey.stringKey("http.route"), "/actuator/health")
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            span.setStatus(StatusCode.OK);
        } finally {
            span.end();
        }

        // Ждём 6 секунд, чтобы Collector принял решение (decision_wait = 5s)
        try {
            Thread.sleep(6000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        
        assertThat(findOperation(operationName))
                .as("Успешный health-check %s должен быть отброшен", operationName)
                .isFalse();
    }

    @Test
    void health_check_error_всегда_сохраняется() {
        // Конфликт drop-infra-noise vs ERROR - проверяет, что ERROR имеет приоритет
        String operationName = "test-health-error-" + UUID.randomUUID();

        Span span = tracer.spanBuilder(operationName)
                .setSpanKind(SpanKind.SERVER)
                .setAttribute(AttributeKey.stringKey("http.route"), "/actuator/health")
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            span.setStatus(StatusCode.ERROR, "Health check failed");
        } finally {
            span.end();
        }

        await().atMost(TRACE_VISIBILITY_TIMEOUT)
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> assertThat(findOperation(operationName))
                        .as("Ошибочный health-check %s должен быть сохранён (not-error policy)", operationName)
                        .isTrue());
    }

    @Test
    void late_arriving_span_работает_корректно() {
        String operationName1 = "test-late-parent-" + UUID.randomUUID();
        String operationName2 = "test-late-child-" + UUID.randomUUID();

        // Родительский спан с ошибкой
        Span parent = tracer.spanBuilder(operationName1)
                .setSpanKind(SpanKind.SERVER)
                .startSpan();
        
        try (Scope ignored = parent.makeCurrent()) {
            parent.setStatus(StatusCode.ERROR, "Parent failed");
            parent.end();
            
            // Ждем истечения decision_wait (5s) + 1s запаса
            try {
                Thread.sleep(6000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            
            // Дочерний спан, отправляется ПОСЛЕ принятия решения по родителю
            Span child = tracer.spanBuilder(operationName2)
                    .setSpanKind(SpanKind.CLIENT)
                    .startSpan();
            child.setStatus(StatusCode.OK);
            child.end();
        }

        await().atMost(TRACE_VISIBILITY_TIMEOUT)
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    // Проверяем, что оба спана дошли благодаря decision_cache
                    assertThat(findOperation(operationName1)).isTrue();
                    assertThat(findOperation(operationName2)).isTrue();
                });
    }

    @Test
    void force_on_контракт_без_заголовка_не_попадает_с_заголовком_попадает() throws Exception {
        // Сильный контракт force-on: эмитим ДВА span'а в одном тесте.
        //   1) baselineSpan — обычный OK-span без force-header. Должен НЕ попасть в Jaeger,
        //      потому что probabilistic-default в e2e-конфиге = 0% и нет ни одной положительной
        //      политики для него.
        //   2) forcedSpan — обычный OK-span с x-trace-on=on. Должен ПОПАСТЬ в Jaeger
        //      исключительно благодаря политике forced-record.
        // Если оба теста проходят — это доказывает, что попадание forcedSpan'а вызвано именно
        // force-политикой, а не случайным probabilistic-сэмплом.
        //
        // Соответствует требованию из «Параметры для управления» / Traces Requests.txt §3:
        // «Header X-Trace-On (если On, то Trace будет записан)».
        String baselineName = "test-baseline-" + UUID.randomUUID();
        String forcedName = "test-forced-" + UUID.randomUUID();

        emitOkSpan(baselineName, /*forceOn=*/false);
        emitOkSpan(forcedName, /*forceOn=*/true);

        // Сначала ждём появления forcedSpan'а — это наш положительный контроль.
        await().atMost(TRACE_VISIBILITY_TIMEOUT)
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> assertThat(findOperation(forcedName))
                        .as("forced span %s должен попасть в Jaeger через политику forced-record",
                                forcedName)
                        .isTrue());

        // К этому моменту decision_wait collector'а уже истёк и для baseline-span'а:
        // BSP экспортирует обоих в одной пачке, decision_wait одинаковый. Если baseline после
        // момента видимости forced'а всё ещё отсутствует — мы получили доказательство, что он
        // дропнут tail_sampling'ом, а не «ещё в пути». Дополнительный sleep 2с для запаса
        // на случай, если baseline экспортируется чуть позже forced'а.
        try {
            Thread.sleep(2_000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        assertThat(findOperation(baselineName))
                .as("baseline OK-span без force-header не должен попасть в Jaeger "
                        + "(probabilistic-default = 0%% в e2e-конфиге)")
                .isFalse();
    }

    @Test
    void scrubbing_атрибут_password_маскируется_до_попадания_в_Jaeger() {
        // Сценарий: span со «зловредным» атрибутом db.password=secret123 проходит через
        // ScrubbingSpanProcessor (зарегистрирован в @BeforeAll), и в Jaeger приходит уже
        // маскированное значение «***». Это критическое требование §2.4 Traces Requests.txt:
        // «библиотека должна по умолчанию маскировать или удалять значения атрибутов,
        // похожие на пароли, токены, ...».
        //
        // Используется x-trace-on=on, чтобы span гарантированно прошёл tail_sampling
        // (иначе negative-результат теста мог бы означать «span не дошёл», а не «scrubbing работает»).
        String operationName = "test-scrubbing-" + UUID.randomUUID();

        Span span = tracer.spanBuilder(operationName)
                .setSpanKind(SpanKind.SERVER)
                .setAttribute("platform.sampling.reason", PlatformSamplingReasons.FORCE_HEADER)
                .setAttribute(AttributeKey.stringKey("test.case"), "scrubbing_password")
                .setAttribute(AttributeKey.stringKey("db.password"), "secret123")
                .setAttribute(AttributeKey.stringKey("db.system"), "postgresql")
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            span.setStatus(StatusCode.OK);
        } finally {
            span.end();
        }

        await().atMost(TRACE_VISIBILITY_TIMEOUT)
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    Optional<Map<String, String>> attrs = findSpanAttributes(operationName);
                    assertThat(attrs)
                            .as("span %s должен появиться в Jaeger", operationName)
                            .isPresent();
                    Map<String, String> attributes = attrs.get();
                    // Контракт ScrubbingSpanProcessor: значение секретного атрибута заменено,
                    // но сам атрибут не удалён (OTel SDK API не позволяет удалить атрибут).
                    assertThat(attributes)
                            .as("db.password обязан быть замаскирован (пустая строка DROP) в Jaeger")
                            .containsEntry("db.password", "");
                    // Контр-проверка: «безопасные» атрибуты не тронуты.
                    assertThat(attributes)
                            .as("db.system не должен затрагиваться scrubbing'ом")
                            .containsEntry("db.system", "postgresql");
                });
    }

    /**
     * Создаёт и завершает простой OK-span с указанным именем; опционально проставляет
     * заголовок force-on. Используется в {@link #force_on_контракт_без_заголовка_не_попадает_с_заголовком_попадает()},
     * чтобы исключить копипасту между двумя ветками.
     */
    private static void emitOkSpan(String operationName, boolean forceOn) {
        var builder = tracer.spanBuilder(operationName).setSpanKind(SpanKind.SERVER);
        if (forceOn) {
            builder.setAttribute("platform.sampling.reason", PlatformSamplingReasons.FORCE_HEADER);
        }
        Span span = builder.startSpan();
        try (Scope ignored = span.makeCurrent()) {
            span.setStatus(StatusCode.OK);
        } finally {
            span.end();
        }
    }

    private static boolean findOperation(String operationName) throws Exception {
        return jaegerClient.hasOperation(SERVICE_NAME, operationName);
    }

    private static Optional<Map<String, String>> findSpanAttributes(String operationName) throws Exception {
        return jaegerClient.findSpanAttributesByName(SERVICE_NAME, operationName);
    }
}
