package space.br1440.platform.tracing.otel.javaagent.scrubbing;

import lombok.experimental.UtilityClass;

import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;
import space.br1440.platform.tracing.otel.javaagent.resilience.RateLimitedLogger;

@UtilityClass
public final class ScrubbingFailureLog {

    private static final String SUPPRESSED_HINT = "(detail suppressed; enable DEBUG for space.br1440.platform.tracing to inspect)";

    public static void error(RateLimitedLogger log, String event, Throwable t) {
        PlatformThrowables.propagateIfFatal(t);
        log.error("Scrubbing failure [{}]: {} " + SUPPRESSED_HINT, event, t.getClass().getName());
        logDetail(log, event, t);
    }

    public static void warn(RateLimitedLogger log, String event, Throwable t) {
        PlatformThrowables.propagateIfFatal(t);
        log.warn("Scrubbing failure [{}]: {} " + SUPPRESSED_HINT, event, t.getClass().getName());
        logDetail(log, event, t);
    }

    private static void logDetail(RateLimitedLogger log, String event, Throwable t) {
        if (!log.isDebugEnabled()) {
            return;
        }

        log.debug("Scrubbing failure [{}] detail: {}", event, ThrowableDigest.render(t));
    }
}
