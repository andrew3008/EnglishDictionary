package space.br1440.platform.metrics.internal.factory;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import space.br1440.platform.metrics.MetricLabelRulesApplier;
import space.br1440.platform.metrics.MetricTags;
import space.br1440.platform.metrics.configuration.properties.HttpMetricProperties;
import space.br1440.platform.metrics.internal.SloHelper;

import java.time.Duration;
import java.util.List;

public final class HttpMetricFactory {

    private final MeterRegistry meterRegistry;
    private final MetricLabelRulesApplier metricLabelRulesApplier;
    private final HttpMetricProperties httpMetricProperties;
    private final List<Duration> slo;

    HttpMetricFactory(MeterRegistry meterRegistry,
                      MetricLabelRulesApplier metricLabelRulesApplier,
                      HttpMetricProperties httpMetricProperties,
                      List<Duration> slo) {
        this.meterRegistry = meterRegistry;
        this.metricLabelRulesApplier = metricLabelRulesApplier;
        this.httpMetricProperties = httpMetricProperties;
        this.slo = slo;
    }

    public Timer httpTimer(String method, String path, String status) {
        String name = httpMetricProperties.getTimerMetricName();
        Tags tags = metricLabelRulesApplier.apply(
                name,
                Tags.of(MetricTags.METHOD, method,
                        MetricTags.PATH, path,
                        MetricTags.STATUS, status)
        );

        Timer.Builder builder = Timer.builder(name).tags(tags);
        SloHelper.applyServiceLevelObjectives(builder, slo);
        return builder.register(meterRegistry);
    }

    public Counter httpResponseCounter(String method, String path, String status) {
        String name = httpMetricProperties.getCounterMetricName();
        Tags tags = metricLabelRulesApplier.apply(
                name,
                Tags.of(MetricTags.METHOD, method,
                        MetricTags.PATH, path,
                        MetricTags.STATUS, status)
        );

        return meterRegistry.counter(name, tags);
    }
}
