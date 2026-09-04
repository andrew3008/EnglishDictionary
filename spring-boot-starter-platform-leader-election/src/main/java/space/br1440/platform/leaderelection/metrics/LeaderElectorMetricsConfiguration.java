package space.br1440.platform.leaderelection.metrics;

import static space.br1440.platform.leaderelection.metrics.LeaderElectionMetrics.METRIC_PREFIX;

import java.time.Duration;

import org.jetbrains.annotations.NotNull;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;

@AutoConfiguration
@ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
@ConditionalOnProperty(prefix = "platform.leader-election.metrics", name = "enabled", havingValue = "true")
public class LeaderElectorMetricsConfiguration {

    @Bean
    public MeterFilter leaderElectionMetricsFilter() {
        return new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.@NotNull Id id, @NotNull DistributionStatisticConfig config) {
                String name = id.getName();

                if (name.startsWith(METRIC_PREFIX) && name.contains("duration")) {
                    return DistributionStatisticConfig.builder()
                            .percentilesHistogram(true)
                            .serviceLevelObjectives(
                                    Duration.ofMillis(10).toNanos(),
                                    Duration.ofMillis(50).toNanos(),
                                    Duration.ofMillis(100).toNanos(),
                                    Duration.ofMillis(500).toNanos(),
                                    Duration.ofSeconds(1).toNanos(),
                                    Duration.ofSeconds(5).toNanos()
                            )
                            .minimumExpectedValue((double) Duration.ofMillis(1).toNanos())
                            .maximumExpectedValue((double) Duration.ofSeconds(30).toNanos())
                            .build()
                            .merge(config);
                }

                return config;
            }
        };
    }
}
