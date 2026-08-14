package space.br1440.platform.tracing.otel.javaagent.factory;

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
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.DomainMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionCapability;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.SanitizerBootstrapException;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingRuntime;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.circuitbreaker.RuleCircuitBreaker;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.ExecutableRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.RuleOrigin;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.RuntimeRule;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * P5: UPSTREAM and DROP_OLDEST share the same {@code SafeSpanExporter} boundary wrap,
 * and {@code wrappedExporterCount} counts every wrapped exporter.
 */
class PlatformExportProcessorFactoryBoundaryTest {

    @Test
    @DisplayName("captureExporter wraps every exporter; UPSTREAM keeps the wrapped path")
    void upstreamModeExportsThroughTheBoundaryWrap() {
        ScrubbingRuntime runtime = runtimeOfPassword();
        DomainMBeanRegistrar registrar = new DomainMBeanRegistrar();
        PlatformExportProcessorFactory factory = new PlatformExportProcessorFactory(registrar, runtime);
        List<SpanData> captured = new ArrayList<>();
        SpanExporter wrapped = factory.captureExporter(capturing(captured), TracingSettingsSource.isolatedDefaults());

        ExtensionConfig upstream = new ExtensionConfig(stringConfig(
                ExtensionPropertyNames.QUEUE_OVERFLOW_POLICY, "UPSTREAM"));
        SpanProcessor stock = SimpleSpanProcessor.create(wrapped);
        SpanProcessor result = factory.maybeReplaceExportProcessor(
                stock, upstream.queue(), stringConfig(ExtensionPropertyNames.QUEUE_OVERFLOW_POLICY, "UPSTREAM"));

        assertThat(result).isSameAs(stock);
        assertThat(runtime.installation().wrappedExporterCount()).isEqualTo(1);
        assertThat(registrar.extensionReadiness().has(PlatformExtensionCapability.EXPORT_PATH_PROTECTED)).isTrue();

        wrapped.export(List.of(spanWithPasswordEvent()));
        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).getEvents().get(0).getAttributes().get(AttributeKey.stringKey("db.password")))
                .isNull();
    }

    @Test
    @DisplayName("wrappedExporterCount increments for each captureExporter call")
    void eachCapturedExporterIncrementsWrappedCount() {
        ScrubbingRuntime runtime = runtimeOfPassword();
        PlatformExportProcessorFactory factory = new PlatformExportProcessorFactory(
                new DomainMBeanRegistrar(), runtime);

        factory.captureExporter(capturing(new ArrayList<>()), TracingSettingsSource.isolatedDefaults());
        factory.captureExporter(capturing(new ArrayList<>()), TracingSettingsSource.isolatedDefaults());

        assertThat(runtime.installation().wrappedExporterCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("DROP_OLDEST still acknowledges export mode after a single wrapped exporter")
    void dropOldestResolvesExportModeThroughTheSameWrap() {
        ScrubbingRuntime runtime = runtimeOfPassword();
        DomainMBeanRegistrar registrar = new DomainMBeanRegistrar();
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

        ExtensionConfig dropOldest = new ExtensionConfig(stringConfig(
                ExtensionPropertyNames.QUEUE_OVERFLOW_POLICY, "DROP_OLDEST"));
        factory.maybeReplaceExportProcessor(
                SimpleSpanProcessor.create(wrapped),
                dropOldest.queue(),
                stringConfig(ExtensionPropertyNames.QUEUE_OVERFLOW_POLICY, "DROP_OLDEST"));

        assertThat(runtime.installation().wrappedExporterCount()).isEqualTo(1);
        assertThat(registrar.extensionReadiness().has(PlatformExtensionCapability.EXPORT_PATH_PROTECTED)
                || registrar.extensionReadiness().has(PlatformExtensionCapability.SAFE_EXPORTER_INSTALLED))
                .isTrue();
    }

    @Test
    @DisplayName("Foreign SafeSpanExporter is rejected at startup instead of being wrapped twice")
    void foreignSafeSpanExporterIsRejectedAtStartup() {
        ScrubbingRuntime session = runtimeOfPassword();
        List<SpanData> captured = new ArrayList<>();
        SafeSpanExporter foreign = new SafeSpanExporter(
                capturing(captured), TracingSettingsSource.isolatedDefaults(), ScrubbingRuntime.disabled());
        assertThat(foreign.isProtectedBy(session)).isFalse();

        PlatformExportProcessorFactory factory = new PlatformExportProcessorFactory(
                new DomainMBeanRegistrar(), session);

        Throwable failure = catchThrowable(
                () -> factory.captureExporter(foreign, TracingSettingsSource.isolatedDefaults()));

        assertThat(failure)
                .isInstanceOf(SanitizerBootstrapException.class)
                .extracting(t -> ((SanitizerBootstrapException) t).code())
                .isEqualTo(SanitizerBootstrapException.FOREIGN_SAFE_EXPORTER);
        assertThat(session.installation().wrappedExporterCount())
                .as("installation barrier must stay incomplete")
                .isZero();
        assertThat(catchThrowable(() -> session.installation().requireComplete()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Same-runtime identity alone does not prove factory provenance: a manually-built "
            + "SafeSpanExporter is rejected even when it shares the session runtime (H-4)")
    void manuallyBuiltSameRuntimeExporterIsRejectedDespiteMatchingRuntimeIdentity() {
        ScrubbingRuntime session = runtimeOfPassword();
        List<SpanData> captured = new ArrayList<>();
        SafeSpanExporter lookalike = new SafeSpanExporter(
                capturing(captured), TracingSettingsSource.isolatedDefaults(), session);
        assertThat(lookalike.isProtectedBy(session))
                .as("runtime identity matches, but this instance was never produced by the factory")
                .isTrue();

        PlatformExportProcessorFactory factory = new PlatformExportProcessorFactory(
                new DomainMBeanRegistrar(), session);

        Throwable failure = catchThrowable(
                () -> factory.captureExporter(lookalike, TracingSettingsSource.isolatedDefaults()));

        assertThat(failure)
                .isInstanceOf(SanitizerBootstrapException.class)
                .extracting(t -> ((SanitizerBootstrapException) t).code())
                .isEqualTo(SanitizerBootstrapException.FOREIGN_SAFE_EXPORTER);
        assertThat(session.installation().wrappedExporterCount()).isZero();
    }

    @Test
    @DisplayName("Re-entry with this factory's own ReplacementAwareSpanExporter output does not re-wrap "
            + "or run the boundary a second time")
    void ownReplacementAwareOutputIsShortCircuitedOnReentry() {
        ScrubbingRuntime session = runtimeOfPassword();
        List<SpanData> captured = new ArrayList<>();
        PlatformExportProcessorFactory factory = new PlatformExportProcessorFactory(
                new DomainMBeanRegistrar(), session);

        SpanExporter firstCapture = factory.captureExporter(capturing(captured), TracingSettingsSource.isolatedDefaults());
        SpanExporter secondCapture = factory.captureExporter(firstCapture, TracingSettingsSource.isolatedDefaults());

        assertThat(secondCapture).isSameAs(firstCapture);
        secondCapture.export(List.of(spanWithPasswordEvent()));

        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).getEvents().get(0).getAttributes().get(AttributeKey.stringKey("db.password")))
                .isNull();
        assertThat(session.installation().wrappedExporterCount())
                .as("re-entry with the factory's own output must not increment the wrap counter again")
                .isEqualTo(1);
        assertThat(session.boundaryInputSpansAdder().sum())
                .as("exactly one boundary pass, not one per re-entry")
                .isEqualTo(1L);
    }

    private static ScrubbingRuntime runtimeOfPassword() {
        SpanAttributeScrubbingRule password = BuiltInSpanAttributeScrubbingRules.resolve("password");
        return ScrubbingRuntime.create(
                List.of(new RuntimeRule(
                        new ExecutableRule(password, new RuleCircuitBreaker(password.name())),
                        RuleOrigin.BUILT_IN)),
                1L, "test", null, false);
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

    private static ConfigProperties stringConfig(String key, String value) {
        return new ConfigProperties() {
            @Override public String getString(String name) { return key.equals(name) ? value : null; }
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
