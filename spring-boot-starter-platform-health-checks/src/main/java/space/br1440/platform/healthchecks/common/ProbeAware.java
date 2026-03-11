package space.br1440.platform.healthchecks.common;

import java.util.Set;

public interface ProbeAware {

    Set<HealthProbe> getProbes();

}
