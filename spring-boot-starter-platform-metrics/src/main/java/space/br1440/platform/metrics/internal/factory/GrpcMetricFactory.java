package space.br1440.platform.metrics.internal.factory;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import space.br1440.platform.metrics.MetricLabelRulesApplier;
import space.br1440.platform.metrics.MetricTags;
import space.br1440.platform.metrics.configuration.properties.GrpcMetricsProperties;
import space.br1440.platform.metrics.internal.SloHelper;

import java.time.Duration;
import java.util.List;

public final class GrpcMetricFactory {

    private final MeterRegistry meterRegistry;
    private final MetricLabelRulesApplier metricLabelRulesApplier;
    private final GrpcMetricsProperties grpcMetricsProperties;
    private final List<Duration> slo;

    GrpcMetricFactory(MeterRegistry meterRegistry,
                      MetricLabelRulesApplier metricLabelRulesApplier,
                      GrpcMetricsProperties grpcMetricsProperties,
                      List<Duration> slo) {
        this.meterRegistry = meterRegistry;
        this.metricLabelRulesApplier = metricLabelRulesApplier;
        this.grpcMetricsProperties = grpcMetricsProperties;
        this.slo = slo;
    }

    public Timer grpcTimer(String methodName, String service, String code) {
        String name = grpcMetricsProperties.getTimerMetricName();
        Tags tags = metricLabelRulesApplier.apply(
                name,
                Tags.of(MetricTags.RPC_METHOD, methodName,
                        MetricTags.RPC_STATUS_CODE, code,
                        MetricTags.SERVICE, service)
        );

        Timer.Builder builder = Timer.builder(name).tags(tags);
        SloHelper.applyServiceLevelObjectives(builder, slo);
        return builder.register(meterRegistry);
    }

    public Counter grpcReceived(String methodName, String service) {
        String name = grpcMetricsProperties.getCounterReceivedMetricName();
        Tags tags = metricLabelRulesApplier.apply(
                name,
                Tags.of(MetricTags.RPC_METHOD, methodName,
                        MetricTags.SERVICE, service)
        );

        return meterRegistry.counter(name, tags);
    }

    public Counter grpcSent(String methodName, String service, String code) {
        String name = grpcMetricsProperties.getCounterSentMetricName();
        Tags tags = metricLabelRulesApplier.apply(
                name,
                Tags.of(MetricTags.RPC_METHOD, methodName,
                        MetricTags.RPC_STATUS_CODE, code,
                        MetricTags.SERVICE, service)
        );

        return meterRegistry.counter(name, tags);
    }

    public Counter grpcStreamMessageSent(String methodName, String service) {
        String name = grpcMetricsProperties.getStreamMessagesSentMetricName();
        Tags tags = metricLabelRulesApplier.apply(
                name,
                Tags.of(MetricTags.RPC_METHOD, methodName,
                        MetricTags.SERVICE, service)
        );

        return meterRegistry.counter(name, tags);
    }

    public Counter grpcStreamMessageReceived(String methodName, String service) {
        String name = grpcMetricsProperties.getStreamMessagesReceivedMetricName();
        Tags tags = metricLabelRulesApplier.apply(
                name,
                Tags.of(MetricTags.RPC_METHOD, methodName,
                        MetricTags.SERVICE, service)
        );

        return meterRegistry.counter(name, tags);
    }
}
