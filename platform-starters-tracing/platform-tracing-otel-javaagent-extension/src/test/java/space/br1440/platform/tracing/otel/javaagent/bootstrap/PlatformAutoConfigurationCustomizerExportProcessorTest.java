package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.WIRE_INITIALIZING;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.DiagnosticReadinessMBeanAssert.assertThatDiagnosticMBean;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.otel.javaagent.AutoConfiguredSdkIsolationExtension;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.exporter.SafeSpanExporter;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.DomainObjectNames;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxAssessorTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.processor.dropoldest.PlatformDropOldestExportSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionCapability;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionLifecycle;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.assembly.PlatformScrubberFactory;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.SanitizerBootstrapException;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntimeTestFixtures;

import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdkBuilder;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigurationException;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;

@ExtendWith(AutoConfiguredSdkIsolationExtension.class)
class PlatformAutoConfigurationCustomizerExportProcessorTest {

    private static BootstrapAutoConfigurationCustomizerProvider newProvider() {
        return new BootstrapAutoConfigurationCustomizerProvider(RuntimeEvidenceTestFixtures.coordinator());
    }

    private static AutoConfiguredOpenTelemetrySdkBuilder baseBuilder() {
        return AutoConfiguredOpenTelemetrySdk.builder()
                .setServiceClassLoader(
                        AutoConfigurationCustomizerTestClassLoader.withoutDiscoveredCustomizers())
                .disableShutdownHook()
                .addPropertiesSupplier(() -> Map.of(
                        "otel.metrics.exporter", "none",
                        "otel.logs.exporter", "none",
                        "otel.traces.exporter", "otlp",
                        "otel.exporter.otlp.endpoint", "http://127.0.0.1:1",
                        "otel.bsp.schedule.delay", "600000",
                        "otel.bsp.export.timeout", "1000"
                ));
    }

    @Test
    @DisplayName("Single OTLP exporter: платформенный DROP_OLDEST processor")
    void singleOtlpExporterInstallsPlatformDropOldestProcessor() {
        // given
        AtomicReference<SpanProcessor> observedFinalProcessor = new AtomicReference<>();
        AutoConfiguredOpenTelemetrySdkBuilder builder = baseBuilder();
        newProvider().customize(builder);
        builder.addSpanProcessorCustomizer((processor, cfg) -> {
            observedFinalProcessor.set(processor);
            return processor;
        });

        // when
        AutoConfiguredOpenTelemetrySdk sdk = builder.build();
        try {
            // then
            assertThat(observedFinalProcessor.get())
                    .as("финальный processor — платформенный DROP_OLDEST")
                    .isInstanceOf(PlatformDropOldestExportSpanProcessor.class);
            assertThat(observedFinalProcessor.get())
                    .as("финальный processor — не stock BatchSpanProcessor")
                    .isNotInstanceOf(BatchSpanProcessor.class);
        } finally {
            sdk.getOpenTelemetrySdk().getSdkTracerProvider().shutdown().join(2, TimeUnit.SECONDS);
            sdk.getOpenTelemetrySdk().close();
        }
    }

