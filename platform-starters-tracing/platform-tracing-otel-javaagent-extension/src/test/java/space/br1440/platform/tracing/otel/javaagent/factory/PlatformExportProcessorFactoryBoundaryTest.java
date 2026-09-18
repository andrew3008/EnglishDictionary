package space.br1440.platform.tracing.otel.javaagent.factory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.exporter.SafeSpanExporter;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionCapability;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.SanitizerBootstrapException;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingRuntime;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingRuntimeTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.rules.BuiltInSpanAttributeScrubbingRules;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.testing.trace.TestSpanData;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

/**
 * P5: UPSTREAM and DROP_OLDEST share the same {@code SafeSpanExporter} boundary wrap.
 */
class PlatformExportProcessorFactoryBoundaryTest {

    @Test
    @DisplayName("captureExporter wraps every exporter; UPSTREAM keeps the wrapped path")
    void upstreamModeExportsThroughTheBoundaryWrap() {
        // given
        ScrubbingRuntime runtime = runtimeOfPassword();
        PlatformTracingMBeanRegistrar registrar = new PlatformTracingMBeanRegistrar();
        PlatformExportProcessorFactory factory = new PlatformExportProcessorFactory(registrar, runtime);
        List<SpanData> captured = new ArrayList<>();
        SpanExporter wrapped = factory.captureExporter(capturing(captured), TracingSettingsSource.isolatedDefaults());

        ExtensionConfig upstream = new ExtensionConfig(stringConfig("UPSTREAM"));
        SpanProcessor stock = SimpleSpanProcessor.create(wrapped);
        SpanProcessor result = factory.maybeReplaceExportProcessor(
                stock, upstream.queue(), stringConfig("UPSTREAM"));

        // then
        assertThat(result).isSameAs(stock);
        assertThat(registrar.extensionReadiness().has(PlatformExtensionCapability.EXPORT_PATH_PROTECTED)).isTrue();

        // when
        wrapped.export(List.of(spanWithPasswordEvent()));

        // then
        assertThat(captured).hasSize(1);
        assertThat(captured.getFirst().getEvents().getFirst().getAttributes().get(AttributeKey.stringKey("db.password")))
                .isNull();
    }

    @Test
    @DisplayName("DROP_OLDEST still acknowledges export mode after a single wrapped exporter")
    void dropOldestResolvesExportModeThroughTheSameWrap() {
        // given
        ScrubbingRuntime runtime = runtimeOfPassword();
        PlatformTracingMBeanRegistrar registrar = new PlatformTracingMBeanRegistrar();
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
        }, TracingSettingsSource.isolatedDefaults());

        ExtensionConfig dropOldest = new ExtensionConfig(stringConfig("DROP_OLDEST"));

        // when
        factory.maybeReplaceExportProcessor(
                SimpleSpanProcessor.create(wrapped),
                dropOldest.queue(),
                stringConfig("DROP_OLDEST"));

        // then
        assertThat(registrar.extensionReadiness().has(PlatformExtensionCapability.EXPORT_PATH_PROTECTED)
                || registrar.extensionReadiness().has(PlatformExtensionCapability.SAFE_EXPORTER_INSTALLED))
                .isTrue();
    }

    @Test
    @DisplayName("Foreign SafeSpanExporter is rejected at startup instead of being wrapped twice")
    void foreignSafeSpanExporterIsRejectedAtStartup() {
        // given
        ScrubbingRuntime session = runtimeOfPassword();
        List<SpanData> captured = new ArrayList<>();
        SafeSpanExporter foreign = new SafeSpanExporter(
                capturing(captured), TracingSettingsSource.isolatedDefaults(),
                ScrubbingRuntimeTestFixtures.disabled());
        assertThat(foreign.isProtectedBy(session)).isFalse();

        PlatformExportProcessorFactory factory = new PlatformExportProcessorFactory(
                new PlatformTracingMBeanRegistrar(), session);

        // when
        Throwable failure = catchThrowable(
                () -> factory.captureExporter(foreign, TracingSettingsSource.isolatedDefaults()));

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
                capturing(captured), TracingSettingsSource.isolatedDefaults(), session);
        assertThat(lookalike.isProtectedBy(session))
                .as("runtime identity matches, but this instance was never produced by the factory")
                .isTrue();

        PlatformExportProcessorFactory factory = new PlatformExportProcessorFactory(
                new PlatformTracingMBeanRegistrar(), session);

        // when
        Throwable failure = catchThrowable(
                () -> factory.captureExporter(lookalike, TracingSettingsSource.isolatedDefaults()));

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
                new PlatformTracingMBeanRegistrar(), session);

        SpanExporter firstCapture = factory.captureExporter(capturing(captured), TracingSettingsSource.isolatedDefaults());
        SpanExporter secondCapture = factory.captureExporter(firstCapture, TracingSettingsSource.isolatedDefaults());

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

    private static ConfigProperties stringConfig(String value) {
        return new ConfigProperties() {
            @Override public String getString(String name) {
                return ExtensionPropertyNames.QUEUE_OVERFLOW_POLICY.equals(name) ? value : null;
            }
            @Override public Boolean getBoolean(String name) { return null; }
            @Override public Integer getInt(String name) { return null; }
            @Override public Long getLong(String name) { return null; }
            @Override public Double getDouble(String name) { return null; }
            @Override public Duration getDuration(String name) { return null; }
            @Override public List<String> getList(String name) { return null; }
            @Override public Map<String, String> getMap(String name) { return null; }
        };
    }
}
