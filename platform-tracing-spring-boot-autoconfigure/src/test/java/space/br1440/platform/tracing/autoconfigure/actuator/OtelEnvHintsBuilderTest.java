package space.br1440.platform.tracing.autoconfigure.actuator;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OtelEnvHintsBuilderTest {

    @Test
    void from_маппит_queue_и_limits_в_integer_ms_OTEL_env() {
        TracingProperties properties = new TracingProperties();
        properties.getQueue().setMaxSize(4096);
        properties.getQueue().setExportBatchSize(256);
        properties.getQueue().setExportTimeout(Duration.ofMillis(250));
        properties.getLimits().setMaxAttributes(40);

        Map<String, Map<String, Object>> hints = OtelEnvHintsBuilder.from(properties);

        assertThat(hints.get("OTEL_BSP_MAX_QUEUE_SIZE"))
                .containsEntry("suggestedValue", "4096")
                .containsEntry("springProperty", "platform.tracing.queue.max-size");

        assertThat(hints.get("OTEL_BSP_EXPORT_TIMEOUT"))
                .containsEntry("suggestedValue", "250")
                .containsEntry("otelProperty", "otel.bsp.export.timeout");

        assertThat(hints.get("OTEL_SPAN_ATTRIBUTE_COUNT_LIMIT"))
                .containsEntry("suggestedValue", "40");
    }
}
