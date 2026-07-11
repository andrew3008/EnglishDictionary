package space.br1440.platform.tracing.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import space.br1440.platform.tracing.api.PlatformTracing;
import space.br1440.platform.tracing.api.manual.ActiveTraceContextView;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.autoconfigure.metrics.MeteredTracingRuntime;
import space.br1440.platform.tracing.core.facade.DefaultPlatformTracing;
import space.br1440.platform.tracing.core.facade.NoOpPlatformTracing;
import space.br1440.platform.tracing.core.runtime.otel.OtelTracingRuntime;
import space.br1440.platform.tracing.core.runtime.NoOpTracingRuntime;
import space.br1440.platform.tracing.core.runtime.TracingRuntime;
import space.br1440.platform.tracing.core.runtime.state.TracingMode;
import space.br1440.platform.tracing.core.runtime.state.TracingState;
import space.br1440.platform.tracing.core.runtime.NoOpSpanHandle;
import space.br1440.platform.tracing.core.semconv.policy.AttributePolicy;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class BeanTopologyTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TracingCoreAutoConfiguration.class,
                    TracingMetricsAutoConfiguration.class));

    @Test
    void exactlyOnePlatformTracingAndTracingRuntime() {
        contextRunner
                .withUserConfiguration(OpenTelemetryConfiguration.class)
                .run(context -> {
                    assertThat(context.getBeansOfType(PlatformTracing.class)).hasSize(1);
                    assertThat(context.getBeansOfType(TracingRuntime.class)).hasSize(1);
                    assertThat(context.getBean(PlatformTracing.class))
                            .isInstanceOf(DefaultPlatformTracing.class);
                    assertThat(context.getBean(TracingRuntime.class))
                            .isInstanceOf(OtelTracingRuntime.class);
                });
    }

    @Test
    void facadeBackedBySingleImplementationChain() {
        contextRunner
                .withUserConfiguration(OpenTelemetryConfiguration.class)
                .run(context -> {
                    DefaultPlatformTracing facade = context.getBean(DefaultPlatformTracing.class);
                    TracingRuntime impl = context.getBean(TracingRuntime.class);
                    assertThat(facade.tracingRuntime()).isSameAs(impl);
                });
    }

    @Test
    void withMicrometer_wrapsTracingRuntimeWithoutPublicFacadeDecorator() {
        contextRunner
                .withUserConfiguration(OpenTelemetryConfiguration.class, MeterRegistryConfiguration.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean("meteredPlatformTracing");
                    assertThat(context.getBean(PlatformTracing.class)).isInstanceOf(DefaultPlatformTracing.class);
                    assertThat(context.getBean(TracingRuntime.class))
                            .isInstanceOf(MeteredTracingRuntime.class);
                    assertThat(((MeteredTracingRuntime) context.getBean(TracingRuntime.class)).delegate())
                            .isInstanceOf(OtelTracingRuntime.class);
                });
    }

    @Test
    void disabledSdkMode_exposesNonEnabledTracingState() {
        contextRunner
                .withPropertyValues("platform.tracing.sdk.mode=DISABLED")
                .run(context -> {
                    TracingRuntime impl = context.getBean(TracingRuntime.class);
                    assertThat(impl.state().mode()).isEqualTo(TracingMode.DISABLED_BY_CONFIGURATION);
                    assertThat(context.getBean(PlatformTracing.class)).isInstanceOf(NoOpPlatformTracing.class);
                });
    }

    @Test
    void unavailableOpenTelemetry_exposesUnavailableState() {
        contextRunner.run(context -> {
            TracingRuntime impl = context.getBean(TracingRuntime.class);
            assertThat(impl.state().mode()).isIn(TracingMode.UNAVAILABLE, TracingMode.NOOP);
            assertThat(context.getBean(PlatformTracing.class)).isInstanceOf(NoOpPlatformTracing.class);
        });
    }

    @Test
    void userPrimaryTracingRuntime_replacesDefaultWithoutHiddenBypass() {
        contextRunner
                .withUserConfiguration(OpenTelemetryConfiguration.class, CustomPrimaryTracingRuntimeConfig.class)
                .run(context -> {
                    assertThat(context.getBeansOfType(TracingRuntime.class)).hasSize(1);
                    assertThat(context.getBean(TracingRuntime.class))
                            .isInstanceOf(MarkerTracingRuntime.class);
                    assertThat(context).doesNotHaveBean("meteredPlatformTracing");
                });
    }

    @Configuration
    static class OpenTelemetryConfiguration {
        @Bean
        OpenTelemetry openTelemetry() {
            return OpenTelemetrySdk.builder().build();
        }
    }

    @Configuration
    static class MeterRegistryConfiguration {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    static final class MarkerTracingRuntime implements TracingRuntime {

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
            return NoOpTracingRuntime.noop().currentTraceContext();
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
