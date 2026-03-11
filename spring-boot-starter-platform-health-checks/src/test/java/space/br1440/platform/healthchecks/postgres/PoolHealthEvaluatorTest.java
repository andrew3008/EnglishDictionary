package space.br1440.platform.healthchecks.postgres;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PoolHealthEvaluator: unit")
class PoolHealthEvaluatorTest {

    @Mock
    private HikariDataSource hikari;
    @Mock
    private HikariPoolMXBean mxBean;

    private PostgresHealthProperties properties;
    private PoolHealthEvaluator evaluator;

    // defaults: maxPoolSize=10, minIdle=2, minAvailablePercent=20, maxWaitingThreads=5
    @BeforeEach
    void setUp() {
        properties = new PostgresHealthProperties();
        properties.getConnectionPool()
                .setMinAvailablePercent(20)
                .setMaxWaitingThreads(5);

        evaluator = new PoolHealthEvaluator(properties);

        when(hikari.getHikariPoolMXBean()).thenReturn(mxBean);
        when(hikari.getMaximumPoolSize()).thenReturn(10);
        when(hikari.getMinimumIdle()).thenReturn(2);
    }

    @Nested
    @DisplayName("нормальное состояние пула")
    class HealthyBaseline {

        @Test
        @DisplayName("все метрики в норме → HEALTHY, isDegraded=false, warnings отсутствуют")
        void shouldReturnHealthyWhenAllMetricsNormal() {
            // Given
            stubPool(1, 4, 5, 0);

            // When
            PoolHealthEvaluator.PoolHealthResult result = evaluator.check(hikari);

            // Then
            assertThat(result.poolStatus()).isEqualTo(PoolHealthEvaluator.PoolHealthResult.PoolStatus.HEALTHY);
            assertThat(result.isDegraded()).isFalse();
            assertThat(result.metrics()).doesNotContainKey("warnings");
        }

        @Test
        @DisplayName("metrics содержат все обязательные ключи")
        void shouldIncludeAllExpectedMetricKeys() {
            // Given
            stubPool(1, 4, 5, 0);

            // When
            Map<String, Object> metrics = evaluator.check(hikari).metrics();

            // Then
            assertThat(metrics).containsKeys(
                    "active", "idle", "total", "waiting",
                    "maxPoolSize", "minIdle", "availablePercent", "status"
            );
        }

        @Test
        @DisplayName("HEALTHY → ключ 'warnings' отсутствует, status='HEALTHY'")
        void shouldNotIncludeWarningsKeyWhenHealthy() {
            // Given
            stubPool(1, 4, 5, 0);

            // When
            Map<String, Object> metrics = evaluator.check(hikari).metrics();

            // Then
            assertThat(metrics).doesNotContainKey("warnings");
            assertThat(metrics.get("status")).isEqualTo("HEALTHY");
        }

        @Test
        @DisplayName("metrics.status совпадает с poolStatus.name()")
        void shouldIncludeStatusNameInMetrics() {
            // Given
            stubPool(1, 4, 5, 0);

            // When
            PoolHealthEvaluator.PoolHealthResult result = evaluator.check(hikari);

            // Then
            assertThat(result.metrics().get("status"))
                    .isEqualTo(result.poolStatus().name());
        }
    }

    @Nested
    @DisplayName("вычисление availablePercent")
    class AvailablePercentCalculation {

        @Test
        @DisplayName("idle=3, total=10 → availablePercent=30")
        void shouldCalculateAvailablePercentCorrectly() {
            // Given
            stubPool(7, 3, 10, 0);

            // When / Then
            assertThat(evaluator.check(hikari).metrics().get("availablePercent")).isEqualTo(30);
        }

        @Test
        @DisplayName("total=0 → availablePercent=0 (защита от деления на 0)")
        void shouldReturnZeroAvailablePercentWhenTotalIsZero() {
            // Given
            stubPool(0, 0, 0, 0);

            // When / Then
            assertThat(evaluator.check(hikari).metrics().get("availablePercent")).isEqualTo(0);
        }

