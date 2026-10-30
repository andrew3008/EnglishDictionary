package space.br1440.platform.tracing.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.util.ClassUtils;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationHeaders;
import space.br1440.platform.tracing.api.propagation.control.PlatformOutboundPropagation;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpanSpecReason;
import space.br1440.platform.tracing.autoconfigure.control.channel.TracingControlChannelAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.control.settings.AcceptedTracingSettingsReader;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsWriter;
import space.br1440.platform.tracing.autoconfigure.diagnostics.TracingRuntimeDiagnosticsAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.integrations.kafka.PlatformKafkaProducerFactoryCustomizer;
import space.br1440.platform.tracing.autoconfigure.metrics.MeteredTracingRuntime;
import space.br1440.platform.tracing.autoconfigure.metrics.PlatformTracingMetrics;
import space.br1440.platform.tracing.autoconfigure.metrics.TracingMetricsAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.support.ControlledAgentTestRuntime;
import space.br1440.platform.tracing.autoconfigure.support.EarlyTracingMetricsConsumerAutoConfiguration;
import space.br1440.platform.tracing.otel.facade.DefaultTraceOperations;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;
import space.br1440.platform.tracing.otel.runtime.otel.OtelTracingRuntime;

import io.micrometer.core.instrument.MeterRegistry;

class AutoConfigurationImportsGraphTest {

    /**
     * Граф реальных Boot-автоконфигураций metrics: регистратор реестра ({@code MetricsAutoConfiguration}),
     * composite-обёртка и единственный exporter. Используется тестами, доказывающими подключение
     * binder'ов к живому {@code MeterRegistry}, а не только регистрацию бинов.
     */
    private static final Class<?>[] BOOT_METRICS_GRAPH = {
            MetricsAutoConfiguration.class,
            CompositeMeterRegistryAutoConfiguration.class,
            SimpleMetricsExportAutoConfiguration.class
    };

