package space.br1440.platform.tracing.otel.javaagent.resilience;

import org.slf4j.Logger;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

import space.br1440.platform.tracing.otel.javaagent.diagnostics.PlatformLogControl;

public final class RateLimitedLogger {

    private final Logger delegate;
    private final TokenBucketRateLimiter warnFast;
    private final TokenBucketRateLimiter warnThrottled;
    private final TokenBucketRateLimiter errorFast;
    private final TokenBucketRateLimiter errorThrottled;
    private final PlatformLogControl logControl;

    private final AtomicBoolean warnThrottledMode = new AtomicBoolean(false);
    private final AtomicBoolean errorThrottledMode = new AtomicBoolean(false);
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
        this.warnFast = TokenBucketRateLimiter.perInterval(fastLimit, interval, unit, nanoClock);
        this.warnThrottled = TokenBucketRateLimiter.perInterval(throttledLimit, interval, unit, nanoClock);
        this.errorFast = TokenBucketRateLimiter.perInterval(fastLimit, interval, unit, nanoClock);
        this.errorThrottled = TokenBucketRateLimiter.perInterval(throttledLimit, interval, unit, nanoClock);
        this.logControl = logControl;
    }

    public boolean warn(String format, Object... args) {
        return log(PlatformLogControl.LogLevel.WARN, warnFast, warnThrottled, warnThrottledMode, "warnings", format, args);
    }

    public boolean error(String format, Object... args) {
        return log(PlatformLogControl.LogLevel.ERROR, errorFast, errorThrottled, errorThrottledMode, "errors", format, args);
    }

    public boolean isDebugEnabled() {
        return logControl.isEnabled(PlatformLogControl.LogLevel.DEBUG) && delegate.isDebugEnabled();
    }

    public boolean debug(String format, Object... args) {
        if (!isDebugEnabled()) {
            return false;
        }

        delegate.debug(format, args);
        return true;
    }

    private boolean log(PlatformLogControl.LogLevel level, TokenBucketRateLimiter fastBucket,
                        TokenBucketRateLimiter throttledBucket, AtomicBoolean throttledMode,
                        String throttleAnnouncementNoun, String format, Object[] args) {
        if (!logControl.isEnabled(level)) {
            return false;
        }

        if (throttledMode.get()) {
            if (throttledBucket.trySpend(1.0)) {
                emit(level, format, args);
                problemLogged.set(true);
                return true;
            }
            return false;
        }

        if (fastBucket.trySpend(1.0)) {
            emit(level, format, args);
            problemLogged.set(true);
            return true;
        }

        if (throttledMode.compareAndSet(false, true)) {
            delegate.warn("Too many tracing-layer {}; further messages will be written at most "
                            + "at the configured limit (throttled). Primary observability channel is JMX/metrics counters.",
                    throttleAnnouncementNoun);
            emit(level, format, args);
            problemLogged.set(true);
            return true;
        }

        return false;
    }

    private void emit(PlatformLogControl.LogLevel level, String format, Object[] args) {
        if (level == PlatformLogControl.LogLevel.ERROR) {
            delegate.error(format, args);
        } else {
            delegate.warn(format, args);
        }
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
        return warnThrottledMode.get() || errorThrottledMode.get();
    }
}
