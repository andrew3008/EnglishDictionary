package space.br1440.platform.datasource.postgres.internal.integration.health;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = RoutingHealthProperties.PREFIX)
public record RoutingHealthProperties(@DefaultValue("5s") Duration timeout,
                                      @DefaultValue("3s") Duration queryTimeout) {

    public static final String PREFIX = "platform.datasource.postgres-cluster.health";

    public RoutingHealthProperties {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("health.timeout must be > 0");
        }

        if (queryTimeout == null || queryTimeout.isZero() || queryTimeout.isNegative()) {
            throw new IllegalArgumentException("health.query-timeout must be > 0");
        }
    }

    public int queryTimeoutSeconds() {
        long millis = queryTimeout.toMillis();
        long seconds = (millis + 999) / 1000;
        if (seconds < 1) {
            return 1;
        }

        if (seconds > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("health.query-timeout exceeds maximum supported seconds: " + seconds);
        }

        return (int) seconds;
    }
}
