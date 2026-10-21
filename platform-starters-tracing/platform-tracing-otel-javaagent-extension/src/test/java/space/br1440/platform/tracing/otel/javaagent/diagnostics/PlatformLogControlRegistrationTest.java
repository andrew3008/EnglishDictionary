package space.br1440.platform.tracing.otel.javaagent.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import java.util.Map;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;

class PlatformLogControlRegistrationTest {

    @Test
    void bindCloseRestoresPreviousAndStaleCloseDoesNotClearNewerBinding() throws Exception {
        // given
        PlatformLogControl control = new PlatformLogControl();
        TracingSettingsSource policyA = TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random());
        TracingSettingsSource policyB = TracingSettingsSourceTestFixtures.boundSampling(AgentInstanceId.random(), true, Map.of(), 0.5d);

        // when
        AutoCloseable token1 = control.bindAcceptedPolicy(policyA);
        token1.close();

        AutoCloseable token2 = control.bindAcceptedPolicy(policyB);
        token1.close();

        // then
        assertThat(control.getLevel()).isNotNull();

        token2.close();
        token2.close();
    }

    @Test
    void rebindWhileActiveFails() throws Exception {
        // given
        PlatformLogControl control = new PlatformLogControl();
        AutoCloseable token1 = control.bindAcceptedPolicy(TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()));

        // when/then
        assertThatThrownBy(() -> {
            try (AutoCloseable ignored = control.bindAcceptedPolicy(
                    TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()))) {
                fail("registration opened");
            }
        })
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already has an active");
        token1.close();
    }
}
