package space.br1440.platform.tracing.otel.javaagent.control.jmx.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.UUID;
import javax.management.MBeanServer;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.jmx.protocol.PrivateControlObjectNames;
import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsStore;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsValidationConstraints;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingLogLevel;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.LastSettingsUpdate;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateStatus;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.TracingSettingsUpdater;

/**
 * P0: decode-reject and empty attemptId must surface in LastSettingsUpdate and platform projection.
 */
class PrivateControlPreApplyDiagnosticsTest extends PlatformControlMBeanLeakGuard {

    private String runtimeId;
    private TracingSettingsStore store;
    private LastSettingsUpdate diagnostics;
    private PrivateControlPlane plane;
    private MBeanServer privateServer;

    @BeforeEach
    void setUp() throws Exception {
        runtimeId = UUID.randomUUID().toString();
        store = new TracingSettingsStore(
                runtimeId,
                TracingSettings.of(true, 1.0, Map.of(), true, false, true, true, TracingLogLevel.INFO));
        diagnostics = new LastSettingsUpdate();
        plane = new PrivateControlPlane(
                runtimeId,
                store,
                new TracingSettingsUpdater(store, new SettingsValidationConstraints(true), diagnostics),
                diagnostics);
        privateServer = plane.start();
    }

    @AfterEach
    void tearDown() {
        plane.close();
    }

    @Test
    void malformedApply_withCanonicalAttemptId_updatesDiagnosticsAndPlatformProjection()
            throws Exception {
        String attemptId = UUID.randomUUID().toString();
        CompositeData response = apply(typeMismatchRequest(attemptId));

        assertThat(response.get("outcome")).isEqualTo("REJECTED");
        assertThat(response.get("messageCode")).isEqualTo("TYPE_MISMATCH");
        assertThat(response.get("attemptId")).isEqualTo(attemptId);

        assertThat(diagnostics.snapshot().latestOutcome()).isEqualTo(SettingsUpdateStatus.REJECTED);
        assertThat(diagnostics.snapshot().latestAttempt().attemptId()).isEqualTo(attemptId);

        MBeanServer platform = ManagementFactory.getPlatformMBeanServer();
        assertThat(platform.getAttribute(PrivateControlObjectNames.WRITER, "LastApplyOutcome"))
                .isEqualTo("REJECTED");
        assertThat(platform.getAttribute(PrivateControlObjectNames.WRITER, "LastFailureCode"))
                .isEqualTo("TYPE_MISMATCH");
        assertThat(platform.getAttribute(PrivateControlObjectNames.WRITER, "LastAttemptId"))
                .isEqualTo(attemptId);
        assertThat(store.current().acceptedSequence()).isZero();
    }

    @Test
    void malformedApply_withEmptyAttemptId_leavesLastAttemptIdEmpty() throws Exception {
        CompositeData response = apply(typeMismatchRequest(""));

        assertThat(response.get("outcome")).isEqualTo("REJECTED");
        assertThat(response.get("attemptId")).isEqualTo("");

        assertThat(diagnostics.snapshot().latestAttempt()).isNull();
        assertThat(diagnostics.snapshot().latestOutcome()).isEqualTo(SettingsUpdateStatus.REJECTED);

        MBeanServer platform = ManagementFactory.getPlatformMBeanServer();
        assertThat(platform.getAttribute(PrivateControlObjectNames.WRITER, "LastAttemptId"))
                .isEqualTo("");
        assertThat(platform.getAttribute(PrivateControlObjectNames.WRITER, "LastApplyOutcome"))
                .isEqualTo("REJECTED");
    }

    private CompositeData apply(CompositeData request) throws Exception {
        return (CompositeData) privateServer.invoke(
                PrivateControlObjectNames.WRITER,
                "applySnapshot",
                new Object[]{request},
                new String[]{CompositeData.class.getName()});
    }

    private static CompositeData typeMismatchRequest(String attemptId) throws Exception {
        CompositeType type = new CompositeType(
                "wrong",
                "wrong",
                new String[]{"protocolVersion", "attemptId"},
                new String[]{"protocolVersion", "attemptId"},
                new OpenType<?>[]{SimpleType.INTEGER, SimpleType.STRING});
        return new CompositeDataSupport(
                type,
                new String[]{"protocolVersion", "attemptId"},
                new Object[]{1, attemptId});
    }
}
