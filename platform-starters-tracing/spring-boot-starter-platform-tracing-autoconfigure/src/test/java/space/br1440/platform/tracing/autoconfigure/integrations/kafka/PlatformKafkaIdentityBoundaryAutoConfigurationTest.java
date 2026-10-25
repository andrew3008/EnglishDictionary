package space.br1440.platform.tracing.autoconfigure.integrations.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.span.enrich.SpanEnricher;
import space.br1440.platform.tracing.autoconfigure.TracingRuntimeAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.control.channel.TracingControlChannelAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.identity.RequestIdentityBoundarySupport;
import space.br1440.platform.tracing.autoconfigure.integrations.errorhandling.RequestContextSupplierAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.span.SpanEnrichmentAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.support.ControlledAgentTestRuntime;
import space.br1440.platform.tracing.otel.facade.NoopTraceOperations;
import space.br1440.platform.tracing.otel.runtime.NoOpTracingRuntime;
import space.br1440.platform.tracing.otel.span.enrich.DefaultSpanEnricher;

class PlatformKafkaIdentityBoundaryAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlatformKafkaAutoConfiguration.class))
            .withBean(TraceOperations.class, () -> NoopTraceOperations.INSTANCE)
            .withBean(RequestIdentityBoundarySupport.class,
                    () -> new RequestIdentityBoundarySupport(NoOpTracingRuntime.noop()))
            .withBean(SpanEnricher.class, DefaultSpanEnricher::new)
            .withPropertyValues("platform.tracing.kafka.propagate-platform-headers=true");

    private final ApplicationContextRunner runnerWithoutEnricher = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlatformKafkaAutoConfiguration.class))
            .withBean(TraceOperations.class, () -> NoopTraceOperations.INSTANCE)
            .withBean(RequestIdentityBoundarySupport.class,
                    () -> new RequestIdentityBoundarySupport(NoOpTracingRuntime.noop()))
            .withPropertyValues("platform.tracing.enabled=true");

    @Test
    void registersKafkaRequestIdentityAspectByDefault() {
        // when
        runner.run(context -> {
            // then
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(KafkaRequestIdentityAspect.class);
        });
    }

    @Test
    void tracingDisabledOmitsKafkaRequestIdentityAspect() {
        // when
        runner.withPropertyValues("platform.tracing.enabled=false").run(context -> {
            // then
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(KafkaRequestIdentityAspect.class);
        });
    }

    @Test
    void missingSpanEnricherFailsBecauseEnricherIsRequired() {
        // when
        runnerWithoutEnricher.run(context -> {
            // then
            assertThat(context).hasFailed();
            assertThat(failureText(context.getStartupFailure())).contains("SpanEnricher");
        });
    }

    @Test
    void sameRunnerWithEnrichmentStarts() {
        // when
        runnerWithoutEnricher
                .withConfiguration(AutoConfigurations.of(SpanEnrichmentAutoConfiguration.class))
                .run(context -> {
                    // then
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(KafkaRequestIdentityAspect.class);
                });
    }

    @Test
    void kafkaAspectUsesTraceOperationsFromTracingRuntimeAutoConfiguration() {
        // given
        assertThat(PlatformKafkaAutoConfiguration.class.getAnnotation(AutoConfiguration.class).after())
                .contains(TracingRuntimeAutoConfiguration.class);
        ApplicationContextRunner runtimeRunner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        PlatformKafkaAutoConfiguration.class,
                        TracingControlChannelAutoConfiguration.class,
                        TracingRuntimeAutoConfiguration.class,
                        SpanEnrichmentAutoConfiguration.class,
                        RequestContextSupplierAutoConfiguration.class))
                .withPropertyValues("platform.tracing.control.channel=NONE")
                .withInitializer(ControlledAgentTestRuntime::initialize);

        // when
        runtimeRunner.run(context -> {
            // then
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(KafkaRequestIdentityAspect.class);
        });
    }

    private static String failureText(Throwable failure) {
        StringBuilder text = new StringBuilder();
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null) {
                text.append(current.getMessage()).append('\n');
            }
            current = current.getCause();
        }
        return text.toString();
    }
}
