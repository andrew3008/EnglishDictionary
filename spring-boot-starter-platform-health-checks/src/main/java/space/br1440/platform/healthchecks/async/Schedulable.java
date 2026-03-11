package space.br1440.platform.healthchecks.async;

import java.time.Duration;

public interface Schedulable extends Runnable {

    Duration getRefreshInterval();

}