        @Test
        @DisplayName("idle=5, total=5 → availablePercent=100 (все соединения свободны)")
        void shouldReturnHundredPercentWhenAllConnectionsIdle() {
            // Given
            stubPool(0, 5, 5, 0);

            // When / Then
            assertThat(evaluator.check(hikari).metrics().get("availablePercent")).isEqualTo(100);
        }
    }

    @Nested
    @DisplayName("порог minAvailablePercent")
    class MinAvailablePercentThreshold {

        @Test
        @DisplayName("availablePercent < minAvailablePercent → DEGRADED")
        void shouldReturnDegradedWhenAvailablePercentBelowThreshold() {
            // Given — availablePercent=10 < threshold=20
            properties.getConnectionPool().setMinAvailablePercent(20);
            stubPool(9, 1, 10, 0);

            // When / Then
            assertThat(evaluator.check(hikari).poolStatus())
                    .isEqualTo(PoolHealthEvaluator.PoolHealthResult.PoolStatus.DEGRADED);
        }

        @Test
        @DisplayName("availablePercent == minAvailablePercent → HEALTHY (граница: '<', не '<=')")
        void shouldReturnHealthyWhenAvailablePercentEqualsThreshold() {
            // Given — availablePercent=20 == threshold=20
            properties.getConnectionPool().setMinAvailablePercent(20);
            stubPool(8, 2, 10, 0);

            // When / Then
            assertThat(evaluator.check(hikari).poolStatus())
                    .isEqualTo(PoolHealthEvaluator.PoolHealthResult.PoolStatus.HEALTHY);
        }

        @Test
        @DisplayName("warning содержит фактический и пороговый процент при DEGRADED")
        void shouldAddWarningMessageWithActualAndThresholdPercent() {
            // Given — availablePercent=10 < threshold=30
            properties.getConnectionPool().setMinAvailablePercent(30);
            stubPool(9, 1, 10, 0);

            // When
            List<String> warnings = warnings(evaluator.check(hikari));

            // Then
            assertThat(warnings)
                    .hasSize(1)
                    .first().asString()
                    .contains("10%")
                    .contains("30%");
        }

        @ParameterizedTest(name = "idle={0}/10, minAvailablePercent={1}% → DEGRADED")
        @DisplayName("параметризованный: availablePercent < threshold → DEGRADED")
        @CsvSource({"0,10", "1,20", "4,50", "0,1"})
        void shouldReturnDegradedWhenBelowThreshold(int idle, int minPercent) {
            // Given
            properties.getConnectionPool().setMinAvailablePercent(minPercent);
            stubPool(10 - idle, idle, 10, 0);

            // When / Then
            assertThat(evaluator.check(hikari).poolStatus())
                    .isEqualTo(PoolHealthEvaluator.PoolHealthResult.PoolStatus.DEGRADED);
        }

        @ParameterizedTest(name = "idle={0}/10, minAvailablePercent={1}% → HEALTHY")
        @DisplayName("параметризованный: availablePercent >= threshold → HEALTHY")
        @CsvSource({"1,10", "2,10", "10,10", "5,50"})
        void shouldReturnHealthyAtOrAboveThreshold(int idle, int minPercent) {
            // Given
            properties.getConnectionPool().setMinAvailablePercent(minPercent);
            stubPool(10 - idle, idle, 10, 0);

            // When / Then
            assertThat(evaluator.check(hikari).poolStatus())
                    .isEqualTo(PoolHealthEvaluator.PoolHealthResult.PoolStatus.HEALTHY);
        }
    }

    @Nested
    @DisplayName("порог maxWaitingThreads")
    class MaxWaitingThreadsThreshold {

        @Test
        @DisplayName("waiting > maxWaitingThreads → DEGRADED")
        void shouldReturnDegradedWhenWaitingThreadsExceedThreshold() {
            // Given — waiting=4 > maxWaitingThreads=3
            properties.getConnectionPool().setMaxWaitingThreads(3);
            stubPool(1, 4, 5, 4);

            // When / Then
            assertThat(evaluator.check(hikari).poolStatus())
                    .isEqualTo(PoolHealthEvaluator.PoolHealthResult.PoolStatus.DEGRADED);
        }

