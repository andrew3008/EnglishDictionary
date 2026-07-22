package space.br1440.platform.tracing.otel.javaagent.safety;

/**
 * Утилита для безопасной обработки {@link Throwable} в safe-обёртках платформы (Фаза 11).
 * <p>
 * Канонический паттерн «swallow all except fatal»: любая safe-обёртка ловит {@link Throwable},
 * вызывает {@link #propagateIfFatal(Throwable)} и затем подавляет оставшееся исключение
 * (заменяя его fallback-поведением). Так бизнес-поток изолируется от сбоев tracing-слоя,
 * но по-настоящему фатальные ошибки JVM и сигнал прерывания не теряются.
 * <p>
 * Паттерн перенят (идея, не исходники) из {@code io.opentelemetry.sdk.common.internal.ThrowableUtil}.
 * Гардрейл «pattern, not source copy» — ADR-otel-direct-integration.
 *
 * <h2>Что пробрасывается</h2>
 * <ul>
 *   <li>{@link VirtualMachineError} (в т.ч. {@link OutOfMemoryError}, {@link StackOverflowError}) —
 *       состояние JVM повреждено, глушить нельзя;</li>
 *   <li>{@link ThreadDeath} — намеренная остановка потока;</li>
 *   <li>{@link LinkageError} — несовместимость классов/версий, скрывать опасно.</li>
 * </ul>
 *
 * <h2>Особый случай: {@link InterruptedException}</h2>
 * Прерывание потока — это управляющий сигнал бизнес-приложения (graceful shutdown, таймаут
 * HTTP-запроса, отмена задачи). Перехватить и «проглотить» его внутри tracing-слоя означало бы
 * сломать механику прерывания вызывающего кода. Однако SPI-методы, которые оборачиваются safe-обёртками
 * ({@code Sampler.shouldSample}, {@code SpanProcessor.onStart/onEnd}, {@code SpanExporter.export}),
 * не объявляют checked-исключений — пробросить {@code InterruptedException} как есть нельзя.
 * Поэтому корректное поведение — <b>восстановить флаг прерывания</b>
 * ({@link Thread#interrupt()}): сигнал не теряется, вышестоящий код увидит его на ближайшей
 * блокирующей операции.
 */
public final class PlatformThrowables {

    private PlatformThrowables() {
    }

    /**
     * Пробрасывает фатальные ошибки JVM и восстанавливает флаг прерывания для
     * {@link InterruptedException}. Для всех прочих {@link Throwable} ничего не делает —
     * вызывающая safe-обёртка обязана их подавить и вернуть fallback.
     *
     * @param t пойманное исключение (может быть {@code null} — тогда no-op)
     */
    @SuppressWarnings({"removal", "deprecation"})
    public static void propagateIfFatal(Throwable t) {
        if (t == null) {
            return;
        }
        if (t instanceof VirtualMachineError) {
            throw (VirtualMachineError) t;
        }
        if (t instanceof ThreadDeath) {
            throw (ThreadDeath) t;
        }
        if (t instanceof LinkageError) {
            throw (LinkageError) t;
        }
        // Прерывание не пробрасываем как checked-исключение (SPI-методы его не объявляют),
        // но обязательно восстанавливаем флаг, чтобы не потерять управляющий сигнал.
        if (t instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
    }
}
