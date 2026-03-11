package space.br1440.platform.healthchecks.common;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class HealthCheckTimer {

    private final long startTimeNanos;
    private volatile long endTimeNanos;

    private HealthCheckTimer() {
        this.startTimeNanos = System.nanoTime();
    }

    public static HealthCheckTimer start() {
        return new HealthCheckTimer();
    }

    public long stop() {
        if (endTimeNanos == 0) {
            endTimeNanos = System.nanoTime();
        }

        return TimeUnit.NANOSECONDS.toMillis(endTimeNanos - startTimeNanos);
    }

    public long elapsed() {
        long end = endTimeNanos != 0 ? endTimeNanos : System.nanoTime();
        return TimeUnit.NANOSECONDS.toMillis(end - startTimeNanos);
    }

    public Health finalize(Health.Builder builder,
                           Map<String, Object> details,
                           @Nullable Duration maxResponseTime) {
        long responseTimeMs = stop();
        details.put("responseTime", formatResponseTime(responseTimeMs));

        if (maxResponseTime != null && responseTimeMs > maxResponseTime.toMillis()) {
            builder.status(Status.OUT_OF_SERVICE);
            details.put("warning", "Response time exceeds threshold");
            details.put("threshold", formatResponseTime(maxResponseTime.toMillis()));
            details.put("exceeded", formatDuration(responseTimeMs - maxResponseTime.toMillis()));
        }

        return builder.withDetails(details).build();
    }

    private String formatResponseTime(long milliseconds) {
        if (milliseconds < 1000) {
            return milliseconds + "ms";
        } else if (milliseconds < 60000) {
            return String.format("%.2fs", milliseconds / 1000.0);
        } else {
            long seconds = milliseconds / 1000;
            return String.format("%dm %ds", seconds / 60, seconds % 60);
        }
    }

    private String formatDuration(long milliseconds) {
        return formatResponseTime(milliseconds);
    }

    @Override
    public String toString() {
        return "HealthCheckTimer{elapsed=" + elapsed() + "ms}";
    }

}
