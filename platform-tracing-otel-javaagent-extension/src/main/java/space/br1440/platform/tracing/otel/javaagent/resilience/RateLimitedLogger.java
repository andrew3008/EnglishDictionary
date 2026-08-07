package space.br1440.platform.tracing.otel.javaagent.resilience;

import org.slf4j.Logger;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

import space.br1440.platform.tracing.otel.javaagent.diagnostics.PlatformLogControl;

public final class RateLimitedLogger {

    private final Logger delegate;
    private final TokenBucketRateLimiter fast;
    private final TokenBucketRateLimiter throttled;
    private final PlatformLogControl logControl;

    private final AtomicBoolean throttledMode = new AtomicBoolean(false);
    private final AtomicBoolean problemLogged = new AtomicBoolean(false);

    public RateLimitedLogger(Logger delegate) {
        this(delegate, 5, 1, 1, TimeUnit.MINUTES, System::nanoTime);
    }

    public RateLimitedLogger(Logger delegate, double fastLimit, double throttledLimit,
                             long interval, TimeUnit unit, LongSupplier nanoClock) {
        this(delegate, fastLimit, throttledLimit, interval, unit, nanoClock, PlatformLogControl.shared());
    }

    public RateLimitedLogger(Logger delegate, double fastLimit, double throttledLimit,
                             long interval, TimeUnit unit, LongSupplier nanoClock,
                             PlatformLogControl logControl) {
        this.delegate = delegate;
        this.fast = TokenBucketRateLimiter.perInterval(fastLimit, interval, unit, nanoClock);
        this.throttled = TokenBucketRateLimiter.perInterval(throttledLimit, interval, unit, nanoClock);
        this.logControl = logControl;
    }

    public boolean warn(String format, Object... args) {
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

        if (throttledMode.compareAndSet(false, true)) {
            delegate.warn("Слишком много предупреждений tracing-слоя — дальнейшие будут писаться "
                    + "не чаще лимита (throttled). Основной канал наблюдаемости — счётчики JMX/метрики.");
            delegate.warn(format, args);
            problemLogged.set(true);
            return true;
        }

        return false;
    }

    public boolean logRecoveryOnce(String format, Object... args) {
        if (problemLogged.compareAndSet(true, false)) {
            if (logControl.isEnabled(PlatformLogControl.LogLevel.INFO)) {
                delegate.info(format, args);
                return true;
            }
        }

        return false;
    }

    boolean isThrottled() {
        return throttledMode.get();
    }
}