    @Test
    @ResourceLock(AgentLogCapture.RESOURCE_LOCK)
    @DisplayName("Multiple exporters keep the stock OTLP BatchSpanProcessor")
    void multipleExportersKeepStockBatchSpanProcessor() {
        try (AgentLogCapture logs = AgentLogCapture.installed()) {
            // given
            List<SpanProcessor> observedProcessors = new ArrayList<>();
            AutoConfiguredOpenTelemetrySdkBuilder builder = AutoConfiguredOpenTelemetrySdk.builder()
                    .setServiceClassLoader(
                            AutoConfigurationCustomizerTestClassLoader.withoutDiscoveredCustomizers())
                    .disableShutdownHook()
                    .addPropertiesSupplier(() -> Map.of(
                            "otel.metrics.exporter", "none",
                            "otel.logs.exporter", "none",
                            "otel.traces.exporter", "otlp,logging",
                            "otel.exporter.otlp.endpoint", "http://127.0.0.1:1",
                            "otel.bsp.schedule.delay", "600000",
                            "otel.bsp.export.timeout", "1000"
                    ));
            newProvider().customize(builder);
            builder.addSpanProcessorCustomizer((processor, config) -> {
                observedProcessors.add(processor);
                return processor;
            });

            // when
            AutoConfiguredOpenTelemetrySdk sdk = builder.build();

            try {
                // then
                assertThat(observedProcessors)
                        .filteredOn(BatchSpanProcessor.class::isInstance)
                        .singleElement()
                        .isExactlyInstanceOf(BatchSpanProcessor.class);
                assertThat(observedProcessors)
                        .noneMatch(PlatformDropOldestExportSpanProcessor.class::isInstance);
                assertThat(logs.events())
                        .filteredOn(event -> "MULTIPLE_SPAN_EXPORTERS_FALLBACK".equals(event.eventName()))
                        .isNotEmpty()
                        .allSatisfy(event -> assertThat(event.fields()).containsEntry("count", "2"));
            } finally {
                sdk.getOpenTelemetrySdk().close();
            }
        }
    }