    private static final Path MODULE_IMPORTS = Path.of(
            "src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"
    );

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(moduleImports()));

    private final ApplicationContextRunner bootMetricsRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(withBootMetricsGraph(moduleImports())));

    @Test
    void moduleImportLinesAreBootCandidates() {
        // given
        List<String> lines = moduleImportLines();
        List<String> candidates = ImportCandidates.load(
                AutoConfiguration.class,
                AutoConfigurationImportsGraphTest.class.getClassLoader()
        ).getCandidates();

        // then
        assertThat(lines).isNotEmpty();
        assertThat(candidates).containsAll(lines);
        for (String line : lines) {
            assertThat(ClassUtils.resolveClassName(line, AutoConfigurationImportsGraphTest.class.getClassLoader()))
                    .isNotNull();
        }
    }

    @Test
    void disabledConfigurationStartsWithoutPlatformOutboundPropagation() {
        // when
        runner
                .withPropertyValues("platform.tracing.enabled=false")
                .run(context -> {
                    // then
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(TraceOperations.class);
                    assertThat(context).doesNotHaveBean(PlatformOutboundPropagation.class);
                    assertThat(context.getBeansOfType(AcceptedTracingSettingsReader.class)).isEmpty();
                    assertThat(context.getBeansOfType(TracingSettingsWriter.class)).isEmpty();
                });
    }

    @Test
    void enabledWithoutAgentFailsClosed() {
        // when
        staticChannel()
                .withPropertyValues("platform.tracing.enabled=true")
                .run(context -> {
                    // then
                    assertFailureContains(context, "CONTROLLED_AGENT_MISSING");
                });
    }

    @Test
    void missingControlChannelFailsClosed() {
        // when
        runner.run(context -> {
            // then
            assertFailureContains(context, "Missing required property 'platform.tracing.control.channel'");
        });
    }

    @Test
    void userPropagationRemainsWithoutKafkaCustomizerWhenTracingDisabled() {
        // given
        PlatformOutboundPropagation user = (decision, control) -> OutboundPropagationHeaders.EMPTY;

        // when
        disabled()
                .withPropertyValues("platform.tracing.kafka.propagate-platform-headers=true")
                .withBean(PlatformOutboundPropagation.class, () -> user)
                .run(context -> {
                    // then
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(PlatformOutboundPropagation.class);
                    assertThat(context.getBean(PlatformOutboundPropagation.class)).isSameAs(user);
                    assertThat(context).doesNotHaveBean(PlatformKafkaProducerFactoryCustomizer.class);
                });
    }

    @Test
    void customizerAbsentWithoutUserPropagationWhenHeadersEnabled() {
        // when
        disabled()
                .withPropertyValues("platform.tracing.kafka.propagate-platform-headers=true")
                .run(context -> {
                    // then
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PlatformOutboundPropagation.class);
                    assertThat(context).doesNotHaveBean(PlatformKafkaProducerFactoryCustomizer.class);
                });
    }

    @Test
    void customizerAbsentWhenPlatformHeadersAreNotPropagated() {
        // given
        PlatformOutboundPropagation user = (decision, control) -> OutboundPropagationHeaders.EMPTY;

        // when
        disabled()
                .withPropertyValues("platform.tracing.kafka.propagate-platform-headers=false")
                .withBean(PlatformOutboundPropagation.class, () -> user)
                .run(context -> {
                    // then
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(PlatformOutboundPropagation.class);
                    assertThat(context.getBean(PlatformOutboundPropagation.class)).isSameAs(user);
                    assertThat(context).doesNotHaveBean(PlatformKafkaProducerFactoryCustomizer.class);
                });
    }

    @Test
    void kafkaClassesHiddenOmitsProducerCustomizer() {
        // given
        PlatformOutboundPropagation user = (decision, control) -> OutboundPropagationHeaders.EMPTY;

        // when
        disabled()
                .withClassLoader(new FilteredClassLoader(
                        "org.springframework.kafka",
                        "org.apache.kafka.clients"))
                .withPropertyValues("platform.tracing.kafka.propagate-platform-headers=true")
                .withBean(PlatformOutboundPropagation.class, () -> user)
                .run(context -> {
                    // then
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PlatformKafkaProducerFactoryCustomizer.class);
                });
    }

    @Test
    void jmx_binders_are_bound_with_boot_metrics_graph() {
        // when
        bootMetricsDisabled().run(context -> {
            // then
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(PlatformTracingMetrics.class);
            assertThat(context).hasBean("platformTracingDiagnosticsCaptureSource");
            assertThat(context).hasBean("platformTracingSamplerMetricsBinder");
            assertThat(context).hasBean("platformTracingSafeWrapperMetricsBinder");
            assertThat(context).hasBean("platformTracingJmxDomainHealthMetricsBinder");
            assertThat(context).hasBean("platformTracingExportMetricsBinder");
            assertThat(context).hasBean("platformTracingScrubbingMetricsBinder");
            assertThat(context).hasBean("platformTracingProcessorMetricsBinder");

            MeterRegistry registry = context.getBean(MeterRegistry.class);
            assertThat(registry.find("platform.tracing.span_attributes.validation.disabled").gauge())
                    .isNotNull();
            assertThat(registry.find("platform.tracing.jmx.domain.registered").gauges())
                    .hasSize(6);
        });
    }

    @Test
    void metrics_are_wired_into_enabled_runtime_with_boot_metrics_graph() {
        // given
        SpanSpec invalidSpec = SpanSpec.builder("graph.invalid")
                .category(SpanCategory.INTERNAL)
                .reason(SpanSpecReason.LEGACY_INTEGRATION)
                .build();

        // when
        bootMetricsStaticChannel()
                .withPropertyValues("platform.tracing.enabled=true")
                .withInitializer(ControlledAgentTestRuntime::initializeWithExporter)
                .run(context -> {
                    // then
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(TraceOperations.class)).isInstanceOf(DefaultTraceOperations.class);
                    assertThat(context.getBeansOfType(TracingRuntime.class)).hasSize(1);
                    assertThat(context.getBean(TracingRuntime.class)).isInstanceOf(MeteredTracingRuntime.class);
                    assertThat(((MeteredTracingRuntime) context.getBean(TracingRuntime.class)).delegate())
                            .isInstanceOf(OtelTracingRuntime.class);

                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    TraceOperations tracing = context.getBean(TraceOperations.class);

                    tracing.spans().operation("graph-op").start().close();
                    assertThat(registry.find(PlatformTracingMetrics.SPANS_STARTED).counters()).isNotEmpty();

                    tracing.spans().fromSpec(invalidSpec).start().close();
                    assertThat(registry.find("platform.tracing.span_attributes.validation.violations").counters())
                            .isNotEmpty();
                });
    }

    @Test
    void metrics_are_absent_without_meter_registry() {
        // when
        disabled().run(context -> {
            // then
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(PlatformTracingMetrics.class);
            assertThat(context).doesNotHaveBean("platformTracingDiagnosticsCaptureSource");
            assertThat(context).doesNotHaveBean("platformTracingSamplerMetricsBinder");
            assertThat(context).doesNotHaveBean("platformTracingSafeWrapperMetricsBinder");
            assertThat(context).doesNotHaveBean("platformTracingJmxDomainHealthMetricsBinder");
            assertThat(context).doesNotHaveBean("platformTracingExportMetricsBinder");
            assertThat(context).doesNotHaveBean("platformTracingScrubbingMetricsBinder");
            assertThat(context).doesNotHaveBean("platformTracingProcessorMetricsBinder");
        });
    }

    @Test
    void metrics_declares_only_boot_registry_ordering() {
        // given
        MergedAnnotations annotations = MergedAnnotations.from(TracingMetricsAutoConfiguration.class);
        MergedAnnotation<AutoConfigureAfter> after = annotations.get(AutoConfigureAfter.class);
        MergedAnnotation<AutoConfigureBefore> before = annotations.get(AutoConfigureBefore.class);

        // when / then
        assertThat(after.getClassArray("value"))
                .containsExactlyInAnyOrder(MetricsAutoConfiguration.class, CompositeMeterRegistryAutoConfiguration.class);
        assertThat(after.getStringArray("name")).isEmpty();
        assertThat(before.getClassArray("value")).isEmpty();
        assertThat(before.getStringArray("name")).isEmpty();
    }

    @Test
    void metrics_follow_boot_registry_when_pulled_ahead_by_early_consumer() {
        // when
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        TracingControlChannelAutoConfiguration.class,
                        TracingRuntimeAutoConfiguration.class,
                        TracingRuntimeDiagnosticsAutoConfiguration.class,
                        TracingMetricsAutoConfiguration.class,
                        MetricsAutoConfiguration.class,
                        CompositeMeterRegistryAutoConfiguration.class,
                        SimpleMetricsExportAutoConfiguration.class,
                        EarlyTracingMetricsConsumerAutoConfiguration.class))
                .withPropertyValues(
                        "platform.tracing.control.channel=NONE",
                        "platform.tracing.enabled=false")
                .run(context -> {
                    // then
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(PlatformTracingMetrics.class);
                    assertThat(context.getBean(MeterRegistry.class)
                            .find("platform.tracing.jmx.domain.registered").gauges())
                            .hasSize(6);
                });
    }

    private ApplicationContextRunner staticChannel() {
        return runner.withPropertyValues("platform.tracing.control.channel=NONE");
    }

    private ApplicationContextRunner disabled() {
        return staticChannel().withPropertyValues("platform.tracing.enabled=false");
    }

    private ApplicationContextRunner bootMetricsStaticChannel() {
        return bootMetricsRunner.withPropertyValues("platform.tracing.control.channel=NONE");
    }

    private ApplicationContextRunner bootMetricsDisabled() {
        return bootMetricsStaticChannel().withPropertyValues("platform.tracing.enabled=false");
    }

    private static Class<?>[] moduleImports() {
        return moduleImportLines().stream()
                .map(line -> ClassUtils.resolveClassName(line, AutoConfigurationImportsGraphTest.class.getClassLoader()))
                .toArray(Class<?>[]::new);
    }

    private static Class<?>[] withBootMetricsGraph(Class<?>[] imports) {
        Class<?>[] combined = Arrays.copyOf(imports, imports.length + BOOT_METRICS_GRAPH.length);
        System.arraycopy(BOOT_METRICS_GRAPH, 0, combined, imports.length, BOOT_METRICS_GRAPH.length);
        return combined;
    }

    private static List<String> moduleImportLines() {
        try {
            return Files.readAllLines(MODULE_IMPORTS).stream()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static void assertFailureContains(AssertableApplicationContext context, String fragment) {
        assertThat(context).hasFailed();

        Throwable failure = context.getStartupFailure();
        assertThat(failure).isNotNull();

        String causeMessage = failure.getCause() == null ? "" : String.valueOf(failure.getCause().getMessage());
        assertThat(failure.getMessage() + " / " + causeMessage).contains(fragment);
    }
}
