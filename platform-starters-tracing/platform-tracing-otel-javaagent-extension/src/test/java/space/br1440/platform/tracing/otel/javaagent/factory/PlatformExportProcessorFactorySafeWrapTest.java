package space.br1440.platform.tracing.otel.javaagent.factory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.exporter.SafeSpanExporter;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntimeTestFixtures;

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

class PlatformExportProcessorFactorySafeWrapTest {

    @Test
    @DisplayName("captureExporter сохраняет safe export path за replacement-aware границей")
    void captureExporterKeepsSafeExportPathBehindReplacementBoundary() {
        // given
        PlatformExportProcessorFactory factory = factory();
        CountingExporter delegate = new CountingExporter();

        // when
        SpanExporter result = factory.captureExporter(delegate, TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()));
        result.export(java.util.List.of()).join(1, java.util.concurrent.TimeUnit.SECONDS);

        // then
        assertThat(delegate.exportCalls).hasValue(1);
        assertThat(result).isNotInstanceOf(SafeSpanExporter.class);
    }

    @Test
    @DisplayName("Повторный проход не создаёт вторую replacement-aware границу")
    void captureExporterDoesNotDoubleWrap() {
        // given
        PlatformExportProcessorFactory factory = factory();

        // when
        SpanExporter once = factory.captureExporter(SpanExporter.noop(), TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()));
        SpanExporter twice = factory.captureExporter(once, TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()));

        // then
        assertThat(twice).isSameAs(once);
    }

    @Test
    void real_otlp_http_exporter_accepts_wrapper_then_raw_delegate_shutdown() {
        // given
        SpanExporter rawDelegate = OtlpHttpSpanExporter.builder()
                .setEndpoint("http://127.0.0.1:1/v1/traces")
                .build();

        assertWrapperThenRawDelegateShutdown(rawDelegate);
    }

    @Test
    void real_otlp_grpc_exporter_accepts_wrapper_then_raw_delegate_shutdown() {
        // given
        SpanExporter rawDelegate = OtlpGrpcSpanExporter.builder()
                .setEndpoint("http://127.0.0.1:1")
                .build();

        assertWrapperThenRawDelegateShutdown(rawDelegate);
    }

    private static PlatformExportProcessorFactory factory() {
        return new PlatformExportProcessorFactory(
                new PlatformTracingMBeanRegistrar(AgentInstanceId.random()),
                ScrubbingRuntimeTestFixtures.disabled()
        );
    }

    private static void assertWrapperThenRawDelegateShutdown(SpanExporter rawDelegate) {
        PlatformExportProcessorFactory factory = factory();

        try {
            SpanExporter wrapper = factory.captureExporter(rawDelegate, TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()));

            // when
            CompletableResultCode wrapperShutdown = wrapper.shutdown().join(2, TimeUnit.SECONDS);
            CompletableResultCode rawDelegateShutdown = rawDelegate.shutdown().join(2, TimeUnit.SECONDS);

            // then
            assertThat(wrapperShutdown.isDone()).isTrue();
            assertThat(wrapperShutdown.isSuccess()).isTrue();
            assertThat(rawDelegateShutdown.isDone()).isTrue();
            assertThat(rawDelegateShutdown.isSuccess()).isTrue();
        } finally {
            rawDelegate.shutdown().join(2, TimeUnit.SECONDS);
        }
    }

    private static final class CountingExporter implements SpanExporter {
        private final AtomicInteger exportCalls = new AtomicInteger();

        @Override public CompletableResultCode export(Collection<SpanData> spans) {
            exportCalls.incrementAndGet();
            return CompletableResultCode.ofSuccess();
        }
        @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
        @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
    }

}
