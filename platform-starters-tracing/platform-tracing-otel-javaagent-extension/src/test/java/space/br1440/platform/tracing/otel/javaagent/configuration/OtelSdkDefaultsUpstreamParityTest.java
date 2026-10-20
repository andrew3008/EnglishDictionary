package space.br1440.platform.tracing.otel.javaagent.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.processor.dropoldest.PlatformDropOldestExportSpanProcessor;

import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanLimits;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;

@DisplayName("OtelSdkDefaults ↔ upstream OTel SDK parity")
class OtelSdkDefaultsUpstreamParityTest {

    @Test
    @DisplayName("BSP fallback contract is applied to the platform processor")
    void bsp_fallback_contract_is_applied_to_platform_processor() {
        // given
        PlatformDropOldestExportSpanProcessor processor = PlatformDropOldestExportSpanProcessor
                .builder(exporter(CompletableResultCode.ofSuccess()))
                .readBspConfigFrom(DefaultConfigProperties.createFromMap(Map.of()))
                .build();

        try {
            // when
            PlatformDropOldestExportSpanProcessor.MetricsSnapshot metrics = processor.metricsSnapshot();

            // then
            assertThat(metrics.queueCapacity()).isEqualTo(OtelSdkDefaults.DEFAULT_BSP_MAX_QUEUE_SIZE);
            assertThat(OtelSdkDefaults.DEFAULT_BSP_MAX_QUEUE_SIZE).isEqualTo(2_048);
            assertThat(OtelSdkDefaults.DEFAULT_BSP_MAX_EXPORT_BATCH_SIZE).isEqualTo(512);
            assertThat(OtelSdkDefaults.DEFAULT_BSP_SCHEDULE_DELAY).isEqualTo(Duration.ofSeconds(5));
        } finally {
            processor.shutdown().join(2, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Missing otel.bsp.export.timeout uses the five-second platform fallback")
    void missing_bsp_export_timeout_uses_five_second_platform_fallback() {
        // given
        CompletableResultCode pendingExport = new CompletableResultCode();
        PlatformDropOldestExportSpanProcessor processor = PlatformDropOldestExportSpanProcessor
                .builder(exporter(pendingExport))
                .readBspConfigFrom(DefaultConfigProperties.createFromMap(Map.of()))
                .build();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(processor)
                .build();
        Tracer tracer = tracerProvider.get("bsp-export-timeout-fallback-test");

        try {
            // when
            tracer.spanBuilder("timeout").startSpan().end();
            CompletableResultCode flush = processor.forceFlush();

            // then
            assertThat(flush.join(7, TimeUnit.SECONDS).isDone()).isTrue();
            assertThat(flush.isSuccess()).isFalse();
            assertThat(processor.metricsSnapshot().timeoutsTotal()).isEqualTo(1L);
            assertThat(OtelSdkDefaults.DEFAULT_BSP_EXPORT_TIMEOUT).isEqualTo(Duration.ofSeconds(5));
        } finally {
            pendingExport.succeed();
            tracerProvider.shutdown().join(2, TimeUnit.SECONDS);
        }
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

    private static SpanExporter exporter(CompletableResultCode exportResult) {
        return new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                return exportResult;
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        };
    }
}
