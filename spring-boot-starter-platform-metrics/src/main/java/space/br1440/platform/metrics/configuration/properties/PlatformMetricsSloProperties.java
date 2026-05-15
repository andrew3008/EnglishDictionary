package space.br1440.platform.metrics.configuration.properties;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Accessors(chain = true)
@Validated
@ConfigurationProperties(prefix = PlatformMetricsSloProperties.PREFIX)
public class PlatformMetricsSloProperties {

    public static final String PREFIX = "platform.metrics";

    /**
     * Список порогов гистограммы таймеров (ранее {@code platform.metrics.sla} в миллисекундах).
     */
    private List<Duration> slo = new ArrayList<>();
}
