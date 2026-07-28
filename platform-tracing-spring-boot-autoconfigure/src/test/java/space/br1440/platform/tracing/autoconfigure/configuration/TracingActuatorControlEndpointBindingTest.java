package space.br1440.platform.tracing.autoconfigure.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class TracingActuatorControlEndpointBindingTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void acceptsExactTypedBooleanAndRejectsStringCoercion() throws Exception {
        TracingActuatorControlEndpoint.BooleanValue request = objectMapper.readValue(
                "{\"value\":true}",
                TracingActuatorControlEndpoint.BooleanValue.class);

        assertThat(request.requiredValue()).isTrue();
        assertThatThrownBy(() -> objectMapper.readValue(
                "{\"value\":\"true\"}",
                TracingActuatorControlEndpoint.BooleanValue.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnknownAndNullFields() {
        assertThatThrownBy(() -> objectMapper.readValue(
                "{\"value\":true,\"unexpected\":1}",
                TracingActuatorControlEndpoint.BooleanValue.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> objectMapper.readValue(
                "{\"value\":null}",
                TracingActuatorControlEndpoint.BooleanValue.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void routeRatiosRequireNumericValuesAndAreDeterministic() throws Exception {
        TracingActuatorControlEndpoint.RouteRatiosValue request = objectMapper.readValue(
                "{\"value\":{\"/z\":0.2,\"/a\":0.8}}",
                TracingActuatorControlEndpoint.RouteRatiosValue.class);

        assertThat(request.requiredValue()).containsExactly(
                org.assertj.core.api.Assertions.entry("/a", 0.8d),
                org.assertj.core.api.Assertions.entry("/z", 0.2d));
        assertThatThrownBy(() -> objectMapper.readValue(
                "{\"value\":{\"/orders\":\"0.2\"}}",
                TracingActuatorControlEndpoint.RouteRatiosValue.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }
}
