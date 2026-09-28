package space.br1440.platform.tracing.otel.javaagent.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;

class PlatformLogControlTest {

    @Test
    void bind_projects_accepted_diagnostics_level() throws Exception {
        // given
        PlatformLogControl control = new PlatformLogControl();
        TracingSettingsSource source = TracingSettingsSource.isolatedDefaults();

        // when
        try (AutoCloseable ignored = control.bindAcceptedPolicy(source)) {
            // then
            assertThat(control.getLevel()).isEqualTo(AgentLogLevel.INFO);
        }
    }
}
