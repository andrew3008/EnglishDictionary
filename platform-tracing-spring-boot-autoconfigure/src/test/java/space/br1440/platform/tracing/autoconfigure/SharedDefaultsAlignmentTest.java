package space.br1440.platform.tracing.autoconfigure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.javaagent.configuration.spi.DropOldestExportProcessorDefaults;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Targeted alignment-контракт по whitelist shared defaults между
 * {@link TracingProperties} (Spring-facing) и agent extension defaults (OTel SDK policy).
 * <p>
 * BSP queue defaults проверяются через {@link DropOldestExportProcessorDefaults}.
 * Span limits проверяются по задокументированным platform defaults (локальные литералы).
 * Override drift при старте мониторит {@code DualChannelDriftDiagnostics}.
 * <p>
 * См. {@code docs/decisions/ADR-dual-channel-properties-v0.1.md}.
 */
class SharedDefaultsAlignmentTest {

    private final TracingProperties properties = new TracingProperties();

    @Test
    @DisplayName("Shared BSP queue defaults: Spring TracingProperties.Queue == agent extension BSP defaults")
    void queueDefaults_aligned_with_agentExtension() {
        TracingProperties.Queue queue = properties.getQueue();

        assertThat(queue.getMaxSize())
                .as("queue.max-size должен совпадать с agent extension BSP max queue size")
                .isEqualTo(DropOldestExportProcessorDefaults.defaultMaxQueueSize());

        assertThat(queue.getExportBatchSize())
                .as("queue.export-batch-size должен совпадать с agent extension BSP max export batch size")
                .isEqualTo(DropOldestExportProcessorDefaults.defaultMaxExportBatchSize());

        assertThat(queue.getExportTimeout().toMillis())
                .as("queue.export-timeout (ms) должен совпадать с agent extension BSP export timeout")
                .isEqualTo(DropOldestExportProcessorDefaults.defaultExportTimeout().toMillis());
    }

    @Test
    @DisplayName("Span limits defaults: Spring TracingProperties.Limits == documented platform defaults")
    void limitsDefaults_matchDocumentedPlatformDefaults() {
        TracingProperties.Limits limits = properties.getLimits();

        assertThat(limits.getMaxAttributes())
                .as("limits.max-attributes — platform default otel.span.attribute.count.limit")
                .isEqualTo(50);

        assertThat(limits.getMaxAttributeValueLength())
                .as("limits.max-attribute-value-length — platform default otel.span.attribute.value.length.limit")
                .isEqualTo(1000);

        assertThat(limits.getMaxEvents())
                .as("limits.max-events — platform default otel.span.event.count.limit")
                .isEqualTo(10);
    }
}
