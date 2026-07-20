package space.br1440.platform.tracing.autoconfigure.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
    @DisplayName("AUTO + функциональный GlobalOpenTelemetry без marker → EXTERNAL")
    void resolver_external_when_global_set_without_agent_marker() {
        assertThat(SdkModeResolver.resolve(SdkMode.AUTO, inputs(false, true, false)))
                .isEqualTo(SdkMode.EXTERNAL);
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
    @DisplayName("agent и пользовательский bean завершают startup диагностируемой ошибкой")
    void resolver_rejects_agent_and_user_bean() {
        assertThatIllegalStateException()
                .isThrownBy(() -> SdkModeResolver.resolve(SdkMode.AUTO, inputs(true, false, true)))
                .withMessageContaining("OpenTelemetry bean and active Java Agent");
    }

    @Test
    @DisplayName("DISABLED разрешён с Agent, но не скрывает второй application SDK")
    void disabled_mode_does_not_hide_dual_runtime() {
        assertThat(SdkModeResolver.resolve(SdkMode.DISABLED, inputs(true, true, false)))
                .isEqualTo(SdkMode.DISABLED);
        assertThatIllegalStateException()
                .isThrownBy(() -> SdkModeResolver.resolve(
                        SdkMode.DISABLED, inputs(true, true, true)))
                .withMessageContaining("OpenTelemetry bean and active Java Agent");
    }

    @Test
    @DisplayName("несовместимый explicit STARTER завершается ошибкой")
    void explicit_starter_rejects_agent() {
        assertThatIllegalStateException()
                .isThrownBy(() -> SdkModeResolver.resolve(SdkMode.STARTER, inputs(true, false, false)))
                .withMessageContaining("STARTER conflicts with an active OpenTelemetry Java Agent");
    }
}
