package space.br1440.platform.tracing.otel.javaagent.diagnostics;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogLevel;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контракт {@link PlatformLogControl}: порог детализации читается из принятой политики.
 */
class PlatformLogControlTest {

    @Test
    void bind_projects_accepted_diagnostics_level() {
        // given
        PlatformLogControl control = new PlatformLogControl();
        TracingSettingsSource source = TracingSettingsSource.isolatedDefaults();

        // when
        control.bindAcceptedPolicy(source);

        // then
        assertThat(control.getLevel()).isEqualTo(AgentLogLevel.INFO);
    }
}
