package space.br1440.platform.tracing.otel.javaagent.control.settings.update;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.otel.javaagent.control.AcceptedPolicyTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsStore;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsWriter;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class SecretCanaryAuditTest {

    private static final String CANARY_ROUTE = "/canary/secret/z9Qx7mK2vL4pR8nW";

    @RegisterExtension
    final AgentLogCapture capture = new AgentLogCapture();

    @Test
    void resolvedUpdateLogContainsAttemptIdAndLeafNamesWithoutPolicyValues() {
        // given
        TracingSettings initial = settings(Map.of());
        TracingSettings candidate = settings(Map.of(CANARY_ROUTE, 0.25d));
        TracingSettingsStore store = AcceptedPolicyTestFixtures.bootstrapAgentDefaultsStore(
                AcceptedPolicyTestFixtures.AGENT_INSTANCE_ID,
                initial
        );
        TracingSettingsUpdater updater = new TracingSettingsUpdater(store, level -> { });
        String attemptId = UUID.randomUUID().toString();

        // when
        SettingsUpdateResult result = updater.apply(
                new SettingsUpdateAttempt(attemptId, SettingsWriter.CONFIG_SERVER),
                store.current().base(),
                candidate
        );

        // then
        assertThat(result.outcome()).isEqualTo(SettingsUpdateStatus.APPLIED);
        assertThat(capture.events()).singleElement().satisfies(event -> {
            assertThat(event.eventName()).isEqualTo("TRACING_SETTINGS_UPDATE_APPLIED");
            assertThat(event.fields())
                    .containsEntry("attemptId", attemptId)
                    .containsEntry("changedLeaves", "SAMPLING_ROUTE_RATIOS");
        });
        assertThat(capture.rendered()).doesNotContain(CANARY_ROUTE);
    }

    private static TracingSettings settings(Map<String, Double> routes) {
        return TracingSettings.of(
                true,
                0.5d,
                routes,
                SpanAttributeValidationLevel.WARNING,
                true,
                true,
                AgentLogLevel.INFO
        );
    }
}
