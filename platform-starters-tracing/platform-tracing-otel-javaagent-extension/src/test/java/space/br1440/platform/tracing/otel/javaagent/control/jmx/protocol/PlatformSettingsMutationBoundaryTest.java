package space.br1440.platform.tracing.otel.javaagent.control.jmx.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.openmbean.CompositeData;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsDigest;
import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsStore;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsValidationConstraints;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.LastSettingsUpdate;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.TracingSettingsUpdater;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.GeneratedJmxOpenTypes;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.GeneratedJmxWireContract;

final class PlatformSettingsMutationBoundaryTest {

    private TracingSettingsStore store;
    private LastSettingsUpdate diagnostics;
    private MBeanServer server;

    @BeforeEach
    void setUp() throws Exception {
        store = JmxSettingsMutationFixtures.store();
        diagnostics = new LastSettingsUpdate();
        TracingSettingsUpdater updater = new TracingSettingsUpdater(
                store,
                new SettingsValidationConstraints(true),
                diagnostics);
        server = MBeanServerFactory.createMBeanServer();
        server.registerMBean(
                new PlatformSettingsMutationMBean(updater),
                GeneratedJmxWireContract.SETTINGS_MUTATION_OBJECT_NAME);
    }

    @AfterEach
    void tearDown() {
        MBeanServerFactory.releaseMBeanServer(server);
    }

    @Test
    void appliesCompleteSnapshotAndRejectsStaleOrMalformedInput() throws Exception {
        // given
        String runtimeId = store.current().runtimeInstanceId();
        TracingSettings candidate = JmxSettingsMutationFixtures.settings(0.25d);

        // when
        CompositeData applied = invoke(JmxSettingsMutationFixtures.request(
                runtimeId,
                0L,
                candidate,
                UUID.randomUUID().toString()));
        CompositeData stale = invoke(JmxSettingsMutationFixtures.request(
                runtimeId,
                0L,
                JmxSettingsMutationFixtures.settings(0.5d),
                UUID.randomUUID().toString()));
        CompositeData malformed = invoke(JmxSettingsMutationFixtures.malformedRequest());

        // then
        assertThat(applied.get(GeneratedJmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_OUTCOME))
                .isEqualTo("APPLIED");
        assertThat(applied.get(
                GeneratedJmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_ACCEPTED_SEQUENCE))
                .isEqualTo(1L);
        assertThat(store.current().policy()).isEqualTo(candidate);
        assertThat(stale.get(GeneratedJmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_OUTCOME))
                .isEqualTo("STALE");
        assertThat(stale.get(GeneratedJmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_MESSAGE_CODE))
                .isEqualTo("ACCEPTED_SEQUENCE_MISMATCH");
        assertThat(malformed.get(GeneratedJmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_OUTCOME))
                .isEqualTo("REJECTED");
        assertThat(malformed.get(GeneratedJmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_MESSAGE_CODE))
                .isEqualTo("TYPE_MISMATCH");
        assertThat(store.current().acceptedSequence()).isEqualTo(1L);
        assertThat(diagnostics.snapshot().latestOutcome().name()).isEqualTo("REJECTED");
        assertThat(diagnostics.snapshot().latestMessageCode().name()).isEqualTo("TYPE_MISMATCH");
    }

    @Test
    void rejectsCandidateDigestMismatchWithoutChangingAcceptedState() throws Exception {
        // given
        TracingSettingsRevision acceptedBefore = store.current();
        String attemptId = UUID.randomUUID().toString();
        CompositeData validRequest = JmxSettingsMutationFixtures.request(
                acceptedBefore.runtimeInstanceId(),
                acceptedBefore.acceptedSequence(),
                JmxSettingsMutationFixtures.settings(0.25d),
                attemptId);
        String validDigest = (String) validRequest.get(
                GeneratedJmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_CANDIDATE_DIGEST);
        String mismatchedDigest = (validDigest.charAt(0) == '0' ? "1" : "0")
                + validDigest.substring(1);

        // when
        CompositeData rejected = invoke(JmxSettingsMutationFixtures.withCandidateDigest(
                validRequest,
                mismatchedDigest));

        // then
        assertThat(rejected.getCompositeType())
                .isEqualTo(GeneratedJmxOpenTypes.APPLY_SNAPSHOT_RESPONSE);
        assertThat(rejected.get(GeneratedJmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_OUTCOME))
                .isEqualTo("REJECTED");
        assertThat(rejected.get(GeneratedJmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_MESSAGE_CODE))
                .isEqualTo("INVALID_VALUE");
        assertThat(rejected.get(GeneratedJmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_ATTEMPT_ID))
                .isEqualTo(attemptId);
        assertThat(rejected.get(
                GeneratedJmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_ACCEPTED_SEQUENCE))
                .isEqualTo(acceptedBefore.acceptedSequence());
        assertThat(rejected.get(
                GeneratedJmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_PAYLOAD_DIGEST))
                .isEqualTo(TracingSettingsDigest.sha256(acceptedBefore.policy()));
        assertThat(store.current()).isSameAs(acceptedBefore);
    }

    private CompositeData invoke(CompositeData request) throws Exception {
        return (CompositeData) server.invoke(
                GeneratedJmxWireContract.SETTINGS_MUTATION_OBJECT_NAME,
                GeneratedJmxWireContract.OPERATION_SETTINGS_MUTATION_APPLY_SNAPSHOT,
                new Object[]{request},
                new String[]{CompositeData.class.getName()});
    }
}
