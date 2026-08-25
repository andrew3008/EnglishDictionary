package space.br1440.platform.tracing.otel.javaagent.control.jmx.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.TabularDataSupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsDigest;
import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsStore;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsValidationConstraints;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingLogLevel;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.LastSettingsUpdate;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.TracingSettingsUpdater;

class PrivateControlWriterBoundaryTest {

    private String runtimeId;
    private TracingSettingsStore store;
    private LastSettingsUpdate diagnostics;
    private MBeanServer server;

    @BeforeEach
    void setUp() throws Exception {
        runtimeId = UUID.randomUUID().toString();
        store = store(runtimeId);
        diagnostics = new LastSettingsUpdate();
        server = MBeanServerFactory.createMBeanServer(PrivateControlObjectNames.PRIVATE_DOMAIN);
        server.registerMBean(
                new PrivateControlWriterMBean(
                        new TracingSettingsUpdater(
                                store,
                                new SettingsValidationConstraints(true),
                                diagnostics),
                        store),
                PrivateControlObjectNames.WRITER);
    }

    @AfterEach
    void tearDown() {
        MBeanServerFactory.releaseMBeanServer(server);
    }

    @Test
    void appliesCompleteSnapshotAndRejectsStaleOrMalformedInput() throws Exception {
        TracingSettings candidate = policy(0.25d);
        CompositeData applied = invoke(
                request(runtimeId, 0L, candidate, UUID.randomUUID().toString()));
        assertThat(applied.get("outcome")).isEqualTo("APPLIED");
        assertThat(applied.get("acceptedSequence")).isEqualTo(1L);
        assertThat(store.current().policy()).isEqualTo(candidate);

        CompositeData stale = invoke(
                request(runtimeId, 0L, policy(0.5d), UUID.randomUUID().toString()));
        assertThat(stale.get("outcome")).isEqualTo("STALE");
        assertThat(stale.get("messageCode")).isEqualTo("ACCEPTED_SEQUENCE_MISMATCH");
        assertThat(store.current().acceptedSequence()).isEqualTo(1L);

        CompositeData malformed = new CompositeDataSupport(
                new javax.management.openmbean.CompositeType(
                        "wrong",
                        "wrong",
                        new String[]{"protocolVersion"},
                        new String[]{"protocolVersion"},
                        new javax.management.openmbean.OpenType<?>[]{
                                javax.management.openmbean.SimpleType.INTEGER
                        }),
                new String[]{"protocolVersion"},
                new Object[]{1});
        CompositeData rejected = invoke(malformed);
        assertThat(rejected.get("outcome")).isEqualTo("REJECTED");
        assertThat(rejected.get("messageCode")).isEqualTo("TYPE_MISMATCH");
        assertThat(store.current().acceptedSequence()).isEqualTo(1L);
        assertThat(diagnostics.snapshot().latestOutcome().name()).isEqualTo("REJECTED");
        assertThat(diagnostics.snapshot().latestMessageCode().name()).isEqualTo("TYPE_MISMATCH");
    }

    @Test
    void rejectsWellFormedCandidateDigestMismatchWithoutChangingAcceptedState() throws Exception {
        TracingSettingsRevision acceptedBefore = store.current();
        String attemptId = UUID.randomUUID().toString();
        CompositeData validRequest = request(
                runtimeId,
                acceptedBefore.acceptedSequence(),
                policy(0.25d),
                attemptId);
        String validDigest = (String) validRequest.get("candidateDigest");
        String mismatchedDigest = (validDigest.charAt(0) == '0' ? "1" : "0")
                + validDigest.substring(1);

        CompositeData rejected = invoke(withCandidateDigest(validRequest, mismatchedDigest));

        assertThat(rejected.getCompositeType()).isEqualTo(PrivateControlWireTypes.RESPONSE);
        assertThat(rejected.get("outcome")).isEqualTo("REJECTED");
        assertThat(rejected.get("messageCode")).isEqualTo("INVALID_VALUE");
        assertThat(rejected.get("attemptId")).isEqualTo(attemptId);
        assertThat(rejected.get("acceptedSequence")).isEqualTo(acceptedBefore.acceptedSequence());
        assertThat(rejected.get("payloadDigest"))
                .isEqualTo(TracingSettingsDigest.sha256(acceptedBefore.policy()));
        assertThat(store.current()).isSameAs(acceptedBefore);
        assertThat(store.current().policy()).isEqualTo(acceptedBefore.policy());
    }

