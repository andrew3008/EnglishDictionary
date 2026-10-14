package space.br1440.platform.tracing.otel.javaagent.control.settings.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;

class PolicyLeafTest {

    @Test
    void identicalSettingsProduceNoLeaves() {
        TracingSettings settings = baseline();

        assertThat(PolicyLeaf.diff(settings, settings)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(PolicyLeaf.class)
    void singleFieldChangeProducesOnlyThatLeaf(PolicyLeaf leaf) {
        TracingSettings current = baseline();
        TracingSettings candidate = changed(leaf);

        assertThat(current).isNotEqualTo(candidate);
        assertThat(PolicyLeaf.diff(current, candidate)).containsExactly(leaf);
    }

    private static TracingSettings baseline() {
        return new Fixture().build();
    }

    private static TracingSettings changed(PolicyLeaf leaf) {
        Consumer<Fixture> change = switch (leaf) {
            case SAMPLING_ENABLED -> fixture -> fixture.samplingEnabled = false;
            case SAMPLING_RATIO -> fixture -> fixture.samplingRatio = 1.0d;
            case SAMPLING_ROUTE_RATIOS -> fixture -> fixture.samplingRouteRatios = Map.of("/api", 0.5d);
            case VALIDATION_LEVEL -> fixture -> fixture.validationLevel = SpanAttributeValidationLevel.ERROR;
            case EXPORTER_ENABLED -> fixture -> fixture.exporterEnabled = false;
            case TRACE_CONTROL_HEADERS_ENABLED -> fixture -> fixture.traceControlHeadersEnabled = false;
            case DIAGNOSTICS_LOG_LEVEL -> fixture -> fixture.diagnosticsLogLevel = AgentLogLevel.WARN;
        };

        Fixture fixture = new Fixture();
        change.accept(fixture);
        return fixture.build();
    }

    private static final class Fixture {

        private boolean samplingEnabled = true;
        private double samplingRatio = 0.5d;
        private Map<String, Double> samplingRouteRatios = Map.of();
        private SpanAttributeValidationLevel validationLevel = SpanAttributeValidationLevel.WARNING;
        private boolean exporterEnabled = true;
        private boolean traceControlHeadersEnabled = true;
        private AgentLogLevel diagnosticsLogLevel = AgentLogLevel.INFO;

        private TracingSettings build() {
            return TracingSettings.of(
                    samplingEnabled,
                    samplingRatio,
                    samplingRouteRatios,
                    validationLevel,
                    exporterEnabled,
                    traceControlHeadersEnabled,
                    diagnosticsLogLevel
            );
        }
    }
}
