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
import space.br1440.platform.tracing.api.manual.TraceContextView;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.autoconfigure.metrics.MeteredTracingImplementation;
import space.br1440.platform.tracing.core.DefaultPlatformTracing;
import space.br1440.platform.tracing.core.NoOpPlatformTracing;
import space.br1440.platform.tracing.core.impl.DefaultTracingImplementation;
import space.br1440.platform.tracing.core.impl.NoOpTracingImplementation;
import space.br1440.platform.tracing.core.impl.TracingImplementation;
import space.br1440.platform.tracing.core.impl.TracingMode;
import space.br1440.platform.tracing.core.impl.TracingState;
import space.br1440.platform.tracing.core.manual.NoOpSpanHandle;

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
    void exactlyOnePlatformTracingAndTracingImplementation() {
        contextRunner
                .withUserConfiguration(OpenTelemetryConfiguration.class)
                .run(context -> {
                    assertThat(context.getBeansOfType(PlatformTracing.class)).hasSize(1);
                    assertThat(context.getBeansOfType(TracingImplementation.class)).hasSize(1);
                    assertThat(context.getBean(PlatformTracing.class))
                            .isInstanceOf(DefaultPlatformTracing.class);
                    assertThat(context.getBean(TracingImplementation.class))
                            .isInstanceOf(DefaultTracingImplementation.class);
                });
    }

    @Test
    void facadeBackedBySingleImplementationChain() {
        contextRunner
                .withUserConfiguration(OpenTelemetryConfiguration.class)
                .run(context -> {
                    DefaultPlatformTracing facade = context.getBean(DefaultPlatformTracing.class);
                    TracingImplementation impl = context.getBean(TracingImplementation.class);
                    assertThat(facade.tracingImplementation()).isSameAs(impl);
                });
    }

    @Test
    void withMicrometer_wrapsTracingImplementationWithoutPublicFacadeDecorator() {
        contextRunner
                .withUserConfiguration(OpenTelemetryConfiguration.class, MeterRegistryConfiguration.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean("meteredPlatformTracing");
                    assertThat(context.getBean(PlatformTracing.class)).isInstanceOf(DefaultPlatformTracing.class);
                    assertThat(context.getBean(TracingImplementation.class))
                            .isInstanceOf(MeteredTracingImplementation.class);
                    assertThat(((MeteredTracingImplementation) context.getBean(TracingImplementation.class)).delegate())
                            .isInstanceOf(DefaultTracingImplementation.class);
                });
    }

    @Test
    void disabledSdkMode_exposesNonEnabledTracingState() {
        contextRunner
                .withPropertyValues("platform.tracing.sdk.mode=DISABLED")
                .run(context -> {
                    TracingImplementation impl = context.getBean(TracingImplementation.class);
                    assertThat(impl.state().mode()).isEqualTo(TracingMode.DISABLED_BY_CONFIGURATION);
                    assertThat(context.getBean(PlatformTracing.class)).isInstanceOf(NoOpPlatformTracing.class);
                });
    }

    @Test
    void unavailableOpenTelemetry_exposesUnavailableState() {
        contextRunner.run(context -> {
            TracingImplementation impl = context.getBean(TracingImplementation.class);
            assertThat(impl.state().mode()).isIn(TracingMode.UNAVAILABLE, TracingMode.NOOP);
            assertThat(context.getBean(PlatformTracing.class)).isInstanceOf(NoOpPlatformTracing.class);
        });
    }

    @Test
    void userPrimaryTracingImplementation_replacesDefaultWithoutHiddenBypass() {
        contextRunner
                .withUserConfiguration(OpenTelemetryConfiguration.class, CustomPrimaryTracingImplementationConfig.class)
                .run(context -> {
                    assertThat(context.getBeansOfType(TracingImplementation.class)).hasSize(1);
                    assertThat(context.getBean(TracingImplementation.class))
                            .isInstanceOf(MarkerTracingImplementation.class);
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

    static final class MarkerTracingImplementation implements TracingImplementation {

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
        public TraceContextView currentTraceContext() {
            return NoOpTracingImplementation.noop().currentTraceContext();
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
    }

    @Configuration
    static class CustomPrimaryTracingImplementationConfig {
        @Bean
        @Primary
        TracingImplementation customTracingImplementation() {
            return new MarkerTracingImplementation();
        }
    }
}
