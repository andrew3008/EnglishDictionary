package space.br1440.platform.tracing.otel.javaagent.safety;

import org.slf4j.Logger;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Переиспользуемый rate-limited логгер для tracing-слоя (Фаза 11).
 * <p>
 * Назначение: одна из типичных production-ошибок observability-библиотеки — логировать каждую
 * ошибку pipeline. При недоступном Collector'е или misconfigured deploy это приводит к «log storm»
 * (тысячи строк в минуту), росту CPU/диска и маскировке настоящей проблемы. Этот класс заменяет
 * дублирующуюся ad-hoc throttling-логику (ранее в {@code SafeSpanExporter}, {@code ValidatingSpanProcessor},
 * {@code RuleCircuitBreaker}) единым контрактом.
 *
 * <h2>Двухфазный throttling (паттерн OTel {@code ThrottlingLogger})</h2>
 * <ul>
 *   <li><b>Normal:</b> до {@code fastLimit} сообщений за интервал пишутся как есть;</li>
 *   <li>при исчерпании — однократное уведомление о троттлинге и переход в <b>throttled</b>;</li>
 *   <li><b>Throttled:</b> не чаще {@code throttledLimit} сообщений за интервал.</li>
 * </ul>
 *
 * <h2>Recovery-once (паттерн Datadog {@code IOLogger})</h2>
 * Каждое реально записанное предупреждение взводит флаг «была проблема». При первом успешном
 * восстановлении вызывающий код может один раз залогировать recovery-событие через
 * {@link #logRecoveryOnce(String, Object...)} — это сбрасывает флаг. Так в логе видно и начало,
 * и конец деградации, без шума в промежутке.
 *
 * <p><b>Потокобезопасность:</b> {@link TokenBucketRateLimiter} синхронизирован, флаги атомарны.
 */
public final class RateLimitedLogger {

    private final Logger delegate;
    private final TokenBucketRateLimiter fast;
    private final TokenBucketRateLimiter throttled;
    private final long interval;
    private final TimeUnit unit;
    /** Runtime-порог детализации платформенной диагностики (Фаза 14). */
    private final PlatformLogControl logControl;

    private final AtomicBoolean throttledMode = new AtomicBoolean(false);
    private final AtomicBoolean problemLogged = new AtomicBoolean(false);

    /** Конструктор с дефолтами: 5 сообщений/мин (fast), затем 1 сообщение/мин (throttled). */
    public RateLimitedLogger(Logger delegate) {
        this(delegate, 5, 1, 1, TimeUnit.MINUTES, System::nanoTime);
    }

    /**
     * @param delegate       реальный slf4j-логгер
     * @param fastLimit      сообщений за интервал в обычном режиме
     * @param throttledLimit сообщений за интервал в throttled-режиме
     * @param interval       длительность интервала
     * @param unit           единица интервала
     * @param nanoClock      источник монотонного времени (для тестов — виртуальные часы)
     */
    public RateLimitedLogger(Logger delegate, double fastLimit, double throttledLimit,
                             long interval, TimeUnit unit, LongSupplier nanoClock) {
        this(delegate, fastLimit, throttledLimit, interval, unit, nanoClock, PlatformLogControl.shared());
    }

    /**
     * Полный конструктор с явным {@link PlatformLogControl} (для тестов изолированного уровня).
     */
    public RateLimitedLogger(Logger delegate, double fastLimit, double throttledLimit,
                             long interval, TimeUnit unit, LongSupplier nanoClock,
                             PlatformLogControl logControl) {
        this.delegate = delegate;
        this.interval = interval;
        this.unit = unit;
        this.fast = TokenBucketRateLimiter.perInterval(fastLimit, interval, unit, nanoClock);
        this.throttled = TokenBucketRateLimiter.perInterval(throttledLimit, interval, unit, nanoClock);
        this.logControl = logControl;
    }

    /**
     * Пишет WARN с учётом rate-limit. Поддерживает slf4j-плейсхолдеры {@code {}} и завершающий
     * {@link Throwable} как последний аргумент.
     *
     * @return {@code true}, если сообщение реально записано; {@code false} — подавлено троттлингом
     */
    public boolean warn(String format, Object... args) {
        // Runtime log-level gate (Фаза 14): при уровне ниже WARN диагностика платформы приглушается
        // без рестарта (agent-CL, недоступный /actuator/loggers). Счётчики JMX/метрики не зависят.
        if (!logControl.isEnabled(PlatformLogControl.LogLevel.WARN)) {
            return false;
        }
        if (throttledMode.get()) {
            if (throttled.trySpend(1.0)) {
                delegate.warn(format, args);
                problemLogged.set(true);
                return true;
            }
            return false;
        }

        if (fast.trySpend(1.0)) {
            delegate.warn(format, args);
            problemLogged.set(true);
            return true;
        }

        // Fast-лимит исчерпан: ровно один поток переводит логгер в throttled-режим и пишет уведомление.
        if (throttledMode.compareAndSet(false, true)) {
            delegate.warn("Слишком много предупреждений tracing-слоя — дальнейшие будут писаться "
                    + "не чаще лимита (throttled). Основной канал наблюдаемости — счётчики JMX/метрики.");
            delegate.warn(format, args);
            problemLogged.set(true);
            return true;
        }
        return false;
    }

    /**
     * Если ранее было записано хотя бы одно предупреждение, один раз пишет INFO о восстановлении
     * и сбрасывает внутренний флаг. Безопасно вызывать на каждом успешном исходе — лишних записей
     * не будет.
     *
     * @return {@code true}, если recovery-сообщение было записано
     */
    public boolean logRecoveryOnce(String format, Object... args) {
        if (problemLogged.compareAndSet(true, false)) {
            // Recovery — INFO: печатаем только если текущий порог это разрешает (Фаза 14).
            if (logControl.isEnabled(PlatformLogControl.LogLevel.INFO)) {
                delegate.info(format, args);
                return true;
            }
        }
        return false;
    }

    /** {@code true}, если логгер сейчас в throttled-режиме (для тестов/диагностики). */
    boolean isThrottled() {
        return throttledMode.get();
    }
}
