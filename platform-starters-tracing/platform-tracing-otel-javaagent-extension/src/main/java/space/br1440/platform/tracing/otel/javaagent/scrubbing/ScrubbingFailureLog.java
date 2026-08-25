package space.br1440.platform.tracing.otel.javaagent.scrubbing;

import lombok.experimental.UtilityClass;

import space.br1440.platform.tracing.otel.javaagent.logging.AgentEventCodes;
import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;
import space.br1440.platform.tracing.otel.javaagent.resilience.RateLimitedLogger;

@UtilityClass
public final class ScrubbingFailureLog {

    public static void error(RateLimitedLogger log, String event, Throwable t) {
        PlatformThrowables.propagateIfFatal(t);
        log.error(AgentEventCodes.SCRUB_FAILURE, event, t, "event", event);
        logDetail(log, event);
    }

    public static void warn(RateLimitedLogger log, String event, Throwable t) {
        PlatformThrowables.propagateIfFatal(t);
        log.warn(AgentEventCodes.SCRUB_FAILURE, event, t, "event", event);
        logDetail(log, event);
    }

    private static void logDetail(RateLimitedLogger log, String event) {
        if (!log.isDebugEnabled()) {
            return;
        }

        log.debug(AgentEventCodes.SCRUB_FAILURE, event, "event", event);
    }
}
