package space.br1440.platform.healthchecks.business;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.boot.actuate.health.Status;
import space.br1440.platform.healthchecks.common.HealthStatuses;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Результат бизнес-проверки: Spring {@link Status} + details.
 *
 * <p>Можно явно указывает статус через фабричные методы:
 * <ul>
 *   <li>{@link #up()} / {@link #up(String)} — проверка пройдена</li>
 *   <li>{@link #down(String)} — критический сбой, Kubernetes перезапустит pod</li>
 *   <li>{@link #degraded(String)} — деградация, pod продолжает работу с Warning-сообщением</li>
 * </ul>
 */
@Getter
@ToString
@EqualsAndHashCode
public final class BusinessHealthStatus {

    private final Status status;
    private final String message;
    private final Map<String, Object> details;
    private final Exception exception;
    private final Instant timestamp;

    private BusinessHealthStatus(Builder builder) {
        this.status = Objects.requireNonNull(builder.status, "Status is required");
        this.message = builder.message;
        this.details = Map.copyOf(builder.details);
        this.exception = builder.exception;
        this.timestamp = Objects.requireNonNullElseGet(builder.timestamp, Instant::now);
    }

    public static BusinessHealthStatus up() {
        return builder().up().build();
    }

    public static BusinessHealthStatus up(String message) {
        return builder().up().message(message).build();
    }

    public static BusinessHealthStatus down(String message) {
        return builder().down().message(message).build();
    }

    public static BusinessHealthStatus degraded(String message) {
        return builder().degraded().message(message).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean hasDetails() {
        return !details.isEmpty();
    }

    public Object getDetail(String key) {
        return details.get(key);
    }

    public boolean hasException() {
        return exception != null;
    }

    public boolean isUp() {
        return Status.UP.equals(status);
    }

    public static final class Builder {
        private Status status;
        private String message;
        private final Map<String, Object> details = new HashMap<>();
        private Exception exception;
        private Instant timestamp;

        private Builder() {}

        public Builder status(Status status) {
            this.status = Objects.requireNonNull(status);
            return this;
        }

        public Builder up() {
            this.status = Status.UP;
            return this;
        }

        public Builder down() {
            this.status = Status.DOWN;
            return this;
        }

        public Builder degraded() {
            this.status = HealthStatuses.DEGRADED;
            return this;
        }

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        public Builder detail(String key, Object value) {
            Objects.requireNonNull(key, "Detail key cannot be null");
            this.details.put(key, value);
            return this;
        }

        public Builder details(Map<String, Object> details) {
            if (details != null) {
                this.details.putAll(details);
            }
            return this;
        }

        public Builder exception(Exception exception) {
            this.exception = exception;
            return this;
        }

        public BusinessHealthStatus build() {
            return new BusinessHealthStatus(this);
        }
    }
}
