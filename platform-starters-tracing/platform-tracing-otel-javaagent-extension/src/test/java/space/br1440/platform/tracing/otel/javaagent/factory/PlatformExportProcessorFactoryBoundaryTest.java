package space.br1440.platform.tracing.otel.javaagent.factory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.configuration.OtelSdkDefaults;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.exporter.guard.OtlpRequestSizeLimitingSpanExporter;
import space.br1440.platform.tracing.otel.javaagent.exporter.SafeSpanExporter;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionCapability;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.rules.BuiltInSpanAttributeScrubbingRules;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.SanitizerBootstrapException;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntimeTestFixtures;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.testing.trace.TestSpanData;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

class PlatformExportProcessorFactoryBoundaryTest {

    @Test
    @DisplayName("Non-batch processor is rejected without protecting the export path")
    void nonBatchProcessorIsRejected() {
        // given
        ScrubbingRuntime runtime = runtimeOfPassword();
        PlatformTracingMBeanRegistrar registrar = new PlatformTracingMBeanRegistrar(AgentInstanceId.random());
        PlatformExportProcessorFactory factory = new PlatformExportProcessorFactory(registrar, runtime);
        SpanExporter wrapped = factory.captureExporter(capturing(new ArrayList<>()),
                TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()));
        try (SpanProcessor stock = SimpleSpanProcessor.create(wrapped)) {
            // when
            Throwable failure = catchThrowable(() -> factory.maybeReplaceExportProcessor(stock, emptyConfig()));

            // then
            assertThat(failure).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(stock.getClass().getName());
            assertThat(registrar.extensionReadiness().snapshot().has(
                    PlatformExtensionCapability.EXPORT_PATH_PROTECTED)).isFalse();
        }
    }

    @Test
    @DisplayName("Missing captured exporter is rejected")
    void missingCapturedExporterIsRejected() {
        // given
        ScrubbingRuntime runtime = runtimeOfPassword();
        PlatformTracingMBeanRegistrar registrar = new PlatformTracingMBeanRegistrar(AgentInstanceId.random());
        PlatformExportProcessorFactory factory = new PlatformExportProcessorFactory(registrar, runtime);
        try (SpanProcessor stock = SimpleSpanProcessor.create(capturing(new ArrayList<>()))) {
            // when
            Throwable failure = catchThrowable(() -> factory.maybeReplaceExportProcessor(stock, emptyConfig()));

            // then
            assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessageContaining("found 0");
            assertThat(registrar.extensionReadiness().snapshot().has(
                    PlatformExtensionCapability.EXPORT_PATH_PROTECTED)).isFalse();
        }
    }

    @Test
    @DisplayName("Multiple captured exporters are rejected")
    void multipleCapturedExportersAreRejected() {
        // given
        ScrubbingRuntime runtime = runtimeOfPassword();
        PlatformTracingMBeanRegistrar registrar = new PlatformTracingMBeanRegistrar(AgentInstanceId.random());
        PlatformExportProcessorFactory factory = new PlatformExportProcessorFactory(registrar, runtime);
        SpanExporter first = factory.captureExporter(
                capturing(new ArrayList<>()),
                TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random())
        );
        SpanExporter second = factory.captureExporter(
                capturing(new ArrayList<>()),
                TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random())
        );
        SpanProcessor stock = SimpleSpanProcessor.create(first);

        // when
        Throwable failure = catchThrowable(() -> factory.maybeReplaceExportProcessor(stock, emptyConfig()));

        // then
        assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessageContaining("found 2");
        assertThat(registrar.extensionReadiness().snapshot()
                .has(PlatformExtensionCapability.EXPORT_PATH_PROTECTED)).isFalse();
        stock.close();
        second.close();
    }

    @Test
    @DisplayName("Foreign SafeSpanExporter is rejected at startup instead of being wrapped twice")
    void foreignSafeSpanExporterIsRejectedAtStartup() {
        // given
        ScrubbingRuntime session = runtimeOfPassword();
        List<SpanData> captured = new ArrayList<>();
        SafeSpanExporter foreign = new SafeSpanExporter(
                new OtlpRequestSizeLimitingSpanExporter(
                        capturing(captured),
                        Math.toIntExact(OtelSdkDefaults.MAX_OTLP_REQUEST_SIZE_BYTES)
                ),
                TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()),
                ScrubbingRuntimeTestFixtures.disabled());

        PlatformExportProcessorFactory factory = new PlatformExportProcessorFactory(
                new PlatformTracingMBeanRegistrar(AgentInstanceId.random()), session);

        // when
        Throwable failure = catchThrowable(
                () -> factory.captureExporter(foreign, TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random())));

        // then
        assertThat(failure)
                .isInstanceOf(SanitizerBootstrapException.class)
                .extracting(t -> ((SanitizerBootstrapException) t).code())
                .isEqualTo(SanitizerBootstrapException.FOREIGN_SAFE_EXPORTER);
        assertThat(catchThrowable(session::requireInstallationComplete))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Same-runtime identity alone does not prove factory provenance: a manually-built "
            + "SafeSpanExporter is rejected even when it shares the session runtime (H-4)")
    void manuallyBuiltSameRuntimeExporterIsRejectedDespiteMatchingRuntimeIdentity() {
        // given
        ScrubbingRuntime session = runtimeOfPassword();
        List<SpanData> captured = new ArrayList<>();
        SafeSpanExporter lookalike = new SafeSpanExporter(
                new OtlpRequestSizeLimitingSpanExporter(
                        capturing(captured),
                        Math.toIntExact(OtelSdkDefaults.MAX_OTLP_REQUEST_SIZE_BYTES)
                ),
                TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()), session
        );

        PlatformExportProcessorFactory factory = new PlatformExportProcessorFactory(
                new PlatformTracingMBeanRegistrar(AgentInstanceId.random()), session);

        // when
        Throwable failure = catchThrowable(
                () -> factory.captureExporter(lookalike, TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random())));

        // then
        assertThat(failure)
                .isInstanceOf(SanitizerBootstrapException.class)
                .extracting(t -> ((SanitizerBootstrapException) t).code())
                .isEqualTo(SanitizerBootstrapException.FOREIGN_SAFE_EXPORTER);
    }

    @Test
    @ResourceLock(AgentLogCapture.RESOURCE_LOCK)
    @DisplayName("Factory re-entry preserves one scrubbed export path that drops oversized spans and retains neighbours")
    void ownReplacementAwareOutputKeepsSingleProtectedExportPath() {
        // given
        ScrubbingRuntime session = runtimeOfPassword();
        List<SpanData> captured = new ArrayList<>();
        PlatformExportProcessorFactory factory = new PlatformExportProcessorFactory(
                new PlatformTracingMBeanRegistrar(AgentInstanceId.random()), session
        );

        SpanData oversized = TestSpanData.builder()
                .setName("oversized")
                .setKind(io.opentelemetry.api.trace.SpanKind.INTERNAL)
                .setStatus(StatusData.unset())
                .setStartEpochNanos(0L)
                .setEndEpochNanos(1L)
                .setHasEnded(true)
                .setAttributes(Attributes.of(AttributeKey.stringArrayKey("payload"), Collections.nCopies(
                        Math.toIntExact(OtelSdkDefaults.MAX_OTLP_REQUEST_SIZE_BYTES / 1024) + 1,
                        "x".repeat(1024))))
                .build();

        try (SpanExporter firstCapture = factory.captureExporter(
                capturing(captured),
                TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()))) {
            SpanExporter secondCapture = factory.captureExporter(
                    firstCapture,
                    TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random())
            );

            // when
            CompletableResultCode result = secondCapture.export(List.of(
                    spanWithPasswordEvent("left"),
                    oversized,
                    spanWithPasswordEvent("right"))
            );

            // then
            assertThat(secondCapture).isSameAs(firstCapture);
            assertThat(result.isSuccess()).isTrue();
            assertThat(captured).extracting(SpanData::getName).containsExactly("left", "right");
            assertThat(captured).allSatisfy(span -> assertThat(
                    span.getEvents().getFirst().getAttributes().get(AttributeKey.stringKey("db.password"))).isNull());
            assertThat(session.boundaryMetrics().inputSpans())
                    .as("each input span passes the boundary exactly once despite factory re-entry")
                    .isEqualTo(3L);
        }
    }

    private static ScrubbingRuntime runtimeOfPassword() {
        SpanAttributeScrubbingRule password = BuiltInSpanAttributeScrubbingRules.PASSWORD_KEY.create();
        return ScrubbingRuntimeTestFixtures.fromBuiltInRules(List.of(password));
    }

    private static SpanExporter capturing(List<SpanData> captured) {
        return new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                captured.addAll(spans);
                return CompletableResultCode.ofSuccess();
            }
            @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
            @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
        };
    }

    private static SpanData spanWithPasswordEvent(String name) {
        return TestSpanData.builder()
                .setName(name)
                .setKind(io.opentelemetry.api.trace.SpanKind.INTERNAL)
                .setStatus(StatusData.unset())
                .setStartEpochNanos(0L)
                .setEndEpochNanos(1L)
                .setHasEnded(true)
                .setEvents(List.of(EventData.create(
                        1L, "db.query", Attributes.of(AttributeKey.stringKey("db.password"), "supersecret"))))
                .build();
    }

    private static ConfigProperties emptyConfig() {
        return DefaultConfigProperties.createFromMap(Map.of());
    }
}