        @Test
        @DisplayName("waiting == maxWaitingThreads → HEALTHY (граница: '>', не '>=')")
        void shouldReturnHealthyWhenWaitingThreadsEqualsThreshold() {
            // Given — waiting=3 == maxWaitingThreads=3
            properties.getConnectionPool().setMaxWaitingThreads(3);
            stubPool(1, 4, 5, 3);

            // When / Then
            assertThat(evaluator.check(hikari).poolStatus())
                    .isEqualTo(PoolHealthEvaluator.PoolHealthResult.PoolStatus.HEALTHY);
        }

        @Test
        @DisplayName("waiting=0 → HEALTHY (нет ожидающих потоков)")
        void shouldReturnHealthyWhenNoWaitingThreads() {
            // Given
            properties.getConnectionPool().setMaxWaitingThreads(0);
            stubPool(1, 4, 5, 0);

            // When / Then
            assertThat(evaluator.check(hikari).poolStatus())
                    .isEqualTo(PoolHealthEvaluator.PoolHealthResult.PoolStatus.HEALTHY);
        }

        @Test
        @DisplayName("warning содержит фактическое и максимальное число ожидающих потоков")
        void shouldAddWarningMessageWithActualAndMaxWaitingThreads() {
            // Given — waiting=10 > maxWaitingThreads=3
            properties.getConnectionPool().setMaxWaitingThreads(3);
            stubPool(1, 4, 5, 10);

            // When
            List<String> warnings = warnings(evaluator.check(hikari));

            // Then — точное соответствие формату production-кода: "Waiting threads %d exceeds maximum %d"
            assertThat(warnings)
                    .hasSize(1)
                    .first().asString()
                    .contains("Waiting threads")
                    .contains("10")
                    .contains("maximum")
                    .contains("3");
        }
    }

    @Nested
    @DisplayName("исчерпание пула (pool exhausted)")
    class PoolExhaustion {

        @Test
        @DisplayName("total >= maxSize && idle == 0 → DEGRADED + exhausted warning")
        void shouldReturnDegradedWhenPoolExhausted() {
            // Given — total=maxSize=10, idle=0; minAvailablePercent=0 чтобы сработало только exhausted-правило
            properties.getConnectionPool().setMinAvailablePercent(0);
            stubPool(10, 0, 10, 0);

            // When
            PoolHealthEvaluator.PoolHealthResult result = evaluator.check(hikari);

            // Then
            assertThat(result.poolStatus())
                    .isEqualTo(PoolHealthEvaluator.PoolHealthResult.PoolStatus.DEGRADED);
            assertThat(result.isDegraded()).isTrue();
            assertThat(warnings(result))
                    .hasSize(1)
                    .first().asString()
                    .containsIgnoringCase("exhausted");
        }

        @Test
        @DisplayName("total > maxSize && idle == 0 → DEGRADED (total >= maxSize — нестрогое)")
        void shouldReturnDegradedWhenTotalExceedsMaxSize() {
            // Given — total=12 > maxSize=10, idle=0
            properties.getConnectionPool().setMinAvailablePercent(0);
            when(hikari.getMaximumPoolSize()).thenReturn(10);
            stubPool(12, 0, 12, 0);

            // When
            PoolHealthEvaluator.PoolHealthResult result = evaluator.check(hikari);

            // Then
            assertThat(result.isDegraded()).isTrue();
            assertThat(warnings(result)).anySatisfy(w ->
                    assertThat(w).containsIgnoringCase("exhausted"));
        }

