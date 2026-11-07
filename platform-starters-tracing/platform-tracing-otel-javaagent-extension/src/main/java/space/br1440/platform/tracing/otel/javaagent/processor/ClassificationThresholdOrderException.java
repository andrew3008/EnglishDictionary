package space.br1440.platform.tracing.otel.javaagent.processor;

import java.time.Duration;
import java.util.Objects;

public final class ClassificationThresholdOrderException extends RuntimeException {

    public static final String REASON = "CLASSIFICATION_THRESHOLD_ORDER";

    private final Duration normalThreshold;
    private final Duration slowThreshold;

    public ClassificationThresholdOrderException(Duration slowThreshold, Duration normalThreshold) {
        super("slowThreshold must be strictly greater than normalThreshold");
        this.slowThreshold = Objects.requireNonNull(slowThreshold, "slowThreshold");
        this.normalThreshold = Objects.requireNonNull(normalThreshold, "normalThreshold");
    }

    public String reason() {
        return REASON;
    }

    public Duration normalThreshold() {
        return normalThreshold;
    }

    public Duration slowThreshold() {
        return slowThreshold;
    }
}
