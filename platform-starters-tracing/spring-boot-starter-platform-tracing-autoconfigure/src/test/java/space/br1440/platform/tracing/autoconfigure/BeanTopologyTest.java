package space.br1440.platform.tracing.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.context.ActiveTraceContextView;
import space.br1440.platform.tracing.api.context.CorrelationScope;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.autoconfigure.control.channel.TracingControlChannelAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.metrics.MeteredTracingRuntime;
import space.br1440.platform.tracing.autoconfigure.metrics.TracingMetricsAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.support.ControlledAgentTestRuntime;
import space.br1440.platform.tracing.otel.attributes.validation.SpanAttributeValidator;
import space.br1440.platform.tracing.otel.facade.DefaultTraceOperations;
import space.br1440.platform.tracing.otel.facade.NoopTraceOperations;
import space.br1440.platform.tracing.otel.runtime.NoOpSpanHandle;
import space.br1440.platform.tracing.otel.runtime.NoOpTracingRuntime;
import space.br1440.platform.tracing.otel.runtime.TraceControlBoundaryState;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;
import space.br1440.platform.tracing.otel.runtime.otel.OtelTracingRuntime;
import space.br1440.platform.tracing.otel.runtime.state.ImmutableTracingState;
import space.br1440.platform.tracing.otel.runtime.state.TracingRuntimeMode;
import space.br1440.platform.tracing.otel.runtime.state.TracingState;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class BeanTopologyTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TracingControlChannelAutoConfiguration.class,
                    TracingRuntimeAutoConfiguration.class,
                    TracingMetricsAutoConfiguration.class)
            )
            .withPropertyValues(
                    "platform.tracing.control.channel=NONE"
            );

    private final ApplicationContextRunner controlledAgentRunner = contextRunner
            .withInitializer(ControlledAgentTestRuntime::initialize);

    @Test
    void exactlyOneTraceOperationsAndTracingRuntime() {
        controlledAgentRunner
                .run(context -> {
                    assertThat(context.getBeansOfType(TraceOperations.class)).hasSize(1);
                    assertThat(context.getBeansOfType(TracingRuntime.class)).hasSize(1);
                    assertThat(context.getBean(TraceOperations.class)).isInstanceOf(DefaultTraceOperations.class);
                    assertThat(context.getBean(TracingRuntime.class)).isInstanceOf(OtelTracingRuntime.class);
                });
    }

    @Test
    void facadeBackedBySingleImplementationChain() {
        controlledAgentRunner
                .run(context -> {
                    DefaultTraceOperations facade = context.getBean(DefaultTraceOperations.class);
                    TracingRuntime impl = context.getBean(TracingRuntime.class);
                    assertThat(facade.tracingRuntime()).isSameAs(impl);
                });
    }

    @Test
    void withMicrometer_wrapsTracingRuntimeWithoutPublicFacadeDecorator() {
        controlledAgentRunner
                .withUserConfiguration(MeterRegistryConfiguration.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean("meteredTraceOperations");
                    assertThat(context.getBean(TraceOperations.class)).isInstanceOf(DefaultTraceOperations.class);
                    assertThat(context.getBean(TracingRuntime.class)).isInstanceOf(MeteredTracingRuntime.class);
                    assertThat(((MeteredTracingRuntime) context.getBean(TracingRuntime.class)).delegate())
                            .isInstanceOf(OtelTracingRuntime.class);
                });
    }

    @Test
    void disabledTracingMode_exposesNonEnabledTracingState() {
        contextRunner
                .withPropertyValues("platform.tracing.enabled=false")
                .run(context -> {
                    TracingRuntime impl = context.getBean(TracingRuntime.class);
                    assertThat(impl.state().mode()).isEqualTo(TracingRuntimeMode.DISABLED_BY_CONFIGURATION);
                    assertThat(context.getBean(TraceOperations.class)).isInstanceOf(NoopTraceOperations.class);
                });
    }

    @Test
    void missingControlledAgent_failsStartup() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("CONTROLLED_AGENT_MISSING");
        });
    }

    @Test
    void userPrimaryTracingRuntime_isRejectedAsUnsupportedSdkOwnershipBypass() {
        String message = """
            Application-owned TracingRuntime bean is not supported \
            with Controlled Agent ownership: \
            customTracingRuntime""";

        contextRunner
                .withUserConfiguration(CustomPrimaryTracingRuntimeConfig.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessage(message);
                });
    }

    @Configuration
    static class MeterRegistryConfiguration {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    static final class MarkerTracingRuntime implements TracingRuntime {

        private final TracingRuntime identityDelegate = NoOpTracingRuntime.noop();

        private static final TracingState STATE = ImmutableTracingState.of(
                TracingRuntimeMode.NOOP, "test-primary", Map.of()
        );

        @Override
        @Nonnull
        public SpanHandle startSpan(@Nonnull SpanSpec spec) {
            return NoOpSpanHandle.INSTANCE;
        }

        @Override
        @Nonnull
        public ActiveTraceContextView currentTraceContext() {
            return identityDelegate.currentTraceContext();
        }

        @Override
        @Nonnull
        public CorrelationScope openCorrelationScope(@Nonnull String correlationId) {
            return identityDelegate.openCorrelationScope(correlationId);
        }

        @Override
        @Nonnull
        public CorrelationScope openRequestIdentityScope(@Nonnull String requestId, boolean forceTrace, boolean qaTrace) {
            return identityDelegate.openRequestIdentityScope(requestId, forceTrace, qaTrace);
        }

        @Override
        @Nonnull
        public String requireCanonicalCorrelationId(@Nonnull String correlationId) {
            return identityDelegate.requireCanonicalCorrelationId(correlationId);
        }

        @Override
        @Nonnull
        public Optional<String> currentRequestId() {
            return identityDelegate.currentRequestId();
        }

        @Override
        @Nonnull
        public Optional<String> currentCorrelationId() {
            return identityDelegate.currentCorrelationId();
        }

        @Override
        @Nonnull
        public TraceControlBoundaryState currentTraceControlBoundary() {
            return identityDelegate.currentTraceControlBoundary();
        }

        @Override
        public void recordException(@Nonnull SpanHandle span, @Nullable Throwable throwable) {
            Objects.requireNonNull(span, "span");
        }

        @Override
        @Nonnull
        public TracingState state() {
            return STATE;
        }

        @Override
        @Nonnull
        public SpanAttributeValidator spanAttributeValidator() {
            return new SpanAttributeValidator();
        }
    }

    @Configuration
    static class CustomPrimaryTracingRuntimeConfig {

        @Bean
        @Primary
        TracingRuntime customTracingRuntime() {
            return new MarkerTracingRuntime();
        }
    }
}
