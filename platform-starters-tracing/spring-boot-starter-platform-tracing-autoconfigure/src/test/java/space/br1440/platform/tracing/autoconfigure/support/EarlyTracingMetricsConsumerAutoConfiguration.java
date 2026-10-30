package space.br1440.platform.tracing.autoconfigure.support;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.core.Ordered;

import space.br1440.platform.tracing.autoconfigure.metrics.TracingMetricsAutoConfiguration;

@AutoConfiguration(after = TracingMetricsAutoConfiguration.class)
@AutoConfigureOrder(Ordered.HIGHEST_PRECEDENCE)
public class EarlyTracingMetricsConsumerAutoConfiguration {
}
