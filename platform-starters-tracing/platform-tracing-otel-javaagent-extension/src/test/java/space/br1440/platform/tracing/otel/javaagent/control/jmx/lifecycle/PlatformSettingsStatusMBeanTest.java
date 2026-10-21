package space.br1440.platform.tracing.otel.javaagent.control.jmx.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.OpenMBeanAttributeInfo;
import javax.management.openmbean.OpenMBeanInfoSupport;
import javax.management.openmbean.TabularData;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.AcceptedPolicyTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsStore;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsSourceAuthority;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsWriter;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateAttempt;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateResult;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateStatus;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.TracingSettingsUpdater;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;

class PlatformSettingsStatusMBeanTest {

    @Test
    void exposesExactGeneratedOpenMBeanContract() {
        // given
        TracingSettingsUpdater updater = updater(
                new AgentInstanceId(UUID.randomUUID()),
                settings(1.0d, Map.of()));
        PlatformSettingsStatusMBean mBean = new PlatformSettingsStatusMBean(updater);

        // when
        MBeanInfo info = mBean.getMBeanInfo();

        // then
        assertThat(info).isInstanceOf(OpenMBeanInfoSupport.class);
        assertThat(info.getClassName()).isEqualTo(PlatformSettingsStatusMBean.class.getName());
        assertThat(info.getAttributes()).hasSize(1);
        assertThat(info.getOperations()).isEmpty();
        assertThat(info.getConstructors()).isEmpty();
        assertThat(info.getNotifications()).isEmpty();

        MBeanAttributeInfo attribute = info.getAttributes()[0];
        assertThat(attribute.getName()).isEqualTo(
                JmxWireContract.ATTRIBUTE_SETTINGS_STATUS_ACCEPTED_POLICY_SNAPSHOT);
        assertThat(attribute.isReadable()).isTrue();
        assertThat(attribute.isWritable()).isFalse();
        assertThat(attribute.isIs()).isFalse();
        assertThat(attribute).isInstanceOf(OpenMBeanAttributeInfo.class);
        assertThat(attribute.getType()).isEqualTo(CompositeData.class.getName());
        assertThat(((OpenMBeanAttributeInfo) attribute).getOpenType())
                .isEqualTo(JmxOpenTypes.ACCEPTED_POLICY_SNAPSHOT);
    }

    @Test
    void captureFailureDoesNotExposeForeignThrowable() {
        // given
        TracingSettingsUpdater updater = mock(TracingSettingsUpdater.class);
        RuntimeException captureFailure = new ForeignStatusFailure("foreign status failure");
        when(updater.current()).thenThrow(captureFailure);
        PlatformSettingsStatusMBean mBean = new PlatformSettingsStatusMBean(updater);

        // when
        MBeanException thrown = catchThrowableOfType(
                MBeanException.class,
                () -> mBean.getAttribute(
                        JmxWireContract.ATTRIBUTE_SETTINGS_STATUS_ACCEPTED_POLICY_SNAPSHOT));

        // then
        assertThat(thrown.getTargetException()).isNull();
        assertThat(thrown.getCause()).isNull();
        assertThat(thrown.getSuppressed()).isEmpty();
        assertThat(thrown.getMessage())
                .isEqualTo("JMX_SETTINGS_STATUS_CAPTURE_FAILED: Settings status capture failed")
                .doesNotContain(
                        captureFailure.getClass().getName(),
                        captureFailure.getMessage());
    }

    @Test
    void returnsCoherentGeneratedAcceptedPolicySnapshot() throws Exception {
        // given
        AgentInstanceId agentInstanceId = new AgentInstanceId(UUID.randomUUID());
        TracingSettings initial = settings(1.0d, Map.of());
        TracingSettings candidate = settings(0.25d, Map.of("/orders", 0.5d));
        TracingSettingsStore store = AcceptedPolicyTestFixtures.bootstrapAgentDefaultsStore(agentInstanceId, initial);
        TracingSettingsUpdater updater = new TracingSettingsUpdater(store, level -> { });
        SettingsUpdateAttempt attempt = new SettingsUpdateAttempt(
                UUID.randomUUID().toString(),
                SettingsWriter.CONFIG_SERVER
        );
        SettingsUpdateResult update = updater.apply(attempt, store.current().base(), candidate);
        PlatformSettingsStatusMBean mBean = new PlatformSettingsStatusMBean(updater);

        // when
        CompositeData snapshot = (CompositeData) mBean.getAttribute(
                JmxWireContract.ATTRIBUTE_SETTINGS_STATUS_ACCEPTED_POLICY_SNAPSHOT);

        // then
        assertThat(update.outcome()).isEqualTo(SettingsUpdateStatus.APPLIED);
        assertThat(snapshot.getCompositeType())
                .isEqualTo(JmxOpenTypes.ACCEPTED_POLICY_SNAPSHOT);
        assertThat(snapshot.get(
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_ACCEPTED_SEQUENCE))
                .isEqualTo(1L);
        assertThat(snapshot.get(
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_AGENT_INSTANCE_ID))
                .isEqualTo(agentInstanceId.toString());
        assertThat(snapshot.get(
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SOURCE_AUTHORITY))
                .isEqualTo(SettingsSourceAuthority.CONFIG_SERVER.name());
        assertThat(snapshot.get(
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SAMPLING_ENABLED))
                .isEqualTo(true);
        assertThat(snapshot.get(
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SAMPLING_RATIO))
                .isEqualTo(0.25d);
        assertThat(snapshot.get(
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_VALIDATION_LEVEL))
                .isEqualTo(SpanAttributeValidationLevel.WARNING.name());
        assertThat(snapshot.get(
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_EXPORTER_ENABLED))
                .isEqualTo(true);
        assertThat(snapshot.get(
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_TRACE_CONTROL_HEADERS_ENABLED))
                .isEqualTo(true);
        assertThat(snapshot.get(
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_DIAGNOSTICS_LOG_LEVEL))
                .isEqualTo(AgentLogLevel.INFO.name());

        TabularData routes = (TabularData) snapshot.get(
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SAMPLING_ROUTE_RATIOS);
        assertThat(routes.getTabularType()).isEqualTo(JmxOpenTypes.ROUTE_RATIOS);
        assertThat(routes.size()).isEqualTo(1);
        CompositeData route = routes.get(new Object[]{"/orders"});
        assertThat(route.getCompositeType()).isEqualTo(JmxOpenTypes.ROUTE_RATIO);
        assertThat(route.get(JmxWireContract.FIELD_ROUTE_RATIO_ROUTE_PREFIX))
                .isEqualTo("/orders");
        assertThat(route.get(JmxWireContract.FIELD_ROUTE_RATIO_RATIO))
                .isEqualTo(0.5d);
    }

    private static TracingSettingsUpdater updater(AgentInstanceId agentInstanceId,
                                                  TracingSettings settings) {
        return new TracingSettingsUpdater(
                AcceptedPolicyTestFixtures.bootstrapAgentDefaultsStore(agentInstanceId, settings),
                level -> { }
        );
    }

    private static TracingSettings settings(double samplingRatio,
                                            Map<String, Double> routes) {
        return TracingSettings.of(
                true,
                samplingRatio,
                routes,
                SpanAttributeValidationLevel.WARNING,
                true,
                true,
                AgentLogLevel.INFO
        );
    }

    private static final class ForeignStatusFailure extends RuntimeException {

        private ForeignStatusFailure(String message) {
            super(message);
        }
    }
}
