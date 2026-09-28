package space.br1440.platform.tracing.otel.javaagent.logging;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

import space.br1440.platform.tracing.otel.javaagent.logging.event.AgentLogEventType;
import space.br1440.platform.tracing.otel.javaagent.logging.event.LogThrottlingLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.ratelimit.SeverityThrottle;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;

public final class RateLimitedLogger {

    private static final double DEFAULT_FAST_LIMIT = 5.0d;
    private static final double DEFAULT_SLOW_LIMIT = 1.0d;
    private static final long DEFAULT_INTERVAL = 1L;
    private static final AgentLogFieldsWriter NO_FIELDS = fields -> {};
    private static final AgentLogFieldsWriter WARN_MARKER_FIELDS = fields ->
            fields.putString(AgentLogField.KIND, "warnings");
    private static final AgentLogFieldsWriter ERROR_MARKER_FIELDS = fields ->
            fields.putString(AgentLogField.KIND, "errors");

    private final PlatformAgentLogger delegate;
    private final SeverityThrottle warnThrottle;
    private final SeverityThrottle errorThrottle;
    private final AtomicBoolean problemLogged = new AtomicBoolean(false);

    public RateLimitedLogger(Class<?> owner) {
        this(
                PlatformAgentLogger.get(owner),
                DEFAULT_FAST_LIMIT,
                DEFAULT_SLOW_LIMIT,
                DEFAULT_INTERVAL,
                TimeUnit.MINUTES,
                System::nanoTime
        );
    }

    RateLimitedLogger(PlatformAgentLogger delegate,
                      double fastLimit,
                      double slowLimit,
                      long interval,
                      TimeUnit unit,
                      LongSupplier nanoClock) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.warnThrottle = new SeverityThrottle(fastLimit, slowLimit, interval, unit, nanoClock);
        this.errorThrottle = new SeverityThrottle(fastLimit, slowLimit, interval, unit, nanoClock);
    }

    public void log(AgentLogEventType eventType) {
        log(eventType, NO_FIELDS);
    }

    public void log(AgentLogEventType eventType, AgentLogFieldsWriter fieldsWriter) {
        logWithResult(eventType, fieldsWriter);
    }

    EmitResult logWithResult(AgentLogEventType eventType, AgentLogFieldsWriter fieldsWriter) {
        AgentLogEventType requiredEventType = Objects.requireNonNull(eventType, "eventType");
        AgentLogFieldsWriter requiredFieldsWriter = Objects.requireNonNull(fieldsWriter, "fieldsWriter");
        AgentLogLevel level = requiredEventType.level();
        SeverityThrottle throttle;
        AgentLogEventType markerType;
        AgentLogFieldsWriter markerWriter;
        if (level == AgentLogLevel.WARN) {
            throttle = warnThrottle;
            markerType = LogThrottlingLogEvent.RATE_WARNINGS_THROTTLED;
            markerWriter = WARN_MARKER_FIELDS;
        } else if (level == AgentLogLevel.ERROR) {
            throttle = errorThrottle;
            markerType = LogThrottlingLogEvent.RATE_ERRORS_THROTTLED;
            markerWriter = ERROR_MARKER_FIELDS;
        } else {
            throw new IllegalArgumentException("Rate-limited logging supports WARN and ERROR events only");
        }

        if (!delegate.permits(requiredEventType)) {
            return EmitResult.FILTERED_BY_LEVEL;
        }

        SeverityThrottle.Acquisition acquisition = throttle.tryAcquire();
        SeverityThrottle.Decision decision = acquisition.decision();
        if (decision == SeverityThrottle.Decision.DENY) {
            return EmitResult.RATE_LIMITED;
        }

        EmitResult result;
        if (decision == SeverityThrottle.Decision.ADMIT_WITH_MARKER) {
            result = delegate.emitRateTransitionAdmitted(
                    markerType,
                    markerWriter,
                    requiredEventType,
                    requiredFieldsWriter,
                    acquisition.suppressedCount()
            );
        } else {
            result = delegate.emitAdmitted(
                    requiredEventType,
                    requiredFieldsWriter,
                    acquisition.suppressedCount()
            );
        }

        if (result == EmitResult.EMITTED || result == EmitResult.FALLBACK_EMITTED) {
            problemLogged.set(true);
        }

        return result;
    }

    public boolean tryLogRecoveryOnce() {
        if (!problemLogged.get() || !problemLogged.compareAndSet(true, false)) {
            return false;
        }

        long suppressedCount = warnThrottle.drainSuppressed() + errorThrottle.drainSuppressed();
        if (delegate.permits(LogThrottlingLogEvent.RATE_RECOVERY)) {
            delegate.emitAdmitted(LogThrottlingLogEvent.RATE_RECOVERY, NO_FIELDS, suppressedCount);
        }
        return true;
    }

    boolean isThrottled() {
        return warnThrottle.isThrottled() || errorThrottle.isThrottled();
    }
}
