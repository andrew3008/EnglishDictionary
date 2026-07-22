package space.br1440.platform.tracing.otel.extension.propagation;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Runtime-гейт платформенной пропагации (Фаза 14, kill-switch).
 * <p>
 * Гейт управляет ТОЛЬКО платформенными управляющими заголовками
 * ({@code X-Trace-On}/{@code X-QA}/{@code X-Request-Id}), которые обрабатывает
 * {@link InboundTraceControlPropagator}. W3C {@code traceparent}/{@code tracestate} и
 * {@code baggage} — зона OTel Java Agent и этим гейтом НЕ управляются: при выключенной
 * платформенной пропагации trace-context продолжает ходить, ломки нет.
 *
 * <h2>Почему shared-singleton</h2>
 * Пропагатор создаётся SPI-фабрикой в classloader'е Java Agent extension и его сложно
 * протянуть через конструкторы к JMX-слою. Здесь живёт один classloader, поэтому единый
 * процессный инстанс корректен — тот же паттерн, что и
 * {@link space.br1440.platform.tracing.otel.extension.safety.TracingDiagnostics#shared()}.
 * В юнит-тестах можно создать изолированный экземпляр через {@code new PlatformPropagationGate()}.
 *
 * <p>Это политика (kill-switch), а не топология: набор {@code fields()} пропагатора не меняется
 * (контракт {@code TextMapPropagator.fields()} остаётся стабильным во избежание рассинхрона кэшей).
 *
 * <p><b>Потокобезопасность:</b> флаг и счётчики атомарны; рассчитан на конкурентные inject/extract.
 */
public final class PlatformPropagationGate {

    private static final PlatformPropagationGate SHARED = new PlatformPropagationGate();

    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private final LongAdder gatedInjects = new LongAdder();
    private final LongAdder gatedExtracts = new LongAdder();

    public static PlatformPropagationGate shared() {
        return SHARED;
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    public void setEnabled(boolean value) {
        enabled.set(value);
    }

    /** Регистрирует пропущенный из-за выключенного гейта inject (диагностика). */
    void recordGatedInject() {
        gatedInjects.increment();
    }

    /** Регистрирует пропущенный из-за выключенного гейта extract (диагностика). */
    void recordGatedExtract() {
        gatedExtracts.increment();
    }

    public long getGatedInjects() {
        return gatedInjects.sum();
    }

    public long getGatedExtracts() {
        return gatedExtracts.sum();
    }
}
