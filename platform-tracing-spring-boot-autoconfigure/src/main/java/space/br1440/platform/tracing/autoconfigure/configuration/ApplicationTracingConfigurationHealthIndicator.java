package space.br1440.platform.tracing.autoconfigure.configuration;

import java.util.Objects;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Отдельная health-проекция application-side готовности конфигурации трассировки.
 */
final class ApplicationTracingConfigurationHealthIndicator implements HealthIndicator {

    private final TracingConfigurationReadiness readiness;

    ApplicationTracingConfigurationHealthIndicator(TracingConfigurationReadiness readiness) {
        this.readiness = Objects.requireNonNull(readiness, "readiness");
    }

    @Override
    public Health health() {
        TracingConfigurationReadiness.Snapshot snapshot = readiness.current();
        Health.Builder builder = snapshot.state() == TracingConfigurationReadiness.State.READY
                ? Health.up()
                : Health.down();
        return builder
                .withDetail("state", snapshot.state().name())
                .withDetail("messageCode", snapshot.messageCode())
                .withDetail("acceptedSequence", snapshot.acceptedSequence())
                .build();
    }
}
