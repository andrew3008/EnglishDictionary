package space.br1440.platform.tracing.autoconfigure.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-3 (Фаза 15): резолв {@link SdkMode} по признакам среды и явному значению оператора.
 */
@DisplayName("SdkModeResolver")
class SdkModeResolverTest {

    private static SdkModeResolver.Inputs inputs(boolean agent, boolean global, boolean userBean) {
        return new SdkModeResolver.Inputs(agent, global, userBean);
    }

    @Test
    @DisplayName("AUTO + agent присутствует → AGENT")
    void resolver_agent_when_agent_present() {
        assertThat(SdkModeResolver.resolve(SdkMode.AUTO, inputs(true, false, false)))
                .isEqualTo(SdkMode.AGENT);
    }

    @Test
    @DisplayName("AUTO + функциональный GlobalOpenTelemetry → AGENT")
    void resolver_agent_when_global_set() {
        assertThat(SdkModeResolver.resolve(SdkMode.AUTO, inputs(false, true, false)))
                .isEqualTo(SdkMode.AGENT);
    }

    @Test
    @DisplayName("AUTO + пользовательский OpenTelemetry bean (без агента) → EXTERNAL")
    void resolver_external_when_user_bean() {
        assertThat(SdkModeResolver.resolve(SdkMode.AUTO, inputs(false, false, true)))
                .isEqualTo(SdkMode.EXTERNAL);
    }

    @Test
    @DisplayName("AUTO + ничего не обнаружено → STARTER (consume-mode без создания SDK)")
    void resolver_starter_when_none() {
        assertThat(SdkModeResolver.resolve(SdkMode.AUTO, inputs(false, false, false)))
                .isEqualTo(SdkMode.STARTER);
    }

    @Test
    @DisplayName("agent имеет приоритет над пользовательским bean при AUTO")
    void resolver_agent_precedence_over_bean() {
        assertThat(SdkModeResolver.resolve(SdkMode.AUTO, inputs(true, false, true)))
                .isEqualTo(SdkMode.AGENT);
    }

    @Test
    @DisplayName("явный режим оператора уважается без авто-детекта")
    void explicit_mode_wins() {
        assertThat(SdkModeResolver.resolve(SdkMode.DISABLED, inputs(true, true, true)))
                .isEqualTo(SdkMode.DISABLED);
        assertThat(SdkModeResolver.resolve(SdkMode.STARTER, inputs(true, false, false)))
                .isEqualTo(SdkMode.STARTER);
    }
}
