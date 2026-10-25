package space.br1440.platform.tracing.otel.javaagent.factory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.exporter.SafeSpanExporter;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionCapability;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadinessSnapshot;
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
    @ResourceLock(AgentLogCapture.RESOURCE_LOCK)
    @DisplayName("Non-batch processor keeps the wrapped exporter")
    void nonBatchProcessorKeepsTheWrappedExporter() {
        try (AgentLogCapture logs = AgentLogCapture.installed()) {
            // given
            ScrubbingRuntime runtime = runtimeOfPassword();
            PlatformTracingMBeanRegistrar registrar = new PlatformTracingMBeanRegistrar(AgentInstanceId.random());
            PlatformExportProcessorFactory factory = new PlatformExportProcessorFactory(registrar, runtime);
            List<SpanData> captured = new ArrayList<>();
            SpanExporter wrapped = factory.captureExporter(capturing(captured), TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()));

            SpanProcessor stock = SimpleSpanProcessor.create(wrapped);

            // when
            SpanProcessor result = factory.maybeReplaceExportProcessor(stock, emptyConfig());

            // then
            assertThat(result).isSameAs(stock);
            assertThat(logs.events())
                    .filteredOn(event -> "NON_BATCH_SPAN_PROCESSOR_FALLBACK".equals(event.eventName()))
                    .singleElement()
                    .satisfies(event -> assertThat(event.fields())
                            .containsEntry("type", stock.getClass().getName()));
            assertThat(registrar.extensionReadiness().snapshot().has(
                    PlatformExtensionCapability.EXPORT_PATH_PROTECTED)).isTrue();

            // when
            wrapped.export(List.of(spanWithPasswordEvent()));

            // then
            assertThat(captured).hasSize(1);
            assertThat(captured.getFirst().getEvents().getFirst().getAttributes().get(AttributeKey.stringKey("db.password")))
                    .isNull();
        }
    }

    @Test
    @ResourceLock(AgentLogCapture.RESOURCE_LOCK)
    @DisplayName("Missing captured exporter keeps the incoming processor")
    void missingCapturedExporterKeepsTheIncomingProcessor() {
        try (AgentLogCapture logs = AgentLogCapture.installed()) {
            // given
            ScrubbingRuntime runtime = runtimeOfPassword();
            PlatformTracingMBeanRegistrar registrar = new PlatformTracingMBeanRegistrar(AgentInstanceId.random());
            PlatformExportProcessorFactory factory = new PlatformExportProcessorFactory(registrar, runtime);
            SpanProcessor stock = SimpleSpanProcessor.create(capturing(new ArrayList<>()));

            // when
            SpanProcessor result = factory.maybeReplaceExportProcessor(stock, emptyConfig());

            // then
            assertThat(result).isSameAs(stock);
            assertThat(logs.events())
                    .filteredOn(event -> "CAPTURED_SPAN_EXPORTER_NULL_FALLBACK".equals(event.eventName()))
                    .singleElement()
                    .satisfies(event -> {
                        assertThat(event.level()).isEqualTo(AgentLogLevel.WARN);
                        assertThat(event.eventCode()).isEqualTo(AgentEventCode.EXPORT_FACTORY);
                        assertThat(event.fields()).isEmpty();
                    });
            assertThat(registrar.extensionReadiness().snapshot().has(
                    PlatformExtensionCapability.EXPORT_PATH_PROTECTED)).isFalse();
        }
    }

    @Test
    @DisplayName("Single wrapped exporter still acknowledges export mode for a non-batch processor")
    void singleWrappedExporterAcknowledgesExportModeForNonBatchProcessor() {
        // given
        ScrubbingRuntime runtime = runtimeOfPassword();
        PlatformTracingMBeanRegistrar registrar = new PlatformTracingMBeanRegistrar(AgentInstanceId.random());
        PlatformExportProcessorFactory factory = new PlatformExportProcessorFactory(registrar, runtime);
        AtomicInteger delegateCalls = new AtomicInteger();
        SpanExporter wrapped = factory.captureExporter(new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                delegateCalls.incrementAndGet();
                return CompletableResultCode.ofSuccess();
            }
            @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
            @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
        }, TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()));

        // when
        factory.maybeReplaceExportProcessor(SimpleSpanProcessor.create(wrapped), emptyConfig());

        // then
        PlatformExtensionReadinessSnapshot readiness = registrar.extensionReadiness().snapshot();
        assertThat(readiness.has(PlatformExtensionCapability.EXPORT_PATH_PROTECTED)
                || readiness.has(PlatformExtensionCapability.SAFE_EXPORTER_INSTALLED))
                .isTrue();
    }

    @Test
    @DisplayName("Foreign SafeSpanExporter is rejected at startup instead of being wrapped twice")
    void foreignSafeSpanExporterIsRejectedAtStartup() {
        // given
        ScrubbingRuntime session = runtimeOfPassword();
        List<SpanData> captured = new ArrayList<>();
        SafeSpanExporter foreign = new SafeSpanExporter(
                capturing(captured), TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()),
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
                capturing(captured), TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()), session
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
    @DisplayName("Re-entry with this factory's own ReplacementAwareSpanExporter output does not re-wrap "
            + "or run the boundary a second time")
    void ownReplacementAwareOutputIsShortCircuitedOnReentry() {
        // given
        ScrubbingRuntime session = runtimeOfPassword();
        List<SpanData> captured = new ArrayList<>();
        PlatformExportProcessorFactory factory = new PlatformExportProcessorFactory(
                new PlatformTracingMBeanRegistrar(AgentInstanceId.random()), session
        );

        SpanExporter firstCapture = factory.captureExporter(capturing(captured), TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()));
        SpanExporter secondCapture = factory.captureExporter(firstCapture, TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()));

        // then
        assertThat(secondCapture).isSameAs(firstCapture);

        // when
        secondCapture.export(List.of(spanWithPasswordEvent()));

        // then
        assertThat(captured).hasSize(1);
        assertThat(captured.getFirst().getEvents().getFirst().getAttributes().get(AttributeKey.stringKey("db.password")))
                .isNull();
        assertThat(session.boundaryMetrics().inputSpans())
                .as("exactly one boundary pass, not one per re-entry")
                .isEqualTo(1L);
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

    private static SpanData spanWithPasswordEvent() {
        return TestSpanData.builder()
                .setName("op")
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
