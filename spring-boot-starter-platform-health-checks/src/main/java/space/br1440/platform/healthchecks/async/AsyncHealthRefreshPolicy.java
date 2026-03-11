package space.br1440.platform.healthchecks.async;

import java.time.Duration;

public interface AsyncHealthRefreshPolicy {

    boolean isEnabled();

    Duration getHealthCheckTimeout();

    Duration getRefreshInterval();

    Duration getStaleResultTtl();

}
