package space.br1440.platform.tracing.otel.javaagent.control.jmx.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import space.br1440.platform.tracing.autoconfigure.agent.AgentInstanceIdValidator;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;

class AgentInstanceIdCanonicalParityTest {

    private static final String CANONICAL_ID = "5a3f3fe4-e8ea-4dc4-b040-5d355e906f3a";

    @ParameterizedTest
    @ValueSource(strings = {
            "2545D2BE-9095-4C83-9B37-C7737976A9ED",
            "1-1-1-1-1",
            "not-a-uuid",
            " 2545d2be-9095-4c83-9b37-c7737976a9ed",
            "2545d2be-9095-4c83-9b37-c7737976a9ed ",
            "5a3f3fe4-e8ea-4dc4-b040-5d355e906f3",
            "00000000-0000-0000-0000-000000000000"
    })
    void bothSidesRejectTheSameInputs(String value) {
        // when
        Throwable agentFailure = catchThrowable(() -> AgentInstanceId.parse(value));
        Throwable starterFailure = catchThrowable(() ->
                AgentInstanceIdValidator.requireCanonical(value, "agentInstanceId"));

        // then
        assertThat(agentFailure).isInstanceOf(IllegalArgumentException.class);
        assertThat(starterFailure).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bothSidesAcceptTheSameCanonicalUuid() {
        // given
        String value = CANONICAL_ID;

        // when
        String parsed = AgentInstanceId.parse(value).toString();
        String validated = AgentInstanceIdValidator.requireCanonical(value, "agentInstanceId");

        // then
        assertThat(parsed).isEqualTo(validated).isEqualTo(value);
    }
}
