package space.br1440.platform.tracing.otel.javaagent.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsWriter;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateAttempt;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateStatus;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class PlatformLogControlTest {

    @Test
    void bind_projects_accepted_diagnostics_level() throws Exception {
        // given
        PlatformLogControl control = new PlatformLogControl();
        TracingSettingsSource source = TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random());

        // when
        try (AutoCloseable ignored = control.bindAcceptedPolicy(source)) {
            // then
            assertThat(control.getLevel()).isEqualTo(AgentLogLevel.INFO);
        }
    }

    @Test
    void closeRestoresDefaultDiagnosticsLevelAndAllowsRebind() throws Exception {
        // given
        PlatformLogControl control = new PlatformLogControl();
        TracingSettingsSource source = TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random());
        TracingSettings current = source.current().policy();
        TracingSettings debug = TracingSettings.of(
                current.samplingEnabled(),
                current.samplingRatio(),
                current.samplingRouteRatios(),
                current.validationLevel(),
                current.exporterEnabled(),
                current.traceControlHeadersEnabled(),
                AgentLogLevel.DEBUG
        );
        assertThat(source.updater().apply(
                new SettingsUpdateAttempt(UUID.randomUUID().toString(), SettingsWriter.CONFIG_SERVER),
                source.current().base(),
                debug
        ).outcome()).isEqualTo(SettingsUpdateStatus.APPLIED);

        // when
        try (AutoCloseable ignored = control.bindAcceptedPolicy(source)) {
            assertThat(control.getLevel()).isEqualTo(AgentLogLevel.DEBUG);
        }

        // then
        assertThat(control.getLevel()).isEqualTo(TracingSettingsSource.defaultPolicy().diagnosticsLogLevel());
        try (AutoCloseable ignored = control.bindAcceptedPolicy(source)) {
            assertThat(control.getLevel()).isEqualTo(AgentLogLevel.DEBUG);
        }
    }
}
