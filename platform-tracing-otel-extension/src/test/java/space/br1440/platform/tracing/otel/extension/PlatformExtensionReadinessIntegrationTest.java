package space.br1440.platform.tracing.otel.extension;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.ManagementFactory;
import java.util.Map;

import javax.management.MBeanServer;

import org.junit.jupiter.api.Test;

import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;

import space.br1440.platform.tracing.otel.extension.jmx.PlatformTracingObjectNames;

class PlatformExtensionReadinessIntegrationTest {

    @Test
    void realAutoconfigureCallbacksPublishReadyOnlyForCompletePipeline() {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        AutoConfiguredOpenTelemetrySdk sdk = AutoConfiguredOpenTelemetrySdk.builder()
                .addPropertiesSupplier(() -> Map.of(
                        "otel.service.name", "readiness-integration-test",
                        "otel.traces.exporter", "logging",
                        "otel.metrics.exporter", "none",
                        "otel.logs.exporter", "none",
                        "otel.propagators", "tracecontext,baggage,platform-trace-control",
                        "platform.tracing.queue.overflow-policy", "UPSTREAM"))
                .build();
        try {
            assertThat(server.getAttribute(
                    PlatformTracingObjectNames.EXTENSION_READINESS, "LifecycleState"))
                    .isEqualTo("READY");
            assertThat((String[]) server.getAttribute(
                    PlatformTracingObjectNames.EXTENSION_READINESS, "Capabilities"))
                    .containsExactlyInAnyOrder((String[]) server.getAttribute(
                            PlatformTracingObjectNames.EXTENSION_READINESS, "RequiredCapabilities"));
            assertThat(server.getAttribute(
                    PlatformTracingObjectNames.EXTENSION_READINESS, "ExportPathProtected"))
                    .isEqualTo(true);
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            sdk.getOpenTelemetrySdk().close();
        }
    }
}
