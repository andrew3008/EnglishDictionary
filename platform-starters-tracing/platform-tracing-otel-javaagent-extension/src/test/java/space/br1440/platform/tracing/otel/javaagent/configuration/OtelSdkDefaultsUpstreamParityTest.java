package space.br1440.platform.tracing.otel.javaagent.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.opentelemetry.sdk.trace.SpanLimits;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessorBuilder;

@DisplayName("OtelSdkDefaults ↔ upstream OTel SDK parity")
class OtelSdkDefaultsUpstreamParityTest {

    @Test
    @DisplayName("BSP queue/batch/schedule match BatchSpanProcessorBuilder defaults")
    void bsp_matching_defaults_equal_upstream_builder() throws Exception {
        // given/when/then
        assertThat(OtelSdkDefaults.DEFAULT_BSP_MAX_QUEUE_SIZE)
                .as("otel.bsp.max.queue.size")
                .isEqualTo(upstreamInt(BatchSpanProcessorBuilder.class, "DEFAULT_MAX_QUEUE_SIZE"));
        assertThat(OtelSdkDefaults.DEFAULT_BSP_MAX_EXPORT_BATCH_SIZE)
                .as("otel.bsp.max.export.batch.size")
                .isEqualTo(upstreamInt(BatchSpanProcessorBuilder.class, "DEFAULT_MAX_EXPORT_BATCH_SIZE"));
        assertThat(OtelSdkDefaults.DEFAULT_BSP_SCHEDULE_DELAY)
                .as("otel.bsp.schedule.delay")
                .isEqualTo(Duration.ofMillis(
                        upstreamInt(BatchSpanProcessorBuilder.class, "DEFAULT_SCHEDULE_DELAY_MILLIS")));
    }

    @Test
    @DisplayName("BSP export timeout is a documented platform override of upstream 30s")
    void bsp_export_timeout_is_documented_platform_override() throws Exception {
        // given
        int upstreamMillis = upstreamInt(BatchSpanProcessorBuilder.class, "DEFAULT_EXPORT_TIMEOUT_MILLIS");

        // when/then
        assertThat(upstreamMillis)
                .as("upstream BatchSpanProcessorBuilder.DEFAULT_EXPORT_TIMEOUT_MILLIS")
                .isEqualTo(30_000);
        assertThat(OtelSdkDefaults.DEFAULT_BSP_EXPORT_TIMEOUT)
                .as("platform otel.bsp.export.timeout override")
                .isEqualTo(Duration.ofMillis(5_000))
                .isNotEqualTo(Duration.ofMillis(upstreamMillis));
    }

    @Test
    @DisplayName("span limits are documented platform overrides of SpanLimits.getDefault()")
    void span_limits_are_documented_platform_overrides() {
        // given
        SpanLimits upstream = SpanLimits.getDefault();

        // when/then
        assertThat(upstream.getMaxNumberOfAttributes()).isEqualTo(128);
        assertThat(upstream.getMaxNumberOfEvents()).isEqualTo(128);
        assertThat(upstream.getMaxNumberOfLinks()).isEqualTo(128);
        assertThat(upstream.getMaxNumberOfAttributesPerEvent()).isEqualTo(128);
        assertThat(upstream.getMaxNumberOfAttributesPerLink()).isEqualTo(128);

        assertThat(OtelSdkDefaults.DEFAULT_SPAN_ATTRIBUTE_COUNT_LIMIT)
                .isEqualTo(50)
                .isNotEqualTo(upstream.getMaxNumberOfAttributes());
        assertThat(OtelSdkDefaults.DEFAULT_SPAN_EVENT_COUNT_LIMIT)
                .isEqualTo(10)
                .isNotEqualTo(upstream.getMaxNumberOfEvents());
        assertThat(OtelSdkDefaults.DEFAULT_SPAN_LINK_COUNT_LIMIT)
                .isEqualTo(32)
                .isNotEqualTo(upstream.getMaxNumberOfLinks());
        assertThat(OtelSdkDefaults.DEFAULT_SPAN_ATTRIBUTE_VALUE_LENGTH_LIMIT)
                .isEqualTo(1_000)
                .isNotEqualTo(upstream.getMaxAttributeValueLength());
        assertThat(OtelSdkDefaults.DEFAULT_ATTRIBUTE_COUNT_LIMIT)
                .isEqualTo(16);
    }

    private static int upstreamInt(Class<?> type, String fieldName) throws Exception {
        Field field = type.getDeclaredField(fieldName);
        field.setAccessible(true);
        Object value = field.get(null);
        if (value instanceof Integer integer) {
            return integer;
        }

        if (value instanceof Long longValue) {
            return Math.toIntExact(longValue);
        }

        throw new IllegalStateException(
                type.getName() + '.' + fieldName + " has unexpected type " + value.getClass()
        );
    }
}
