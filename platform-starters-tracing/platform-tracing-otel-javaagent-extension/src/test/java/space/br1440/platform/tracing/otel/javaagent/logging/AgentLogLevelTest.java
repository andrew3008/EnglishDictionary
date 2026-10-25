package space.br1440.platform.tracing.otel.javaagent.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;

class AgentLogLevelTest {

    private static final AgentLogLevel[] EVENT_LEVELS = {
            AgentLogLevel.ERROR,
            AgentLogLevel.WARN,
            AgentLogLevel.INFO,
            AgentLogLevel.DEBUG,
            AgentLogLevel.TRACE
    };

    @Test
    void permits_mapping_is_exhaustive() {
        // given
        // when
        // then
        for (AgentLogLevel configuredLevel : AgentLogLevel.values()) {
            for (AgentLogLevel eventLevel : EVENT_LEVELS) {
                boolean expected = switch (configuredLevel) {
                    case OFF -> false;
                    case ERROR -> eventLevel == AgentLogLevel.ERROR;
                    case WARN -> eventLevel == AgentLogLevel.ERROR || eventLevel == AgentLogLevel.WARN;
                    case INFO -> eventLevel != AgentLogLevel.DEBUG && eventLevel != AgentLogLevel.TRACE;
                    case DEBUG -> eventLevel != AgentLogLevel.TRACE;
                    case TRACE -> true;
                };

                assertThat(configuredLevel.permits(eventLevel))
                        .as(configuredLevel + " permits " + eventLevel)
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    void off_is_configuration_only() {
        // given
        // when
        // then
        assertThatThrownBy(AgentLogLevel.OFF::requireEventLevel)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("OFF is not a valid event level");

        assertThat(AgentLogLevel.TRACE.permits(AgentLogLevel.OFF)).isFalse();
    }
}
