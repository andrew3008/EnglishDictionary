package space.br1440.platform.tracing.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.CorrelationScope;
import space.br1440.platform.tracing.api.span.builder.ActiveTraceContextView;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.autoconfigure.metrics.MeteredTracingRuntime;
import space.br1440.platform.tracing.autoconfigure.support.ControlledAgentTestRuntime;
import space.br1440.platform.tracing.otel.facade.DefaultTraceOperations;
import space.br1440.platform.tracing.otel.facade.NoopTraceOperations;
import space.br1440.platform.tracing.otel.runtime.otel.OtelTracingRuntime;
import space.br1440.platform.tracing.otel.runtime.NoOpTracingRuntime;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;
import space.br1440.platform.tracing.otel.runtime.state.TracingMode;
import space.br1440.platform.tracing.otel.runtime.state.TracingState;
import space.br1440.platform.tracing.otel.runtime.NoOpSpanHandle;
import space.br1440.platform.tracing.otel.semconv.policy.AttributePolicy;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class BeanTopologyTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TracingCoreAutoConfiguration.class,
                    TracingMetricsAutoConfiguration.class));

    private final ApplicationContextRunner controlledAgentRunner = contextRunner
            .withInitializer(ControlledAgentTestRuntime::initialize);

    @Test
    void exactlyOneTraceOperationsAndTracingRuntime() {
        controlledAgentRunner
                .run(context -> {
                    assertThat(context.getBeansOfType(TraceOperations.class)).hasSize(1);
                    assertThat(context.getBeansOfType(TracingRuntime.class)).hasSize(1);
                    assertThat(context.getBean(TraceOperations.class))
                            .isInstanceOf(DefaultTraceOperations.class);
                    assertThat(context.getBean(TracingRuntime.class))
                            .isInstanceOf(OtelTracingRuntime.class);
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
                    assertThat(context.getBean(TracingRuntime.class))
                            .isInstanceOf(MeteredTracingRuntime.class);
                    assertThat(((MeteredTracingRuntime) context.getBean(TracingRuntime.class)).delegate())
                            .isInstanceOf(OtelTracingRuntime.class);
                });
    }

    @Test
    void disabledSdkMode_exposesNonEnabledTracingState() {
        contextRunner
                .withPropertyValues(
                        "platform.tracing.enabled=false",
                        "platform.tracing.sdk.mode=DISABLED")
                .run(context -> {
                    TracingRuntime impl = context.getBean(TracingRuntime.class);
                    assertThat(impl.state().mode()).isEqualTo(TracingMode.DISABLED_BY_CONFIGURATION);
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
        contextRunner
                .withUserConfiguration(CustomPrimaryTracingRuntimeConfig.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessage(
                                    "Application-owned TracingRuntime bean is not supported "
                                            + "with Controlled Agent ownership: "
                                            + "customTracingRuntime");
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


        private static final TracingState STATE = new TracingState() {
            @Override
            public TracingMode mode() {
                return TracingMode.TEST;
            }

            @Override
            public Optional<String> reason() {
                return Optional.of("test-primary");
            }

            @Override
            public Map<String, String> details() {
                return Map.of();
            }
        };

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
        public CorrelationScope openCorrelationScope(String correlationId) {
            return identityDelegate.openCorrelationScope(correlationId);
        }

        @Override
        public CorrelationScope openRequestIdentityScope(String requestId) {
            return identityDelegate.openRequestIdentityScope(requestId);
        }

        @Override
        public String requireCanonicalCorrelationId(String correlationId) {
            return identityDelegate.requireCanonicalCorrelationId(correlationId);
        }

        @Override
        public Optional<String> currentRequestId() {
            return identityDelegate.currentRequestId();
        }

        @Override
        public Optional<String> currentCorrelationId() {
            return identityDelegate.currentCorrelationId();
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
        public AttributePolicy attributePolicy() {
            return new AttributePolicy();
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
