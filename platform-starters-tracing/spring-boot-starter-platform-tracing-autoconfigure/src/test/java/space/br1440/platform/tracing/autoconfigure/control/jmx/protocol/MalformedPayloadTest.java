package space.br1440.platform.tracing.autoconfigure.control.jmx.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static space.br1440.platform.tracing.autoconfigure.support.RecordingSettingsWriter.recordingSettingsWriter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.management.MBeanServer;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularData;
import javax.management.openmbean.TabularDataSupport;
import javax.management.openmbean.TabularType;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceGuard;
import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceTestSupport;
import space.br1440.platform.tracing.autoconfigure.control.jmx.PlatformJmxTracingSettingsClient;
import space.br1440.platform.tracing.autoconfigure.control.management.TracingSettingsMutationService;
import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsContractViolationException;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.autoconfigure.support.AcceptedSettingsTestFixtures;
import space.br1440.platform.tracing.autoconfigure.support.ReadinessJmxTestFixtures;
import space.br1440.platform.tracing.autoconfigure.support.RecordingSettingsWriter;
import space.br1440.platform.tracing.autoconfigure.support.SettingsJmxTestFixtures;

class MalformedPayloadTest {

    private static final String AGENT_INSTANCE_ID = "bc269f80-ad51-4baa-818c-3595346290df";

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected CompositeData");
    }

    @Test
    void rejectsString() {
        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode("not-composite-data"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected CompositeData");
    }

    @Test
    void rejectsInteger() {
        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(42))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected CompositeData");
    }

    @Test
    void rejectsWrongTypeName() throws OpenDataException {
        CompositeType wrongType = new CompositeType(
                "WrongName/v1", "WrongName/v1",
                new String[]{"foo"}, new String[]{"foo"},
                new OpenType<?>[]{SimpleType.STRING});
        CompositeData cd = new CompositeDataSupport(
                wrongType, new String[]{"foo"}, new Object[]{"bar"});

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AcceptedCompleteSnapshot/v1");
    }

    @Test
    void rejectsV2TypeName() throws OpenDataException {
        CompositeType v2 = new CompositeType(
                "AcceptedCompleteSnapshot/v2", "AcceptedCompleteSnapshot/v2",
                new String[]{"acceptedSequence"}, new String[]{"acceptedSequence"},
                new OpenType<?>[]{SimpleType.LONG}
        );

        CompositeData cd = new CompositeDataSupport(v2, new String[]{"acceptedSequence"}, new Object[]{1L});

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unexpected CompositeType");
    }

    @Test
    void rejectsMissingAgentInstanceId() throws OpenDataException {
        CompositeData cd = buildWith(
                new String[]{"acceptedSequence", "samplingEnabled", "samplingRatio",
                        "samplingRouteRatios", "validationLevel",
                        "exporterEnabled", "traceControlHeadersEnabled", "diagnosticsLogLevel"},
                new OpenType<?>[]{SimpleType.LONG, SimpleType.BOOLEAN, SimpleType.DOUBLE,
                        JmxOpenTypes.ROUTE_RATIOS, SimpleType.STRING,
                        SimpleType.BOOLEAN, SimpleType.BOOLEAN, SimpleType.STRING},
                new Object[]{1L, true, 0.5d, emptyRouteTable(), "WARNING", true, true, "INFO"});

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingAcceptedSequence() throws OpenDataException {
        CompositeData cd = buildWith(
                new String[]{"agentInstanceId", "samplingEnabled", "samplingRatio",
                        "samplingRouteRatios", "validationLevel",
                        "exporterEnabled", "traceControlHeadersEnabled", "diagnosticsLogLevel"},
                new OpenType<?>[]{SimpleType.STRING, SimpleType.BOOLEAN, SimpleType.DOUBLE,
                        JmxOpenTypes.ROUTE_RATIOS, SimpleType.STRING,
                        SimpleType.BOOLEAN, SimpleType.BOOLEAN, SimpleType.STRING},
                new Object[]{AGENT_INSTANCE_ID, true, 0.5d, emptyRouteTable(), "WARNING", true, true, "INFO"});

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingSamplingRouteRatios() throws OpenDataException {
        CompositeData cd = buildWith(
                new String[]{"acceptedSequence", "agentInstanceId", "samplingEnabled",
                        "samplingRatio", "validationLevel",
                        "exporterEnabled", "traceControlHeadersEnabled", "diagnosticsLogLevel"},
                new OpenType<?>[]{SimpleType.LONG, SimpleType.STRING, SimpleType.BOOLEAN,
                        SimpleType.DOUBLE, SimpleType.STRING,
                        SimpleType.BOOLEAN, SimpleType.BOOLEAN, SimpleType.STRING},
                new Object[]{1L, AGENT_INSTANCE_ID, true, 0.5d, "WARNING", true, true, "INFO"});

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsSequenceAsString() throws OpenDataException {
        CompositeData cd = buildWith(
                acceptedSnapshotFieldNames(),
                new OpenType<?>[]{
                        SimpleType.STRING, SimpleType.STRING, SimpleType.STRING,
                        SimpleType.BOOLEAN,
                        SimpleType.DOUBLE,
                        JmxOpenTypes.ROUTE_RATIOS,
                        SimpleType.STRING,
                        SimpleType.BOOLEAN,
                        SimpleType.BOOLEAN,
                        SimpleType.STRING
                },
                new Object[]{
                        "STRING-NOT-LONG", AGENT_INSTANCE_ID,
                        "BOOTSTRAP",
                        true,
                        0.5d,
                        emptyRouteTable(),
                        "WARNING",
                        true,
                        true,
                        "INFO"
                });

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsSamplingRatioAsString() throws OpenDataException {
        String[] keys = {
                "acceptedSequence", "agentInstanceId", "samplingEnabled",
                "samplingRatio", "samplingRouteRatios", "validationLevel",
                "exporterEnabled", "traceControlHeadersEnabled",
                "diagnosticsLogLevel"
        };
        OpenType<?>[] types = {
                SimpleType.LONG, SimpleType.STRING, SimpleType.BOOLEAN,
                SimpleType.STRING, // <-- wrong type
                JmxOpenTypes.ROUTE_RATIOS, SimpleType.STRING,
                SimpleType.BOOLEAN, SimpleType.BOOLEAN,
                SimpleType.STRING
        };
        CompositeType ct = new CompositeType(
                "AcceptedCompleteSnapshot/v1", "AcceptedCompleteSnapshot/v1",
                keys, keys, types);
        CompositeData cd = new CompositeDataSupport(ct, keys, new Object[]{
                1L, AGENT_INSTANCE_ID, true, "0.5", emptyRouteTable(),
                "WARNING", true, true, "INFO"
        });

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullAgentInstanceId() throws OpenDataException {
        CompositeData cd = buildValidWith(
                1L, null,
                true, 0.5d, emptyRouteTable(),
                "WARNING", true, true, "INFO");

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullDiagnosticsLogLevel() throws OpenDataException {
        CompositeData cd = buildValidWith(
                1L, AGENT_INSTANCE_ID,
                true, 0.5d, emptyRouteTable(),
                "WARNING", true, true, null);

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnknownDiagnosticsLogLevel() throws OpenDataException {
        CompositeData cd = buildValidWith(
                1L, AGENT_INSTANCE_ID,
                true, 0.5d, emptyRouteTable(),
                "WARNING", true, true, "VERBOSE");

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("diagnosticsLogLevel");
    }

    @Test
    void rejectsLowercaseDiagnosticsLogLevel() throws OpenDataException {
        // given
        CompositeData snapshot = buildValidWith(
                1L, AGENT_INSTANCE_ID,
                true, 0.5d, emptyRouteTable(),
                "WARNING", true, true, "info");

        // when
        Throwable failure = catchThrowable(() -> AcceptedPolicySnapshotDecoder.decode(snapshot));

        // then
        assertThat(failure)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("diagnosticsLogLevel");
    }

    @Test
    void rejectsLowercaseValidationLevel() throws OpenDataException {
        // given
        CompositeData snapshot = buildValidWith(
                1L, AGENT_INSTANCE_ID,
                true, 0.5d, emptyRouteTable(),
                "warning", true, true, "INFO");

        // when
        Throwable failure = catchThrowable(() -> AcceptedPolicySnapshotDecoder.decode(snapshot));

        // then
        assertThat(failure)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("validationLevel");
    }

    @Test
    void rejectsBlankAgentInstanceId() throws OpenDataException {
        CompositeData cd = buildValidWith(
                1L, "   ",
                true, 0.5d, emptyRouteTable(),
                "WARNING", true, true, "INFO");

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agentInstanceId");
    }

    @Test
    void rejectsEmptyAgentInstanceId() throws OpenDataException {
        CompositeData cd = buildValidWith(
                1L, "",
                true, 0.5d, emptyRouteTable(),
                "WARNING", true, true, "INFO");

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agentInstanceId");
    }

    @Test
    void rejectsNegativeAcceptedSequence() throws OpenDataException {
        CompositeData cd = buildValidWith(
                -1L, AGENT_INSTANCE_ID,
                true, 0.5d, emptyRouteTable(),
                "WARNING", true, true, "INFO");

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith(contractPoint())
                .hasMessageContaining("revision violates domain invariant")
                .hasMessageContaining("acceptedSequence")
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNaNSamplingRatio() throws OpenDataException {
        CompositeData cd = buildValidWith(
                1L, AGENT_INSTANCE_ID,
                true, Double.NaN, emptyRouteTable(),
                "WARNING", true, true, "INFO");

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith(contractPoint())
                .hasMessageContaining("policy violates domain invariant")
                .hasMessageContaining("ratio must be finite");
    }

    @Test
    void rejectsInfiniteSamplingRatio() throws OpenDataException {
        CompositeData cd = buildValidWith(
                1L, AGENT_INSTANCE_ID,
                true, Double.POSITIVE_INFINITY, emptyRouteTable(),
                "WARNING", true, true, "INFO");

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith(contractPoint())
                .hasMessageContaining("policy violates domain invariant")
                .hasMessageContaining("ratio must be finite");
    }

    @Test
    void rejectsSamplingRatioAboveOne() throws OpenDataException {
        CompositeData cd = buildValidWith(
                1L, AGENT_INSTANCE_ID,
                true, 1.0001d, emptyRouteTable(),
                "WARNING", true, true, "INFO");

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith(contractPoint())
                .hasMessageContaining("policy violates domain invariant")
                .hasMessageContaining("ratio must be finite");
    }

    @Test
    void rejectsSamplingRatioBelow0() throws OpenDataException {
        CompositeData cd = buildValidWith(
                1L, AGENT_INSTANCE_ID,
                true, -0.001d, emptyRouteTable(),
                "WARNING", true, true, "INFO");

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith(contractPoint())
                .hasMessageContaining("policy violates domain invariant")
                .hasMessageContaining("ratio must be finite");
    }

    @Test
    void rejectsNaNRouteRatio() throws OpenDataException {
        TabularData badRoutes = buildRouteTable(Map.of("/api", Double.NaN));
        CompositeData cd = buildValidWith(
                1L, AGENT_INSTANCE_ID,
                true, 0.5d, badRoutes,
                "WARNING", true, true, "INFO"
        );

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInfiniteRouteRatio() throws OpenDataException {
        TabularData badRoutes = buildRouteTable(
                Map.of("/api", Double.NEGATIVE_INFINITY)
        );

        CompositeData cd = buildValidWith(
                1L, AGENT_INSTANCE_ID,
                true, 0.5d, badRoutes,
                "WARNING", true, true, "INFO"
        );

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOversizedRouteTable() throws OpenDataException {
        java.util.LinkedHashMap<String, Double> bigRoutes = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 200; i++) {
            bigRoutes.put("/route-" + i, 0.5d);
        }

        CompositeData cd = buildValidWith(
                1L, AGENT_INSTANCE_ID,
                true, 0.5d, buildRouteTable(bigRoutes),
                "WARNING", true, true, "INFO"
        );

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("samplingRouteRatios");
    }

    @Test
    void rejectsOversizedRouteKey() throws OpenDataException {
        String longKey = "a".repeat(JmxWireContract.MAX_ROUTE_PREFIX_UTF8_BYTES + 1);
        CompositeData cd = buildValidWith(
                1L, AGENT_INSTANCE_ID,
                true, 0.5d, buildRouteTable(Map.of(longKey, 0.5d)),
                "WARNING", true, true, "INFO");

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOversizedAgentInstanceId() throws OpenDataException {
        String longId = "x".repeat(40_960);
        CompositeData cd = buildValidWith(
                1L, longId,
                true, 0.5d, emptyRouteTable(),
                "WARNING", true, true, "INFO");

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical");
    }

    @Test
    void acceptsRoutePrefixAtUtf8Limit() throws OpenDataException {
        String exact = "a".repeat(JmxWireContract.MAX_ROUTE_PREFIX_UTF8_BYTES);
        CompositeData cd = buildValidWith(
                1L, AGENT_INSTANCE_ID,
                true, 0.5d, buildRouteTable(Map.of(exact, 0.5d)),
                "WARNING", true, true, "INFO");

        assertThat(AcceptedPolicySnapshotDecoder.decode(cd).policy().samplingRouteRatios())
                .containsEntry(exact, 0.5d);
    }

    @Test
    void rejectsEmptyDiagnosticsLogLevel() throws OpenDataException {
        CompositeData cd = buildValidWith(
                1L, AGENT_INSTANCE_ID,
                true, 0.5d, emptyRouteTable(),
                "WARNING", true, true, "");

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("diagnosticsLogLevel");
    }

    @Test
    void rejectsEmptyRoutePrefix() throws OpenDataException {
        CompositeData cd = buildValidWith(
                1L, AGENT_INSTANCE_ID,
                true, 0.5d, buildRouteTable(Map.of("", 0.5d)),
                "WARNING", true, true, "INFO");

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }

    @Test
    void rejectsBlankRoutePrefix() throws OpenDataException {
        CompositeData cd = buildValidWith(
                1L, AGENT_INSTANCE_ID,
                true, 0.5d, buildRouteTable(Map.of("   ", 0.5d)),
                "WARNING", true, true, "INFO");

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }

    @Test
    void rejectsIsoControlRoutePrefix() throws OpenDataException {
        CompositeData cd = buildValidWith(
                1L, AGENT_INSTANCE_ID,
                true, 0.5d, buildRouteTable(Map.of("/api\u0001", 0.5d)),
                "WARNING", true, true, "INFO");

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ISO control")
                .hasMessageNotContaining("/api");
    }

    @Test
    void rejectsRouteRatioBelowZero() throws OpenDataException {
        CompositeData cd = buildValidWith(
                1L, AGENT_INSTANCE_ID,
                true, 0.5d, buildRouteTable(Map.of("/api", -0.1d)),
                "WARNING", true, true, "INFO");

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsRouteRatioAboveOne() throws OpenDataException {
        CompositeData cd = buildValidWith(
                1L, AGENT_INSTANCE_ID,
                true, 0.5d, buildRouteTable(Map.of("/api", 1.1d)),
                "WARNING", true, true, "INFO");

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnknownExtraField() throws OpenDataException {
        String[] keys = {
                "acceptedSequence", "agentInstanceId", "samplingEnabled",
                "samplingRatio", "samplingRouteRatios", "validationLevel",
                "exporterEnabled", "traceControlHeadersEnabled",
                "diagnosticsLogLevel",
                "unknownFuture"
        };
        OpenType<?>[] types = {
                SimpleType.LONG, SimpleType.STRING, SimpleType.BOOLEAN,
                SimpleType.DOUBLE, JmxOpenTypes.ROUTE_RATIOS, SimpleType.STRING,
                SimpleType.BOOLEAN, SimpleType.BOOLEAN,
                SimpleType.STRING, SimpleType.STRING
        };
        CompositeType ct = new CompositeType(
                "AcceptedCompleteSnapshot/v1", "AcceptedCompleteSnapshot/v1",
                keys, keys, types);
        CompositeData cd = new CompositeDataSupport(ct, keys, new Object[]{
                1L, AGENT_INSTANCE_ID, true, 0.5d, emptyRouteTable(),
                "WARNING", true, true, "INFO", "future-value"
        });

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unexpected CompositeType");
    }

    @Test
    void rejectsWrongRouteRowTypeName() throws OpenDataException {
        CompositeData cd = buildSnapshotWithWrongRouteRowType();

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownSourceAuthorityIsReportedAsStatusResponseDecodeFailure() throws Exception {
        // given
        MBeanServer server = mock(MBeanServer.class);
        when(server.getMBeanInfo(JmxWireContract.EXTENSION_READINESS_OBJECT_NAME))
                .thenReturn(ReadinessJmxTestFixtures.metadata());
        when(server.getAttribute(
                JmxWireContract.EXTENSION_READINESS_OBJECT_NAME,
                JmxWireContract.ATTRIBUTE_EXTENSION_READINESS_READINESS_SNAPSHOT))
                .thenReturn(ReadinessJmxTestFixtures.readySnapshot());
        when(server.getMBeanInfo(JmxWireContract.SETTINGS_STATUS_OBJECT_NAME))
                .thenReturn(SettingsJmxTestFixtures.statusInfo());
        when(server.getMBeanInfo(JmxWireContract.SETTINGS_MUTATION_OBJECT_NAME))
                .thenReturn(SettingsJmxTestFixtures.mutationInfo());
        when(server.getAttribute(
                JmxWireContract.SETTINGS_STATUS_OBJECT_NAME,
                JmxWireContract.ATTRIBUTE_SETTINGS_STATUS_ACCEPTED_POLICY_SNAPSHOT))
                .thenReturn(buildValidWithAuthority(
                        "HELM",
                        1L,
                        ReadinessJmxTestFixtures.AGENT_INSTANCE_ID,
                        true,
                        0.5d,
                        emptyRouteTable(),
                        "WARNING",
                        true,
                        true,
                        "INFO")
                );

        AgentReadinessCoherenceGuard coherenceGuard = new AgentReadinessCoherenceGuard(server);
        AgentReadinessCoherenceTestSupport.pinCurrent(coherenceGuard);
        PlatformJmxTracingSettingsClient client = new PlatformJmxTracingSettingsClient(server, coherenceGuard);
        client.initialize();

        // when
        // then
        assertThatThrownBy(client::current)
                .isInstanceOf(TracingSettingsContractViolationException.class)
                .hasMessageStartingWith("JMX_CLIENT_STATUS_RESPONSE_DECODE_FAILED:")
                .cause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith(contractPoint())
                .hasMessageContaining("sourceAuthority has unsupported value 'HELM'");
        assertThat(coherenceGuard.terminalFailureCode()).isNull();
    }

    @Test
    void rejectsUnsupportedSourceAuthority() throws OpenDataException {
        // given
        CompositeData snapshot = buildValidWithAuthority(
                "HELM",
                1L,
                AGENT_INSTANCE_ID,
                true,
                0.5d,
                emptyRouteTable(),
                "WARNING",
                true,
                true,
                "INFO");

        // when / then
        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(snapshot))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith(contractPoint())
                .hasMessageContaining("sourceAuthority has unsupported value 'HELM'")
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsCyrillicRoutePrefixThatExceedsUtf8ByteLimit() throws OpenDataException {
        // given
        String prefix = "я".repeat(128);
        CompositeData snapshot = buildValidWith(
                1L,
                AGENT_INSTANCE_ID,
                true,
                0.5d,
                buildRouteTable(Map.of(prefix, 0.5d)),
                "WARNING",
                true,
                true,
                "INFO");
        RecordingSettingsWriter writer = recordingSettingsWriter(
                (expectedBase, policy, source) -> {
                    throw new AssertionError("invalid route prefix must not reach the applier");
                },
                () -> Optional.of(AcceptedSettingsTestFixtures.bootstrapAgentDefaults(
                        1L,
                        AGENT_INSTANCE_ID,
                        CompleteTracingSettings.helmBootstrapDefaults())));
        TracingSettingsMutationService actuator = new TracingSettingsMutationService(writer);

        // when / then
        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(snapshot))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith(contractPoint())
                .hasMessageContaining("policy violates domain invariant")
                .hasMessageContaining("UTF-8")
                .hasMessageNotContaining(prefix);
        assertThatThrownBy(() -> actuator.replaceSamplingRouteRatios(Map.of(prefix, 0.5d)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UTF-8")
                .hasMessageNotContaining(prefix);
        assertThat(writer.applyCallCount()).isZero();
    }

    private static String contractPoint() {
        return JmxWireContract.ATTRIBUTE_SETTINGS_STATUS_ACCEPTED_POLICY_SNAPSHOT;
    }

    @SuppressWarnings("SameParameterValue")
    private CompositeData buildValidWith(long seq, String rid,
                                         boolean sampEn, double sampRatio, TabularData routes,
                                         String validationLevel, boolean expEn,
                                         boolean tcHeaders, String logLevel) throws OpenDataException {
        return buildValidWithAuthority(
                "BOOTSTRAP",
                seq,
                rid,
                sampEn,
                sampRatio,
                routes,
                validationLevel,
                expEn,
                tcHeaders,
                logLevel
        );
    }

    private CompositeData buildValidWithAuthority(String authority,
                                                  long seq,
                                                  String rid,
                                                  boolean sampEn,
                                                  double sampRatio,
                                                  TabularData routes,
                                                  String validationLevel,
                                                  boolean expEn,
                                                  boolean tcHeaders,
                                                  String logLevel) throws OpenDataException {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_ACCEPTED_SEQUENCE, seq);
        fields.put(JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_AGENT_INSTANCE_ID, rid);
        fields.put(JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SOURCE_AUTHORITY, authority);
        fields.put(JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SAMPLING_ENABLED, sampEn);
        fields.put(JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SAMPLING_RATIO, sampRatio);
        fields.put(JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SAMPLING_ROUTE_RATIOS, routes);
        fields.put(JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_VALIDATION_LEVEL, validationLevel);
        fields.put(JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_EXPORTER_ENABLED, expEn);
        fields.put(JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_TRACE_CONTROL_HEADERS_ENABLED, tcHeaders);
        fields.put(JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_DIAGNOSTICS_LOG_LEVEL, logLevel);
        return new CompositeDataSupport(JmxOpenTypes.ACCEPTED_POLICY_SNAPSHOT, fields);
    }

    private static String[] acceptedSnapshotFieldNames() {
        return new String[]{
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_ACCEPTED_SEQUENCE,
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_AGENT_INSTANCE_ID,
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SOURCE_AUTHORITY,
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SAMPLING_ENABLED,
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SAMPLING_RATIO,
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SAMPLING_ROUTE_RATIOS,
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_VALIDATION_LEVEL,
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_EXPORTER_ENABLED,
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_TRACE_CONTROL_HEADERS_ENABLED,
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_DIAGNOSTICS_LOG_LEVEL,
        };
    }

    private CompositeData buildWith(String[] keys, OpenType<?>[] types, Object[] values)
            throws OpenDataException {
        CompositeType ct = new CompositeType(
                "AcceptedCompleteSnapshot/v1", "AcceptedCompleteSnapshot/v1",
                keys, keys, types
        );
        return new CompositeDataSupport(ct, keys, values);
    }

    private CompositeData buildSnapshotWithWrongRouteRowType() throws OpenDataException {
        CompositeType wrongRow = new CompositeType(
                "space.br1440.platform.tracing.control.RouteRatio.v2",
                "space.br1440.platform.tracing.control.RouteRatio.v2",
                new String[]{"ratio", "routePrefix"},
                new String[]{"ratio", "routePrefix"},
                new OpenType<?>[]{SimpleType.DOUBLE, SimpleType.STRING}
        );

        TabularType wrongTabular = new TabularType(
                "space.br1440.platform.tracing.control.RouteRatios.v1",
                "space.br1440.platform.tracing.control.RouteRatios.v1",
                wrongRow,
                new String[]{"routePrefix"}
        );

        return buildWith(
                acceptedSnapshotFieldNames(),
                new OpenType<?>[]{
                        SimpleType.LONG, SimpleType.STRING, SimpleType.STRING,
                        SimpleType.BOOLEAN,
                        SimpleType.DOUBLE,
                        wrongTabular,
                        SimpleType.STRING,
                        SimpleType.BOOLEAN,
                        SimpleType.BOOLEAN,
                        SimpleType.STRING
                },
                new Object[]{
                        1L, AGENT_INSTANCE_ID, "BOOTSTRAP",
                        true, 0.5d, new TabularDataSupport(wrongTabular),
                        "WARNING", true, true, "INFO"
                });
    }

    private TabularData emptyRouteTable() {
        return new TabularDataSupport(JmxOpenTypes.ROUTE_RATIOS);
    }

    private TabularData buildRouteTable(Map<String, Double> routes) throws OpenDataException {
        TabularDataSupport table = new TabularDataSupport(JmxOpenTypes.ROUTE_RATIOS);
        for (var e : routes.entrySet()) {
            table.put(new CompositeDataSupport(
                    JmxOpenTypes.ROUTE_RATIO,
                    new String[]{"ratio", "routePrefix"},
                    new Object[]{e.getValue(), e.getKey()}));
        }
        return table;
    }
}
