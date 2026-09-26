package space.br1440.platform.tracing.otel.javaagent.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AgentInstanceIdTest {

    @Test
    void randomProducesCanonicalLowercaseVersionFourUuid() {
        // given

        // when
        AgentInstanceId agentInstanceId = AgentInstanceId.random();

        // then
        assertThat(agentInstanceId.asUuid().version()).isEqualTo(4);
        assertThat(agentInstanceId.toString())
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    }

    @Test
    void fromAndParseAcceptNonNilVersionOneUuid() {
        // given
        UUID value = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");

        // when
        AgentInstanceId agentInstanceId = AgentInstanceId.from(value);
        AgentInstanceId parsed = AgentInstanceId.parse(agentInstanceId.toString());

        // then
        assertThat(agentInstanceId.asUuid()).isEqualTo(value);
        assertThat(parsed).isEqualTo(agentInstanceId);
    }

    @Test
    void unboundSentinelIsStable() {
        // given
        AgentInstanceId first = AgentInstanceId.unbound();

        // when
        AgentInstanceId second = AgentInstanceId.unbound();

        // then
        assertThat(first).isSameAs(second);
        assertThat(first.isUnbound()).isTrue();
        assertThat(first.toString()).isEqualTo("00000000-0000-0000-0000-000000000000");
    }

    @Test
    void fromRejectsNilUuid() {
        // given
        UUID value = new UUID(0L, 0L);

        // when
        Throwable failure = catchThrowable(() -> AgentInstanceId.from(value));

        // then
        assertThat(failure)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("AgentInstanceId must not be a nil UUID");
    }

    @Test
    void parseRejectsReservedUnboundUuid() {
        // given
        String value = AgentInstanceId.unbound().toString();

        // when
        Throwable failure = catchThrowable(() -> AgentInstanceId.parse(value));

        // then
        assertThat(failure)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("AgentInstanceId must not be a nil UUID");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "2545D2BE-9095-4C83-9B37-C7737976A9ED",
            "1-1-1-1-1",
            "not-a-uuid",
            " 2545d2be-9095-4c83-9b37-c7737976a9ed",
            "2545d2be-9095-4c83-9b37-c7737976a9ed "
    })
    void parseRejectsNonCanonicalValue(String value) {
        // given

        // when
        Throwable failure = catchThrowable(() -> AgentInstanceId.parse(value));

        // then
        assertThat(failure)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("AgentInstanceId must be a canonical");
    }
}
