package space.br1440.platform.tracing.otel.javaagent.processor.watchdog;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.opentelemetry.context.Context;

/**
 * Underflow-сценарий "release больше выданных permit'ов не должен открыть лишнюю ёмкость" теперь
 * характеризуется на уровне владельца бюджета в {@link SpanPermitLedgerTest} и на уровне admission
 * в {@link WatchdogTraceIndexTest}. Этот класс оставлен для facade-уровневого regression'а, который
 * не зависит от прямого доступа к ledger'у.
 */
class SpanWatchdogLedgerUnderflowTest {

    @Test
    void scanDoesNotIncrementLedgerInvariantCounter() {
        // given
        SpanWatchdogProcessor watchdog = new SpanWatchdogProcessor(
                Duration.ofHours(1), Duration.ofHours(1), System::nanoTime, 10, 10, 10
        );
        watchdog.onStart(Context.root(), WatchdogTestSpans.span(1, 1));
        watchdog.onStart(Context.root(), WatchdogTestSpans.span(2, 1));

        // when
        watchdog.scanForTesting();

        // then
        assertThat(watchdog.getSpanLedgerInvariantViolationTotal()).isZero();
        assertThat(watchdog.getActiveSpanCount()).isEqualTo(2);
        watchdog.shutdown();
    }
}
