package space.br1440.platform.metrics.internal;

import io.micrometer.core.instrument.Timer;
import lombok.experimental.UtilityClass;
import org.springframework.util.CollectionUtils;

import java.time.Duration;
import java.util.List;

@UtilityClass
public final class SloHelper {

    public static void applyServiceLevelObjectives(Timer.Builder builder, List<Duration> slo) {
        if (CollectionUtils.isEmpty(slo)) {
            return;
        }

        builder.serviceLevelObjectives(slo.toArray(new Duration[0]));
    }
}