    @Test
    @DisplayName("Foreign SafeSpanExporter fails the real customizer chain before readiness")
    void foreignSafeSpanExporterFailsTheRealCustomizerChainBeforeReadiness() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        BootstrapCoordinator bootstrap = coordinator(server);
        AutoConfiguredOpenTelemetrySdkBuilder builder = baseBuilder();
        builder.addSpanExporterCustomizer((exporter, config) -> new SafeSpanExporter(
                exporter, TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()), ScrubbingRuntimeTestFixtures.disabled()));
        new BootstrapAutoConfigurationCustomizerProvider(bootstrap).customize(builder);

        try (bootstrap) {
            // when
            Throwable failure = catchThrowable(builder::build);

            // then
            assertThat(failure).isInstanceOf(ConfigurationException.class);
            assertThat(failure.getCause()).isInstanceOfSatisfying(BootstrapException.class,
                    bootstrapFailure -> assertThat(bootstrapFailure.failure().effectiveCode())
                            .isEqualTo(SanitizerBootstrapException.FOREIGN_SAFE_EXPORTER));
            assertThat(bootstrap.currentWire().lifecycle()).isEqualTo("FAILED");
            assertThat(bootstrap.currentWire().failureCode())
                    .isEqualTo(SanitizerBootstrapException.FOREIGN_SAFE_EXPORTER);
            assertThat(bootstrap.currentWire().capabilities())
                    .doesNotContain(PlatformExtensionCapability.SAFE_EXPORTER_INSTALLED.name());
            assertThat(server.isRegistered(DomainObjectNames.EXTENSION_READINESS)).isTrue();
        }

        assertThat(server.isRegistered(DomainObjectNames.EXTENSION_READINESS)).isFalse();
    }

    @Test
    @DisplayName("Hostless OTLP endpoint is rejected without publishing a READY snapshot")
    void hostlessOtlpEndpointIsRejectedWithoutPublishingReadySnapshot() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        BootstrapCoordinator bootstrap = coordinator(server);
        AutoConfiguredOpenTelemetrySdkBuilder builder = AutoConfiguredOpenTelemetrySdk.builder()
                .setServiceClassLoader(
                        AutoConfigurationCustomizerTestClassLoader.withoutDiscoveredCustomizers())
                .disableShutdownHook()
                .addPropertiesSupplier(() -> Map.of(
                        "otel.metrics.exporter", "none",
                        "otel.logs.exporter", "none",
                        "otel.traces.exporter", "otlp",
                        "otel.exporter.otlp.endpoint", "http" + ":///v1/traces"
                ));
        new BootstrapAutoConfigurationCustomizerProvider(bootstrap).customize(builder);

        try (bootstrap) {
            // when
            Throwable failure = catchThrowable(builder::build);

            // then
            assertSdkBuildAbandonedAfterPrepare(server, bootstrap, failure, "OtlpConfigUtil");
            assertThat(bootstrap.session().readiness().snapshot().lifecycle())
                    .isNotEqualTo(PlatformExtensionLifecycle.READY);
            assertThat(bootstrap.currentWire().lifecycle()).isNotEqualTo("READY");
        }
    }

    @Test
    @DisplayName("Failing log record exporter abandons PREPARED with diagnostic readiness still INITIALIZING")
    void failingLogRecordExporterAbandonsPreparedWithDiagnosticReadinessInitializing() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        BootstrapCoordinator bootstrap = coordinator(server);
        AutoConfiguredOpenTelemetrySdkBuilder builder = AutoConfiguredOpenTelemetrySdk.builder()
                .setServiceClassLoader(
                        AutoConfigurationCustomizerTestClassLoader.withoutDiscoveredCustomizers())
                .disableShutdownHook()
                .addPropertiesSupplier(() -> Map.of(
                        "otel.metrics.exporter", "none",
                        "otel.traces.exporter", "none",
                        "otel.logs.exporter", FailingLogRecordExporterProvider.NAME
                ));
        new BootstrapAutoConfigurationCustomizerProvider(bootstrap).customize(builder);

        try (bootstrap) {
            // when
            Throwable failure = catchThrowable(builder::build);

            // then
            assertSdkBuildAbandonedAfterPrepare(
                    server,
                    bootstrap,
                    failure,
                    FailingLogRecordExporterProvider.class.getName()
            );
        }

        assertThat(server.isRegistered(DomainObjectNames.EXTENSION_READINESS)).isFalse();
    }

    private static void assertSdkBuildAbandonedAfterPrepare(MBeanServer server,
                                                            BootstrapCoordinator bootstrap,
                                                            Throwable failure,
                                                            String upstreamFrame) {
        assertThat(failure).isInstanceOf(ConfigurationException.class);
        assertThat(causalChain(failure)).noneMatch(BootstrapException.class::isInstance);
        assertThat(failure).hasStackTraceContaining(upstreamFrame);
        assertThat(bootstrap.state()).isEqualTo(BootstrapState.PREPARED);
        assertThat(server.isRegistered(DomainObjectNames.EXTENSION_READINESS)).isTrue();
        assertThatDiagnosticMBean(server)
                .hasSnapshotEntry(
                        JmxWireContract.FIELD_READINESS_SNAPSHOT_LIFECYCLE,
                        WIRE_INITIALIZING
                )
                .hasSnapshotEntry(
                        JmxWireContract.FIELD_READINESS_SNAPSHOT_AGENT_INSTANCE_ID,
                        bootstrap.session().agentInstanceId().toString()
                );
        assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isFalse();
        assertThat(server.isRegistered(DomainObjectNames.SCRUBBING)).isFalse();
        assertThat(server.isRegistered(DomainObjectNames.VALIDATION)).isFalse();
        assertThat(server.isRegistered(DomainObjectNames.EXPORT)).isFalse();
        assertThat(server.isRegistered(DomainObjectNames.PROCESSOR_METRICS)).isFalse();
        assertThat(server.isRegistered(DomainObjectNames.DIAGNOSTICS)).isFalse();
        assertThat(server.isRegistered(DomainObjectNames.SETTINGS_STATUS)).isFalse();
        assertThat(server.isRegistered(DomainObjectNames.SETTINGS_MUTATION)).isFalse();
    }

    private static List<Throwable> causalChain(Throwable failure) {
        List<Throwable> chain = new ArrayList<>();
        Throwable current = failure;
        while (current != null && !chain.contains(current)) {
            chain.add(current);
            current = current.getCause();
        }
        return chain;
    }

    private static BootstrapCoordinator coordinator(MBeanServer server) {
        return new BootstrapCoordinator(
                (threadName, action) -> {
                },
                new PlatformScrubberFactory(),
                RuntimeEvidenceTestFixtures.trustedVerifier(),
                ignored -> RemoteJmxAssessorTestFixtures.allowingAssessor(),
                new PlatformTracingMBeanRegistrar(server, AgentInstanceId.random()),
                new DiagnosticReadinessHandle()
        );
    }
}
