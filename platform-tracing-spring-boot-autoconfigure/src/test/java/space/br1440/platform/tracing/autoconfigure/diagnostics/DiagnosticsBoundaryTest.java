package space.br1440.platform.tracing.autoconfigure.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import space.br1440.platform.tracing.autoconfigure.TracingActuatorAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.TracingCoreAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.actuator.TracingActuatorEndpoint;
import space.br1440.platform.tracing.autoconfigure.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.manual.ActiveTraceContextView;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.core.runtime.TracingRuntime;
import space.br1440.platform.tracing.core.runtime.state.TracingMode;
import space.br1440.platform.tracing.core.runtime.state.TracingState;
import space.br1440.platform.tracing.core.runtime.NoOpSpanHandle;
import space.br1440.platform.tracing.core.semconv.policy.AttributePolicy;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice 7 hard gate: Actuator exposes stable diagnostics DTO, not internal state types.
 */
class DiagnosticsBoundaryTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> APPROVED_MODES = Set.of(
            "ENABLED",
            "DISABLED_BY_CONFIGURATION",
            "UNAVAILABLE",
            "NOOP",
            "UNKNOWN");

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TracingCoreAutoConfiguration.class));

    @Test
    void actuatorEndpoint_exposesStablespanFactorySection() {
        contextRunner
                .withPropertyValues("platform.tracing.sdk.mode=DISABLED")
                .run(context -> {
                    TracingActuatorEndpoint endpoint = new TracingActuatorEndpoint(
                            context.getBean(TraceOperations.class),
                            context.getBean(TracingProperties.class),
                            context.getBean(PlatformTracingJmxClient.class),
                            context.getBean(SpanFactoryDiagnostics.class));
                    Map<String, Object> payload = endpoint.tracing();

                    assertThat(payload).containsKey("spanFactory");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> spanFactory = (Map<String, Object>) payload.get("spanFactory");

                    assertThat(spanFactory.keySet()).containsExactlyInAnyOrder("mode", "reason", "details");
                    assertThat(spanFactory.get("mode")).isEqualTo("DISABLED_BY_CONFIGURATION");
                    assertThat(spanFactory.get("reason")).isInstanceOf(String.class);
                    assertThat(spanFactory.get("details")).isInstanceOf(Map.class);

                    String json;
                    try {
                        json = OBJECT_MAPPER.writeValueAsString(spanFactory);
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                    assertThat(json).doesNotContain("TracingState");
                    assertThat(json).doesNotContain("TracingMode");
                    assertThat(json.toLowerCase()).doesNotContain("opentelemetry");
                    assertThat(APPROVED_MODES).contains((String) spanFactory.get("mode"));
                });
    }

    @Test
    void SpanFactoryDiagnostics_mapsInternalTestModeToUnknown() {
        TracingRuntime testPrimary = new TracingRuntime() {
            private final TracingState state = new TracingState() {
                @Override
                public TracingMode mode() {
                    return TracingMode.TEST;
                }

                @Override
                public Optional<String> reason() {
                    return Optional.of("custom-primary");
                }

                @Override
                public Map<String, String> details() {
                    return Map.of("marker", "true");
                }
            };

            @Override
            public SpanHandle startSpan(SpanSpec spec) {
                return NoOpSpanHandle.INSTANCE;
            }

            @Override
            public ActiveTraceContextView currentTraceContext() {
                return new ActiveTraceContextView() {
                    @Override
                    public Optional<String> traceId() {
                        return Optional.empty();
                    }

                    @Override
                    public Optional<String> spanId() {
                        return Optional.empty();
                    }

                    @Override
                    public Optional<String> correlationId() {
                        return Optional.empty();
                    }
                };
            }

            @Override
            public void recordException(SpanHandle span, Throwable throwable) {
            }

            @Override
            public TracingState state() {
                return state;
            }

            @Override
            public AttributePolicy attributePolicy() {
                return new AttributePolicy();
            }
        };

        SpanFactoryDiagnostics diagnostics = new SpanFactoryDiagnostics(testPrimary);
        TracingDiagnosticsView view = diagnostics.view();

        assertThat(view.mode()).isEqualTo("UNKNOWN");
        assertThat(view.reason()).isEqualTo("custom-primary");
        assertThat(view.details()).containsEntry("marker", "true");
        assertThat(diagnostics.toActuatorMap().get("mode")).isEqualTo("UNKNOWN");
    }

    @Test
    void unavailableState_exposesReasonAndApprovedMode() {
        contextRunner.run(context -> {
            SpanFactoryDiagnostics diagnostics = context.getBean(SpanFactoryDiagnostics.class);
            TracingDiagnosticsView view = diagnostics.view();

            assertThat(view.mode()).isIn("UNAVAILABLE", "NOOP");
            assertThat(APPROVED_MODES).contains(view.mode());
        });
    }
}