    @Test
    void readAcceptedCompleteReturnsConsistentSnapshot() throws Exception {
        CompositeData snapshot = readAcceptedComplete();

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.getCompositeType().getTypeName())
                .isEqualTo("AcceptedCompleteSnapshot/v1");
        assertThat(snapshot.get("runtimeInstanceId")).isEqualTo(runtimeId);
        assertThat(snapshot.get("acceptedSequence")).isEqualTo(0L);
        assertThat(snapshot.get("samplingEnabled")).isEqualTo(true);
        assertThat(snapshot.get("samplingRatio")).isEqualTo(1.0d);
        assertThat(snapshot.get("validationEnabled")).isEqualTo(true);
        assertThat(snapshot.get("validationStrict")).isEqualTo(false);
        assertThat(snapshot.get("exporterEnabled")).isEqualTo(true);
        assertThat(snapshot.get("traceControlHeadersEnabled")).isEqualTo(true);
        assertThat(snapshot.get("diagnosticsLogLevel")).isEqualTo("INFO");

        CompositeData secondSnapshot = readAcceptedComplete();
        assertThat(secondSnapshot.get("acceptedSequence")).isEqualTo(0L);
    }

    private CompositeData invoke(CompositeData request) throws Exception {
        return (CompositeData) server.invoke(
                PrivateControlObjectNames.WRITER,
                "applySnapshot",
                new Object[]{request},
                new String[]{CompositeData.class.getName()});
    }

    private CompositeData readAcceptedComplete() throws Exception {
        return (CompositeData) server.invoke(
                PrivateControlObjectNames.WRITER,
                "readAcceptedComplete",
                new Object[0],
                new String[0]);
    }

    private static CompositeData request(
            String runtimeId,
            long sequence,
            TracingSettings policy,
            String attemptId) throws Exception {
        TabularDataSupport routes = new TabularDataSupport(PrivateControlWireTypes.ROUTES);
        for (Map.Entry<String, Double> route : policy.samplingRouteRatios().entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("routePrefix", route.getKey());
            row.put("ratio", route.getValue());
            routes.put(new CompositeDataSupport(PrivateControlWireTypes.ROUTE_ROW, row));
        }

        Map<String, Object> policyFields = new LinkedHashMap<>();
        policyFields.put("diagnosticsLogLevel", policy.diagnosticsLogLevel().name());
        policyFields.put("exporterEnabled", policy.exporterEnabled());
        policyFields.put("samplingEnabled", policy.samplingEnabled());
        policyFields.put("samplingRatio", policy.samplingRatio());
        policyFields.put("samplingRouteRatios", routes);
        policyFields.put("traceControlHeadersEnabled", policy.traceControlHeadersEnabled());
        policyFields.put("validationEnabled", policy.validationEnabled());
        policyFields.put("validationStrict", policy.validationStrict());
        CompositeData policyData = new CompositeDataSupport(
                PrivateControlWireTypes.POLICY,
                policyFields);

        Map<String, Object> requestFields = new LinkedHashMap<>();
        requestFields.put("applyTrigger", "REFRESH");
        requestFields.put("attemptId", attemptId);
        requestFields.put("candidateDigest", TracingSettingsDigest.sha256(policy));
        requestFields.put("expectedAcceptedSequence", sequence);
        requestFields.put("expectedRuntimeInstanceId", runtimeId);
        requestFields.put("protocolVersion", PrivateControlObjectNames.PROTOCOL_VERSION);
        requestFields.put("runtimePolicy", policyData);
        requestFields.put("sourceAuthority", "CONFIG_SERVER");
        requestFields.put("sourceRevision", "revision-1");
        return new CompositeDataSupport(PrivateControlWireTypes.REQUEST, requestFields);
    }

    private static CompositeData withCandidateDigest(
            CompositeData request,
            String candidateDigest) throws Exception {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (String field : request.getCompositeType().keySet()) {
            fields.put(field, request.get(field));
        }
        fields.put("candidateDigest", candidateDigest);
        return new CompositeDataSupport(request.getCompositeType(), fields);
    }

    private static TracingSettingsStore store(String runtimeId) {
        return new TracingSettingsStore(runtimeId, policy(1.0d));
    }

    private static TracingSettings policy(double ratio) {
        return TracingSettings.of(
                true,
                ratio,
                Map.of("/orders", 0.5d),
                true,
                false,
                true,
                true,
                TracingLogLevel.INFO);
    }
}
