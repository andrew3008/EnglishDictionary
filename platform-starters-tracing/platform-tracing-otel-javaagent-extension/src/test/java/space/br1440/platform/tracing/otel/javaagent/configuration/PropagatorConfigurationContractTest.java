package space.br1440.platform.tracing.otel.javaagent.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigurationException;
import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;

class PropagatorConfigurationContractTest {

    private static final Set<String> KNOWN_AGENT_PROPAGATORS =
            Set.of("b3", "b3multi", "jaeger", "ottrace", "xray", "xray-lambda");
    private static final List<String> REJECTED_AGENT_PROPAGATORS =
            List.of("jaeger", "ottrace", "xray", "xray-lambda");

    @ParameterizedTest
    @MethodSource("rejectedAgentPropagators")
    void rejects_agent_propagators_outside_the_supported_set(String propagator) {
        // given
        var config = DefaultConfigProperties.createFromMap(Map.of(OtelSdkPropertyNames.PROPAGATORS, propagator));

        // when
        Throwable failure = catchThrowable(() -> PropagatorConfigurationContract.requireSupported(config));

        // then
        assertThat(failure)
                .isInstanceOf(ConfigurationPropertyException.class)
                .hasCauseInstanceOf(ConfigurationException.class);
        ConfigurationPropertyException propertyFailure = (ConfigurationPropertyException) failure;
        assertThat(propertyFailure.propertyName()).isEqualTo(OtelSdkPropertyNames.PROPAGATORS);
        assertThat(propertyFailure.failure()).hasMessageContaining("[" + propagator + "]");
    }

    @Test
    void rejected_cases_cover_all_unsupported_agent_propagators() {
        // given
        Set<String> unsupported = new TreeSet<>(KNOWN_AGENT_PROPAGATORS);

        // when
        unsupported.removeAll(PropagatorConfigurationContract.SUPPORTED_PROPAGATORS);

        // then
        assertThat(REJECTED_AGENT_PROPAGATORS).containsExactlyInAnyOrderElementsOf(unsupported);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "none", "tracecontext", "baggage", "b3", "b3multi", "platform-trace-control",
            "tracecontext,baggage,b3,platform-trace-control"
    })
    void accepts_supported_propagators(String propagators) {
        // given
        var config = DefaultConfigProperties.createFromMap(Map.of(OtelSdkPropertyNames.PROPAGATORS, propagators));

        // when
        Throwable failure = catchThrowable(() -> PropagatorConfigurationContract.requireSupported(config));

        // then
        assertThat(failure).isNull();
    }

    @Test
    void accepts_absent_propagators() {
        // given
        var config = DefaultConfigProperties.createFromMap(Map.of());

        // when
        Throwable failure = catchThrowable(() -> PropagatorConfigurationContract.requireSupported(config));

        // then
        assertThat(failure).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"JAEGER", "B3", "unknown"})
    void rejects_unknown_or_differently_cased_names(String propagator) {
        // given
        var config = DefaultConfigProperties.createFromMap(Map.of(OtelSdkPropertyNames.PROPAGATORS, propagator));

        // when
        Throwable failure = catchThrowable(() -> PropagatorConfigurationContract.requireSupported(config));

        // then
        assertThat(failure).isInstanceOf(ConfigurationPropertyException.class);
        ConfigurationPropertyException propertyFailure = (ConfigurationPropertyException) failure;
        assertThat(propertyFailure.propertyName()).isEqualTo(OtelSdkPropertyNames.PROPAGATORS);
        assertThat(propertyFailure.failure()).hasMessageContaining("[" + propagator + "]");
    }

    @Test
    void reports_unsupported_and_supported_names_in_deterministic_order() {
        // given
        var config = DefaultConfigProperties.createFromMap(Map.of(
                OtelSdkPropertyNames.PROPAGATORS, "xray,tracecontext,ottrace,jaeger,xray"
        ));

        // when
        Throwable failure = catchThrowable(() -> PropagatorConfigurationContract.requireSupported(config));

        // then
        assertThat(failure).isInstanceOf(ConfigurationPropertyException.class);
        assertThat(((ConfigurationPropertyException) failure).failure()).hasMessage(
                "otel.propagators contains unsupported propagators [jaeger, ottrace, xray]; "
                        + "these propagators carry baggage outside the platform policy; "
                        + "supported: [b3, b3multi, baggage, none, platform-trace-control, tracecontext]"
        );
    }

    private static Stream<String> rejectedAgentPropagators() {
        return REJECTED_AGENT_PROPAGATORS.stream();
    }
}
