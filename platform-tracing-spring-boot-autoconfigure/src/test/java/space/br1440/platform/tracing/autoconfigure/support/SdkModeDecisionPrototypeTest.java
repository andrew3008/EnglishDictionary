package space.br1440.platform.tracing.autoconfigure.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;

/**
 * Регрессия утверждённой в Spike A mismatch-семантики production resolver.
 */
class SdkModeDecisionPrototypeTest {

    @Test
    void autoUsesAgentOnlyWhenMarkerIsPresent() {
        assertThat(resolve(SdkMode.AUTO, inputs(true, false, false))).isEqualTo(SdkMode.AGENT);
        assertThat(resolve(SdkMode.AUTO, inputs(false, true, false))).isEqualTo(SdkMode.EXTERNAL);
        assertThat(resolve(SdkMode.AUTO, inputs(false, false, true))).isEqualTo(SdkMode.EXTERNAL);
        assertThat(resolve(SdkMode.AUTO, inputs(false, false, false))).isEqualTo(SdkMode.STARTER);
    }

    @Test
    void agentAndUserBeanFailFast() {
        assertFailure(
                SdkMode.AUTO,
                inputs(true, false, true),
                "OpenTelemetry bean and active Java Agent detected simultaneously; "
                        + "remove the bean or disable the Agent");
    }

    @Test
    void explicitAgentRequiresMarker() {
        assertFailure(
                SdkMode.AGENT,
                inputs(false, false, false),
                "platform.tracing.sdk.mode=AGENT requires an active OpenTelemetry Java Agent marker");
        assertThat(resolve(SdkMode.AGENT, inputs(true, true, false))).isEqualTo(SdkMode.AGENT);
    }

    @Test
    void explicitStarterRejectsAgentAndExternalRuntime() {
        assertFailure(
                SdkMode.STARTER,
                inputs(true, false, false),
                "platform.tracing.sdk.mode=STARTER conflicts with an active OpenTelemetry Java Agent; "
                        + "use AUTO or AGENT");
        assertFailure(
                SdkMode.STARTER,
                inputs(false, true, false),
                "platform.tracing.sdk.mode=STARTER conflicts with an external OpenTelemetry runtime; "
                        + "use AUTO or EXTERNAL");
        assertThat(resolve(SdkMode.STARTER, inputs(false, false, false))).isEqualTo(SdkMode.STARTER);
    }

    @Test
    void explicitExternalRequiresRuntimeAndRejectsAgent() {
        assertFailure(
                SdkMode.EXTERNAL,
                inputs(true, false, false),
                "platform.tracing.sdk.mode=EXTERNAL conflicts with an active OpenTelemetry Java Agent; "
                        + "use AUTO or AGENT");
        assertFailure(
                SdkMode.EXTERNAL,
                inputs(false, false, false),
                "platform.tracing.sdk.mode=EXTERNAL requires a functional external OpenTelemetry runtime");
        assertThat(resolve(SdkMode.EXTERNAL, inputs(false, true, false))).isEqualTo(SdkMode.EXTERNAL);
        assertThat(resolve(SdkMode.EXTERNAL, inputs(false, false, true))).isEqualTo(SdkMode.EXTERNAL);
    }

    @Test
    void disabledFacadeIsAllowedWithAndWithoutAgent() {
        assertThat(resolve(SdkMode.DISABLED, inputs(false, false, false))).isEqualTo(SdkMode.DISABLED);
        assertThat(resolve(SdkMode.DISABLED, inputs(true, true, false))).isEqualTo(SdkMode.DISABLED);
    }

    private static SdkMode resolve(SdkMode configured, SdkModeResolver.Inputs inputs) {
        return SdkModeResolver.resolve(configured, inputs);
    }

    private static SdkModeResolver.Inputs inputs(boolean agent, boolean global, boolean userBean) {
        return new SdkModeResolver.Inputs(agent, global, userBean);
    }

    private static void assertFailure(
            SdkMode configured,
            SdkModeResolver.Inputs inputs,
            String message) {
        assertThatIllegalStateException()
                .isThrownBy(() -> resolve(configured, inputs))
                .withMessage(message);
    }
}
