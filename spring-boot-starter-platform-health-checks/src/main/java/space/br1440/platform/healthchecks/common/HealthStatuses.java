package space.br1440.platform.healthchecks.common;

import org.springframework.boot.actuate.health.Status;

public interface HealthStatuses {

    Status UP = Status.UP;
    Status OUT_OF_SERVICE = Status.OUT_OF_SERVICE;
    Status UNKNOWN = Status.UNKNOWN;
    Status DEGRADED = new Status("DEGRADED");
    Status DOWN = Status.DOWN;

}
