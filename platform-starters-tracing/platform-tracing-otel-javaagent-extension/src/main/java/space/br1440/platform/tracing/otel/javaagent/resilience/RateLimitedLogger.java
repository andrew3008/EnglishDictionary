package space.br1440.platform.tracing.otel.javaagent.resilience;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

import space.br1440.platform.tracing.otel.javaagent.logging.AgentEventCodes;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogLevel;
import space.br1440.platform.tracing.otel.javaagent.logging.EmitResult;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;

public final class RateLimitedLogger {

    private final PlatformAgentLogger delegate;
    private final TokenBucketRateLimiter warnFast;
    private final TokenBucketRateLimiter warnThrottled;
    private final TokenBucketRateLimiter errorFast;
    private final TokenBucketRateLimiter errorThrottled;

    private final AtomicBoolean warnThrottledMode = new AtomicBoolean(false);
    private final AtomicBoolean errorThrottledMode = new AtomicBoolean(false);
    private final AtomicBoolean problemLogged = new AtomicBoolean(false);

    public RateLimitedLogger(Class<?> owner) {
        this(PlatformAgentLogger.get(owner), 5, 1, 1, TimeUnit.MINUTES, System::nanoTime);
    }

    public RateLimitedLogger(PlatformAgentLogger delegate) {
        this(delegate, 5, 1, 1, TimeUnit.MINUTES, System::nanoTime);
    }

    public RateLimitedLogger(PlatformAgentLogger delegate, double fastLimit, double throttledLimit,
                             long interval, TimeUnit unit, LongSupplier nanoClock) {
        this.delegate = delegate;
        this.warnFast = TokenBucketRateLimiter.perInterval(fastLimit, interval, unit, nanoClock);
        this.warnThrottled = TokenBucketRateLimiter.perInterval(throttledLimit, interval, unit, nanoClock);
        this.errorFast = TokenBucketRateLimiter.perInterval(fastLimit, interval, unit, nanoClock);
        this.errorThrottled = TokenBucketRateLimiter.perInterval(throttledLimit, interval, unit, nanoClock);
    }

    public EmitResult warn(String eventCode, String message) {
        return log(AgentLogLevel.WARN, warnFast, warnThrottled, warnThrottledMode, "warnings",
                eventCode, message, null);
    }

    public EmitResult warn(String eventCode, String message, String k1, String v1) {
        return log(AgentLogLevel.WARN, warnFast, warnThrottled, warnThrottledMode, "warnings",
                eventCode, message, null, k1, v1);
    }

    public EmitResult warn(String eventCode, String message, String k1, String v1, String k2, String v2) {
        return log(AgentLogLevel.WARN, warnFast, warnThrottled, warnThrottledMode, "warnings",
                eventCode, message, null, k1, v1, k2, v2);
    }

    public EmitResult warn(String eventCode, String message, Throwable error) {
        return log(AgentLogLevel.WARN, warnFast, warnThrottled, warnThrottledMode, "warnings",
                eventCode, message, error);
    }

    public EmitResult warn(String eventCode, String message, Throwable error, String k1, String v1) {
        return log(AgentLogLevel.WARN, warnFast, warnThrottled, warnThrottledMode, "warnings",
                eventCode, message, error, k1, v1);
    }

    public EmitResult error(String eventCode, String message) {
        return log(AgentLogLevel.ERROR, errorFast, errorThrottled, errorThrottledMode, "errors",
                eventCode, message, null);
    }

    public EmitResult error(String eventCode, String message, String k1, String v1) {
        return log(AgentLogLevel.ERROR, errorFast, errorThrottled, errorThrottledMode, "errors",
                eventCode, message, null, k1, v1);
    }

    public EmitResult error(String eventCode, String message, Throwable error) {
        return log(AgentLogLevel.ERROR, errorFast, errorThrottled, errorThrottledMode, "errors",
                eventCode, message, error);
    }

    public EmitResult error(String eventCode, String message, Throwable error, String k1, String v1) {
        return log(AgentLogLevel.ERROR, errorFast, errorThrottled, errorThrottledMode, "errors",
                eventCode, message, error, k1, v1);
    }

    public boolean isDebugEnabled() {
        return delegate.isDebugEnabled();
    }

    public EmitResult debug(String eventCode, String message, String k1, String v1) {
        if (!isDebugEnabled()) {
            return EmitResult.FILTERED_BY_LEVEL;
        }

        return delegate.debug(eventCode, message, k1, v1);
    }

    public EmitResult debug(String eventCode, String message, String k1, String v1, String k2, String v2) {
        if (!isDebugEnabled()) {
            return EmitResult.FILTERED_BY_LEVEL;
        }

        return delegate.debug(eventCode, message, k1, v1, k2, v2);
    }

    private EmitResult log(AgentLogLevel level, TokenBucketRateLimiter fastBucket,
                           TokenBucketRateLimiter throttledBucket, AtomicBoolean throttledMode,
                           String throttleAnnouncementNoun, String eventCode, String message,
                           Throwable error, String... fields) {
        if (!delegate.isEnabled(level)) {
            return EmitResult.FILTERED_BY_LEVEL;
        }

        if (throttledMode.get()) {
            if (throttledBucket.trySpend(1.0)) {
                EmitResult result = emit(level, eventCode, message, error, fields);
                if (result == EmitResult.EMITTED) {
                    problemLogged.set(true);
                }
                return result;
            }
            return EmitResult.RATE_LIMITED;
        }

        if (fastBucket.trySpend(1.0)) {
            EmitResult result = emit(level, eventCode, message, error, fields);
            if (result == EmitResult.EMITTED) {
                problemLogged.set(true);
            }
            return result;
        }

        if (throttledMode.compareAndSet(false, true)) {
            delegate.warn(
                    AgentEventCodes.RATE_THROTTLED,
                    "Agent logging is throttled",
                    "kind",
                    throttleAnnouncementNoun
            );
            EmitResult result = emit(level, eventCode, message, error, fields);
            if (result == EmitResult.EMITTED) {
                problemLogged.set(true);
            }
            return result;
        }

        return EmitResult.RATE_LIMITED;
    }

    private EmitResult emit(
            AgentLogLevel level,
            String eventCode,
            String message,
            Throwable error,
            String[] fields
    ) {
        return delegate.emit(level, eventCode, message, error, (Object[]) fields);
    }

    public EmitResult logRecoveryOnce(String message) {
        if (problemLogged.compareAndSet(true, false)) {
            return delegate.info(AgentEventCodes.RATE_RECOVERY, message);
        }
        return EmitResult.FILTERED_BY_LEVEL;
    }

    boolean isThrottled() {
        return warnThrottledMode.get() || errorThrottledMode.get();
    }
}
