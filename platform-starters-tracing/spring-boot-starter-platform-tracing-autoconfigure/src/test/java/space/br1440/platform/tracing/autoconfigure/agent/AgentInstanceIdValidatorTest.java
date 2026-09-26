package space.br1440.platform.tracing.autoconfigure.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

final class AgentInstanceIdValidatorTest {

    private static final String CANONICAL_ID = "5a3f3fe4-e8ea-4dc4-b040-5d355e906f3a";

    @Test
    void canonicalLowercaseUuidIsAccepted() {
        // given
        String value = CANONICAL_ID;

        // when
        String validated = AgentInstanceIdValidator.requireCanonical(value, "agentInstanceId");

        // then
        assertThat(validated).isSameAs(value);
    }

    @Test
    void uppercaseUuidIsRejected() {
        // given
        String value = CANONICAL_ID.toUpperCase();

        // when
        // then
        assertThatThrownBy(() -> AgentInstanceIdValidator.requireCanonical(value, "agentInstanceId"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("agentInstanceId must be a canonical lowercase UUID");
    }

    @Test
    void nonCanonicalUuidTextIsRejected() {
        // given
        String value = "5a3f3fe4-e8ea-4dc4-b040-5d355e906f3";

        // when
        // then
        assertThatThrownBy(() -> AgentInstanceIdValidator.requireCanonical(value, "agentInstanceId"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("agentInstanceId must be a canonical lowercase UUID");
    }

    @Test
    void nullUuidIsRejected() {
        // given
        String value = null;

        // when
        // then
        //noinspection ConstantValue,ConstantConditions
        assertThatThrownBy(() -> AgentInstanceIdValidator.requireCanonical(value, "agentInstanceId"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("agentInstanceId must not be null");
    }

    @Test
    void nilUuidIsRejected() {
        // given
        String value = "00000000-0000-0000-0000-000000000000";

        // when
        // then
        assertThatThrownBy(() -> AgentInstanceIdValidator.requireCanonical(value, "agentInstanceId"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("agentInstanceId must not be a nil UUID");
    }
}
