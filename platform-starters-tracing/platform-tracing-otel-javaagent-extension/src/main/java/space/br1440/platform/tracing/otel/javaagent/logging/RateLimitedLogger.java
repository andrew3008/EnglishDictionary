package space.br1440.platform.tracing.otel.javaagent.logging;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

import space.br1440.platform.tracing.otel.javaagent.logging.event.AgentLogEventType;
import space.br1440.platform.tracing.otel.javaagent.logging.event.LogThrottlingLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.ratelimit.SeverityThrottle;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;

public final class RateLimitedLogger {

    private static final int DEFAULT_BURST = 5;
    private static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(1L);
    private static final AgentLogFieldsWriter WARN_MARKER_FIELDS = fields -> fields.putString(AgentLogField.KIND, "warnings");
    private static final AgentLogFieldsWriter ERROR_MARKER_FIELDS = fields -> fields.putString(AgentLogField.KIND, "errors");

    private final PlatformAgentLogger delegate;
    private final SeverityBucket warnBucket;
    private final SeverityBucket errorBucket;

    public RateLimitedLogger(Class<?> owner) {
        this(
                PlatformAgentLogger.get(owner),
                DEFAULT_BURST,
                DEFAULT_INTERVAL,
                System::nanoTime
        );
    }

    RateLimitedLogger(PlatformAgentLogger delegate,
                      int burst,
                      Duration interval,
                      LongSupplier nanoClock) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.warnBucket = SeverityBucket.warn(burst, interval, nanoClock);
        this.errorBucket = SeverityBucket.error(burst, interval, nanoClock);
    }

    public void log(AgentLogEventType eventType) {
        log(eventType, AgentLogFieldsWriter.NONE);
    }

    public void log(AgentLogEventType eventType, AgentLogFieldsWriter fieldsWriter) {
        logWithResult(eventType, fieldsWriter);
    }

    EmitResult logWithResult(AgentLogEventType eventType, AgentLogFieldsWriter fieldsWriter) {
        AgentLogEventType requiredEventType = Objects.requireNonNull(eventType, "eventType");
        AgentLogFieldsWriter requiredFieldsWriter = Objects.requireNonNull(fieldsWriter, "fieldsWriter");
        SeverityBucket bucket = bucketFor(requiredEventType.level());

        if (!delegate.permits(requiredEventType)) {
            return EmitResult.FILTERED_BY_LEVEL;
        }

        SeverityThrottle.Acquisition acquisition = bucket.throttle().tryAcquire();
        return switch (acquisition.decision()) {
            case ADMIT -> delegate.emitAdmitted(
                    requiredEventType,
                    requiredFieldsWriter,
                    acquisition.suppressedCount()
            );

            case ADMIT_WITH_MARKER -> delegate.emitRateTransitionAdmitted(
                    bucket.markerType(),
                    bucket.markerWriter(),
                    requiredEventType,
                    requiredFieldsWriter,
                    acquisition.suppressedCount()
            );

            case DENY -> EmitResult.RATE_LIMITED;
        };
    }

    private SeverityBucket bucketFor(AgentLogLevel level) {
        return switch (level) {
            case WARN -> warnBucket;
            case ERROR -> errorBucket;
            case OFF, INFO, DEBUG, TRACE -> throw new IllegalArgumentException(
                    "Rate-limited logging supports WARN and ERROR events only"
            );
        };
    }

    private record SeverityBucket(SeverityThrottle throttle,
                                  AgentLogEventType markerType,
                                  AgentLogFieldsWriter markerWriter) {

        static SeverityBucket warn(int burst, Duration interval, LongSupplier nanoClock) {
            return new SeverityBucket(
                    new SeverityThrottle(burst, interval, nanoClock),
                    LogThrottlingLogEvent.RATE_WARNINGS_THROTTLED,
                    WARN_MARKER_FIELDS
            );
        }

        static SeverityBucket error(int burst, Duration interval, LongSupplier nanoClock) {
            return new SeverityBucket(
                    new SeverityThrottle(burst, interval, nanoClock),
                    LogThrottlingLogEvent.RATE_ERRORS_THROTTLED,
                    ERROR_MARKER_FIELDS
            );
        }
    }
}
