package space.br1440.platform.tracing.otel.javaagent.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class PlatformTracingDefaultsProviderTest {

    @Test
    void supply_does_not_select_an_sdk_sampler() {
        // when
        Map<String, String> defaults = new PlatformTracingDefaultsProvider().supply();

        // then
        assertThat(defaults).doesNotContainKey("otel.traces.sampler");
    }

    @Test
    void supply_returns_all_BSP_defaults() {
        // when
        Map<String, String> defaults = new PlatformTracingDefaultsProvider().supply();

        // then
        assertThat(defaults)
                .containsEntry("otel.bsp.max.queue.size",
                        String.valueOf(OtelSdkDefaults.DEFAULT_BSP_MAX_QUEUE_SIZE))
                .containsEntry("otel.bsp.max.export.batch.size",
                        String.valueOf(OtelSdkDefaults.DEFAULT_BSP_MAX_EXPORT_BATCH_SIZE))
                .containsEntry("otel.bsp.schedule.delay",
                        String.valueOf(OtelSdkDefaults.DEFAULT_BSP_SCHEDULE_DELAY.toMillis()))
                .containsEntry("otel.bsp.export.timeout",
                        String.valueOf(OtelSdkDefaults.DEFAULT_BSP_EXPORT_TIMEOUT.toMillis()));
    }

    @Test
    void supply_returns_all_span_limit_defaults() {
        // when
        Map<String, String> defaults = new PlatformTracingDefaultsProvider().supply();

        // then
        assertThat(defaults)
                .containsEntry("otel.span.attribute.count.limit",
                        String.valueOf(OtelSdkDefaults.DEFAULT_SPAN_ATTRIBUTE_COUNT_LIMIT))
                .containsEntry("otel.span.attribute.value.length.limit",
                        String.valueOf(OtelSdkDefaults.DEFAULT_SPAN_ATTRIBUTE_VALUE_LENGTH_LIMIT))
                .containsEntry("otel.span.event.count.limit",
                        String.valueOf(OtelSdkDefaults.DEFAULT_SPAN_EVENT_COUNT_LIMIT))
                .containsEntry("otel.span.link.count.limit",
                        String.valueOf(OtelSdkDefaults.DEFAULT_SPAN_LINK_COUNT_LIMIT))
                .containsEntry("otel.attribute.count.limit",
                        String.valueOf(OtelSdkDefaults.DEFAULT_ATTRIBUTE_COUNT_LIMIT));
    }

    @Test
    void supply_returns_canonical_logging_mdc_key_defaults() {
        // when
        Map<String, String> defaults = new PlatformTracingDefaultsProvider().supply();

        // then
        assertThat(defaults)
                .containsEntry("otel.instrumentation.common.logging.trace-id-key", "traceId")
                .containsEntry("otel.instrumentation.common.logging.span-id-key", "spanId")
                .containsEntry("otel.instrumentation.common.logging.trace-flags-key", "traceFlags");
    }

    @Test
    void supply_does_not_contain_empty_values() {
        // when
        Map<String, String> defaults = new PlatformTracingDefaultsProvider().supply();

        // then
        assertThat(defaults).isNotEmpty();
        defaults.forEach((key, value) ->
                assertThat(value).as("значение для ключа '%s' не должно быть пустым", key).isNotBlank());
    }

    @Test
    void supply_returns_independent_copy_on_each_call() {
        // given
        PlatformTracingDefaultsProvider provider = new PlatformTracingDefaultsProvider();

        // when
        Map<String, String> first = provider.supply();
        first.put("otel.injected", "yes");
        Map<String, String> second = provider.supply();

        // then
        assertThat(second).doesNotContainKey("otel.injected");
    }

    @Test
    void supply_does_not_publish_service_identity_keys() {
        // given
        PlatformTracingDefaultsProvider provider = new PlatformTracingDefaultsProvider();

        // when
        Map<String, String> defaults = provider.supply();

        // then
        assertThat(defaults)
                .doesNotContainKey(ExtensionPropertyNames.SERVICE_NAME)
                .doesNotContainKey(ExtensionPropertyNames.SERVICE_VERSION)
                .doesNotContainKey(ExtensionPropertyNames.SERVICE_ENVIRONMENT)
                .doesNotContainKey(ExtensionPropertyNames.SERVICE_HOST)
                .doesNotContainKey(ExtensionPropertyNames.SERVICE_CONTAINER_ID);
    }
}
