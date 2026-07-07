package space.br1440.platform.tracing.autoconfigure.metrics;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import space.br1440.platform.tracing.api.attributes.PlatformSamplingReasons;
import space.br1440.platform.tracing.autoconfigure.jmx.PlatformTracingJmxClient;

/**
 * Интеграция операционной статистики сэмплера (CompositeSampler) в Micrometer.
 * Счётчики хранятся в OTel Java Agent extension и считываются через JMX (SamplingControl MBean)
 * при скрейпинге.
 */
public class PlatformTracingSamplerMetricsBinder implements MeterBinder {

    private static final String METRIC_NAME = "platform.tracing.sampler.decisions";

    private final PlatformTracingJmxClient client;

    public PlatformTracingSamplerMetricsBinder(PlatformTracingJmxClient client) {
        this.client = client;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        String[] sampledReasons = {
                PlatformSamplingReasons.FORCE_HEADER,
                PlatformSamplingReasons.QA_TRACE,
                PlatformSamplingReasons.PARENT_SAMPLED,
                PlatformSamplingReasons.ROUTE_RATIO,
                PlatformSamplingReasons.GLOBAL_RATIO};
        String[] droppedReasons = {
                PlatformSamplingReasons.KILL_SWITCH,
                PlatformSamplingReasons.DROP_PATH,
                PlatformSamplingReasons.PARENT_DROP,
                PlatformSamplingReasons.ROUTE_RATIO_DROP,
                PlatformSamplingReasons.GLOBAL_RATIO_DROP,
                PlatformSamplingReasons.FALLBACK_DROP};

        for (String reason : sampledReasons) {
            FunctionCounter.builder(METRIC_NAME, client,
                            c -> c.getSamplerDecisionCount("RECORD_AND_SAMPLE", reason).orElse(0L).doubleValue())
                    .tags("decision", "sampled", "reason", reason)
                    .description("Количество решений сэмплинга")
                    .register(registry);
        }

        for (String reason : droppedReasons) {
            FunctionCounter.builder(METRIC_NAME, client,
                            c -> c.getSamplerDecisionCount("DROP", reason).orElse(0L).doubleValue())
                    .tags("decision", "dropped", "reason", reason)
                    .description("Количество решений сэмплинга")
                    .register(registry);
        }

        FunctionCounter.builder("platform.tracing.sampling.invalid_config", client,
                        c -> c.getInvalidConfigCount().orElse(0L).doubleValue())
                .description("Количество неудачных попыток обновления конфигурации (например, невалидный ratio)")
                .register(registry);
    }
}
