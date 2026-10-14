package space.br1440.platform.tracing.otel.javaagent.control.jmx.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsStore;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsWriter;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateAttempt;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateStatus;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.TracingSettingsUpdater;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLoggingRuntime;

class InvalidDiagnosticsLogLevelRejectionTest {

    @Test
    void invalid_jmx_log_level_rejects_the_apply_and_keeps_effective_level() throws Exception {
        // given
        TracingSettingsStore store = JmxSettingsMutationFixtures.store();
        TracingSettingsUpdater updater = new TracingSettingsUpdater(store, level -> { });
        TracingSettings debug = TracingSettings.of(
                store.current().policy().samplingEnabled(),
                store.current().policy().samplingRatio(),
                store.current().policy().samplingRouteRatios(),
                store.current().policy().validationLevel(),
                store.current().policy().exporterEnabled(),
                store.current().policy().traceControlHeadersEnabled(),
                AgentLogLevel.DEBUG
        );
        updater.apply(attempt(), store.current().base(), debug);
        AgentLogLevel effectiveBefore = AgentLoggingRuntime.shared().effectiveLevel();
        long sequenceBefore = store.current().acceptedSequence();
        CompositeData request = withDiagnosticsLogLevel(
                JmxSettingsMutationFixtures.request(
                        store.current().agentInstanceId().toString(),
                        store.current().acceptedSequence(),
                        store.current().policy(),
                        UUID.randomUUID().toString()),
                "verbose");
        PlatformSettingsMutationMBean writer = new PlatformSettingsMutationMBean(updater);

        // when
        CompositeData response = (CompositeData) writer.invoke(
                JmxWireContract.OPERATION_SETTINGS_MUTATION_APPLY_SNAPSHOT,
                new Object[]{request},
                new String[]{CompositeData.class.getName()});

        // then
        assertThat(response.get(JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_OUTCOME))
                .isEqualTo(SettingsUpdateStatus.REJECTED.name());
        assertThat(store.current().acceptedSequence()).isEqualTo(sequenceBefore);
        assertThat(store.current().policy().diagnosticsLogLevel()).isEqualTo(AgentLogLevel.DEBUG);
        assertThat(AgentLoggingRuntime.shared().effectiveLevel()).isEqualTo(effectiveBefore);
    }

    @SuppressWarnings("SameParameterValue")
    private static CompositeData withDiagnosticsLogLevel(CompositeData request, String rawLevel) throws Exception {
        CompositeData policy = (CompositeData) request.get(JmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_RUNTIME_POLICY);
        Map<String, Object> policyFields = new LinkedHashMap<>();
        for (String field : policy.getCompositeType().keySet()) {
            policyFields.put(field, policy.get(field));
        }

        policyFields.put(JmxWireContract.FIELD_RUNTIME_POLICY_DIAGNOSTICS_LOG_LEVEL, rawLevel);
        CompositeData rewrittenPolicy = new CompositeDataSupport(policy.getCompositeType(), policyFields);

        Map<String, Object> requestFields = new LinkedHashMap<>();
        for (String field : request.getCompositeType().keySet()) {
            requestFields.put(field, request.get(field));
        }

        requestFields.put(JmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_RUNTIME_POLICY, rewrittenPolicy);
        return new CompositeDataSupport(request.getCompositeType(), requestFields);
    }

    private static SettingsUpdateAttempt attempt() {
        return new SettingsUpdateAttempt(
                UUID.randomUUID().toString(),
                SettingsWriter.CONFIG_SERVER
        );
    }
}