        @Test
        @DisplayName("total >= maxSize && idle > 0 → НЕ exhausted")
        void shouldNotTriggerExhaustionWhenIdleConnectionsExist() {
            // Given — total=maxSize=10, idle=1;
            // minAvailablePercent=0 чтобы исключить ложное срабатывание первого правила
            properties.getConnectionPool().setMinAvailablePercent(0);
            stubPool(9, 1, 10, 0);

            // When
            List<String> warnings = warnings(evaluator.check(hikari));

            // Then
            assertThat(warnings).noneMatch(w -> w.toLowerCase().contains("exhausted"));
        }
    }

    @Nested
    @DisplayName("одновременное срабатывание нескольких правил")
    class MultipleRulesTriggered {

        @Test
        @DisplayName("все три правила → DEGRADED, ровно 3 warnings")
        void shouldReturnDegradedWhenMultipleRulesTriggered() {
            // Given:
            // - availablePercent=0 < minAvailablePercent=50         → правило 1
            // - waiting=5 > maxWaitingThreads=0                     → правило 2
            // - total=maxSize=10 && idle=0                          → правило 3 (exhausted)
            properties.getConnectionPool()
                    .setMinAvailablePercent(50)
                    .setMaxWaitingThreads(0);
            stubPool(10, 0, 10, 5);

            // When
            PoolHealthEvaluator.PoolHealthResult result = evaluator.check(hikari);

            // Then
            assertThat(result.poolStatus())
                    .isEqualTo(PoolHealthEvaluator.PoolHealthResult.PoolStatus.DEGRADED);
            assertThat(warnings(result)).hasSize(3);
        }

        @Test
        @DisplayName("DEGRADED → ключ 'warnings' присутствует в metrics")
        void shouldIncludeWarningsKeyWhenDegraded() {
            // Given — minAvailablePercent=100 → всегда нарушено (availablePercent < 100)
            properties.getConnectionPool().setMinAvailablePercent(100);
            stubPool(1, 4, 5, 0);

            // When / Then
            assertThat(evaluator.check(hikari).metrics()).containsKey("warnings");
        }
    }

    @Nested
    @DisplayName("значения метрик в результате")
    class MetricsValues {

        @Test
        @DisplayName("metrics содержат корректные значения из MXBean и DataSource")
        void shouldIncludeCorrectMetricValues() {
            // Given
            when(hikari.getMaximumPoolSize()).thenReturn(20);
            when(hikari.getMinimumIdle()).thenReturn(4);
            stubPool(3, 2, 5, 1);

            // When
            Map<String, Object> metrics = evaluator.check(hikari).metrics();

            // Then
            assertThat(metrics)
                    .containsEntry("active", 3)
                    .containsEntry("idle", 2)
                    .containsEntry("total", 5)
                    .containsEntry("waiting", 1)
                    .containsEntry("maxPoolSize", 20)
                    .containsEntry("minIdle", 4)
                    .containsEntry("availablePercent", 40); // 2/5*100=40
        }

        @Test
        @DisplayName("metrics.status='DEGRADED' когда poolStatus=DEGRADED")
        void shouldIncludeDegradedStatusInMetrics() {
            // Given — любое нарушение вызывает DEGRADED
            properties.getConnectionPool().setMinAvailablePercent(100);
            stubPool(1, 4, 5, 0);

            // When
            PoolHealthEvaluator.PoolHealthResult result = evaluator.check(hikari);

            // Then
            assertThat(result.isDegraded()).isTrue();
            assertThat(result.metrics().get("status")).isEqualTo("DEGRADED");
        }
    }

    private void stubPool(int active, int idle, int total, int waiting) {
        when(mxBean.getActiveConnections()).thenReturn(active);
        when(mxBean.getIdleConnections()).thenReturn(idle);
        when(mxBean.getTotalConnections()).thenReturn(total);
        when(mxBean.getThreadsAwaitingConnection()).thenReturn(waiting);
    }

    @SuppressWarnings("unchecked")
    private List<String> warnings(PoolHealthEvaluator.PoolHealthResult result) {
        Object warnings = result.metrics().get("warnings");
        if (warnings == null) {
            return List.of();
        }
        return (List<String>) warnings;
    }
}
