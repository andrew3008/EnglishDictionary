package space.br1440.platform.tracing.autoconfigure.integrations.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationHeaders;
import space.br1440.platform.tracing.api.propagation.control.PlatformOutboundPropagation;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.TracingRuntimeAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.control.channel.TracingControlChannelAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.identity.RequestIdentityBoundarySupport;
import space.br1440.platform.tracing.autoconfigure.integrations.errorhandling.RequestContextSupplierAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.propagation.PlatformOutboundPropagationAutoConfiguration;
import space.br1440.platform.tracing.otel.facade.NoopTraceOperations;
import space.br1440.platform.tracing.otel.runtime.NoOpTracingRuntime;

class PlatformKafkaOutboundAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    PlatformKafkaOutboundAutoConfiguration.class,
                    PlatformOutboundPropagationAutoConfiguration.class)
            )
            .withBean(TracingProperties.class, TracingProperties::new)
            .withBean(TraceOperations.class, PlatformKafkaOutboundAutoConfigurationTest::noopTraceOperations)
            .withBean(RequestIdentityBoundarySupport.class,
                    () -> new RequestIdentityBoundarySupport(NoOpTracingRuntime.noop())
            )
            .withPropertyValues("platform.tracing.kafka.propagate-platform-headers=true");

    private static TraceOperations noopTraceOperations() {
        return NoopTraceOperations.forDisabledRuntime(NoOpTracingRuntime.noop());
    }

    @Test
    void registersProducerCustomizerAfterSharedPropagationBean() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(PlatformKafkaProducerFactoryCustomizer.class);
            assertThat(context).hasBean("platformOutboundPropagation");
        });
    }

    @Test
    void explicitlyEnabledPlatformRegistersDefaultPortAndCustomizer() {
        // when
        contextRunner.withPropertyValues("platform.tracing.enabled=true").run(context -> {
            // then
            assertThat(context).hasSingleBean(PlatformOutboundPropagation.class);
            assertThat(context).hasSingleBean(PlatformKafkaProducerFactoryCustomizer.class);
        });
    }

    @Test
    void explicitlyEnabledPlatformRegistersCustomizerWithUserPort() {
        // given
        PlatformOutboundPropagation user = (decision, control) -> OutboundPropagationHeaders.EMPTY;

        // when
        contextRunner.withPropertyValues("platform.tracing.enabled=true")
                .withBean(PlatformOutboundPropagation.class, () -> user)
                .run(context -> {
                    // then
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(PlatformOutboundPropagation.class);
                    assertThat(context.getBean(PlatformOutboundPropagation.class)).isSameAs(user);
                    assertThat(context).hasSingleBean(PlatformKafkaProducerFactoryCustomizer.class);
                });
    }

    @Test
    void deleted_kafka_mode_is_ignored_when_propagate_enabled() {
        contextRunner
                .withPropertyValues("platform.tracing.kafka.mode=disabled")
                .run(context -> assertThat(context)
                        .hasSingleBean(PlatformKafkaProducerFactoryCustomizer.class));
    }

    @Test
    void tracingDisabledOmitsProducerCustomizerWithPlatformPropagation() {
        // when
        contextRunner
                .withPropertyValues("platform.tracing.enabled=false")
                .run(context -> {
                    // then
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PlatformOutboundPropagation.class);
                    assertThat(context).doesNotHaveBean(PlatformKafkaProducerFactoryCustomizer.class);
                });
    }

    @Test
    void userPropagationDoesNotRegisterCustomizerWhenTracingDisabled() {
        // given
        PlatformOutboundPropagation user = (decision, control) -> OutboundPropagationHeaders.EMPTY;

        // when
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        PlatformKafkaOutboundAutoConfiguration.class,
                        TracingControlChannelAutoConfiguration.class,
                        TracingRuntimeAutoConfiguration.class,
                        RequestContextSupplierAutoConfiguration.class,
                        PlatformOutboundPropagationAutoConfiguration.class))
                .withPropertyValues(
                        "platform.tracing.enabled=false",
                        "platform.tracing.control.channel=NONE",
                        "platform.tracing.kafka.propagate-platform-headers=true")
                .withBean(PlatformOutboundPropagation.class, () -> user)
                .run(context -> {
                    // then
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(RequestIdentityBoundarySupport.class);
                    assertThat(context).hasSingleBean(PlatformOutboundPropagation.class);
                    assertThat(context.getBean(PlatformOutboundPropagation.class)).isSameAs(user);
                    assertThat(context).doesNotHaveBean(PlatformKafkaProducerFactoryCustomizer.class);
                });
    }

    @Test
    void propagate_false_does_not_register_customizer() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        PlatformKafkaOutboundAutoConfiguration.class,
                        PlatformOutboundPropagationAutoConfiguration.class))
                .withBean(TracingProperties.class, TracingProperties::new)
                .withBean(TraceOperations.class, PlatformKafkaOutboundAutoConfigurationTest::noopTraceOperations)
                .withBean(RequestIdentityBoundarySupport.class,
                        () -> new RequestIdentityBoundarySupport(NoOpTracingRuntime.noop()))
                .run(context -> assertThat(context)
                        .doesNotHaveBean(PlatformKafkaProducerFactoryCustomizer.class));
    }
}
