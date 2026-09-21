package space.br1440.platform.tracing.otel.javaagent.processor.spantimeout;

public enum SpanTimeoutScannerState {

    /** Планировщик не создан: процессор обслуживается только явными вызовами {@code forceFlush()}. */
    DISABLED,

    /** Периодическая задача жива. */
    RUNNING,

    /** Задача отменена штатным {@code shutdown()}. */
    STOPPED,

    /**
     * Задача завершилась исключением (фатальная ошибка прохода) и больше не планируется.
     * Состояние терминальное: последующий {@code shutdown()} не переводит его в {@link #STOPPED},
     * чтобы свидетельство аварии не терялось.
     */
    TERMINATED_ABNORMALLY

}
