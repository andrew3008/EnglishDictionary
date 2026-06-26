package space.br1440.platform.datasource.postgres.internal.integration.health;

import lombok.experimental.UtilityClass;
import org.springframework.boot.actuate.health.Status;

@UtilityClass
public final class DegradedHealthStatus {

    public static final String CODE = "DEGRADED";

    public static final Status INSTANCE = new Status(CODE);

}
