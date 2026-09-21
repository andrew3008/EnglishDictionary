package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.processor;

import java.util.Objects;

/**
 * Снимок owner-значений span timeout processor, сгруппированный по семантическим областям.
 * Wire-контракт (набор {@code FIELD_PROCESSOR_SPAN_TIMEOUT_VALUES_*} и open type) не меняется,
 * группировка затрагивает только форму декодированного значения на стороне клиента.
 */
public record ProcessorSpanTimeoutValues(ForceEnd forceEnd, Tracking tracking, Rejections rejections, Scanner scanner) {

    public ProcessorSpanTimeoutValues {
        Objects.requireNonNull(forceEnd, "forceEnd");
        Objects.requireNonNull(tracking, "tracking");
        Objects.requireNonNull(rejections, "rejections");
        Objects.requireNonNull(scanner, "scanner");
    }

    /**
     * Счётчики попыток принудительного завершения span/trace по таймауту.
     */
    public record ForceEnd(long forcedSpanEndAttemptsTotal, long forcedTraceEndAttemptsTotal, long forceEndFailuresTotal) {
    }

    /**
     * Текущее состояние учёта активных/трекаемых span и trace, включая настроенные ёмкости и вытеснение.
     */
    public record Tracking(int activeSpanCount,
                           int activeTraceCount,
                           int trackedTraceCount,
                           int trackedTraceCapacity,
                           long trackingEvictedTotal,
                           int trackedSpanCapacity,
                           int trackedSpanPerTraceCapacity,
                           long spanLedgerInvariantViolationTotal) {
    }

    /**
     * Счётчики отказов в приёме span из-за исчерпания глобальной, per-trace или trace-capacity ёмкости.
     */
    public record Rejections(long spansRejectedPerTraceTotal, long spansRejectedGlobalTotal, long spansRejectedTraceCapacityTotal) {
    }

    /**
     * Состояние фонового сканера и его последнего успешного прохода.
     */
    public record Scanner(long scanFailuresTotal, long lastSuccessfulBackgroundScanEpochMillis, String scannerState) {

        public Scanner {
            Objects.requireNonNull(scannerState, "scannerState");
        }
    }
}
