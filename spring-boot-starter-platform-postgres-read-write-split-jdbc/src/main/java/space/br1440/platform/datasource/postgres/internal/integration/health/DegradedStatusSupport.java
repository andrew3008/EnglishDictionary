package space.br1440.platform.datasource.postgres.internal.integration.health;

import lombok.experimental.UtilityClass;
import org.springframework.boot.actuate.health.HttpCodeStatusMapper;
import org.springframework.boot.actuate.health.SimpleHttpCodeStatusMapper;
import org.springframework.boot.actuate.health.SimpleStatusAggregator;
import org.springframework.boot.actuate.health.StatusAggregator;

import java.util.Map;

@UtilityClass
public final class DegradedStatusSupport {

    public static StatusAggregator newAggregator() {
        return new SimpleStatusAggregator(
                "DOWN",
                "OUT_OF_SERVICE",
                DegradedHealthStatus.CODE,
                "UNKNOWN",
                "UP"
        );
    }

    public static HttpCodeStatusMapper newHttpCodeStatusMapper(int degradedHttpCode) {
        if (degradedHttpCode < 100 || degradedHttpCode > 599) {
            throw new IllegalArgumentException(
                    "degradedHttpCode must be in range 100..599, got: " + degradedHttpCode
            );
        }

        return new SimpleHttpCodeStatusMapper(Map.of(
                "DOWN",            503,
                "OUT_OF_SERVICE",  503,
                DegradedHealthStatus.CODE, degradedHttpCode
        ));
    }
}
