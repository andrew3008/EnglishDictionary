package space.br1440.platform.tracing.autoconfigure.control.jmx.protocol;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.control.jmx.PrivateControlLocator;
import space.br1440.platform.tracing.autoconfigure.control.jmx.protocol.PrivateControlWireTypes.WireField;
import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.ExpectedSettingsRevision;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Semantic-mapping тест {@link PrivateControlRequestEncoder} (Commit 2 из плана
 * {@code wire-field-descriptors}, §9.1).
 * <p>
 * Проверяет не только форму wire-запроса (как раньше), но точное значение каждого поля —
 * это regression-тест именно на дефект, ради которого введён Map-конструктор
 * {@code CompositeDataSupport}: позиционная подмена двух полей одного {@code OpenType}
 * (например, {@code sourceAuthority} и {@code applyTrigger}, оба STRING) не давала ни
 * {@code OpenDataException}, ни compile error. Каждому такому полю здесь присвоено уникальное
 * значение, поэтому подобная перестановка сделала бы этот тест красным.
 */
class PrivateControlRequestEncoderTest {

    private static final String ATTEMPT_ID = "attempt-11111111";
    private static final String SOURCE_AUTHORITY = "CONFIG_SERVER";
    private static final String APPLY_TRIGGER = "REFRESH";
    private static final String SOURCE_REVISION = "revision-22222222";
    private static final String EXPECTED_RUNTIME_INSTANCE_ID = "runtime-33333333";
    private static final String CANDIDATE_DIGEST = "a".repeat(64);
    private static final long EXPECTED_ACCEPTED_SEQUENCE = 7L;
    private static final String DIAGNOSTICS_LOG_LEVEL = "DEBUG";
    private static final double SAMPLING_RATIO = 0.37d;

    @Test
    void encodesEveryRootRequestFieldWithExactValues() {
        CompositeData request = PrivateControlRequestEncoder.encodeRequest(snapshotWithBooleans(
                false, false, false, false, false));

        assertThat(request.getCompositeType().getTypeName())
                .isEqualTo("space.br1440.platform.tracing.control.ApplySnapshotRequest.v1");
        assertThat(request.get(WireField.ATTEMPT_ID.wireName())).isEqualTo(ATTEMPT_ID);
        assertThat(request.get(WireField.SOURCE_AUTHORITY.wireName())).isEqualTo(SOURCE_AUTHORITY);
        assertThat(request.get(WireField.APPLY_TRIGGER.wireName())).isEqualTo(APPLY_TRIGGER);
        assertThat(request.get(WireField.SOURCE_REVISION.wireName())).isEqualTo(SOURCE_REVISION);
        assertThat(request.get(WireField.EXPECTED_RUNTIME_INSTANCE_ID.wireName()))
                .isEqualTo(EXPECTED_RUNTIME_INSTANCE_ID);
        assertThat(request.get(WireField.EXPECTED_ACCEPTED_SEQUENCE.wireName()))
                .isEqualTo(EXPECTED_ACCEPTED_SEQUENCE);
        assertThat(request.get(WireField.PROTOCOL_VERSION.wireName()))
                .isEqualTo(PrivateControlLocator.PROTOCOL_VERSION);
    }

    @Test
    void encodesEveryNestedPolicyLeafWithExactValues() {
        CompositeData request = PrivateControlRequestEncoder.encodeRequest(snapshotWithBooleans(
                false, false, false, false, false));
        CompositeData policy = (CompositeData) request.get(WireField.RUNTIME_POLICY.wireName());

        assertThat(policy.getCompositeType().getTypeName())
                .isEqualTo("space.br1440.platform.tracing.control.RuntimePolicy.v1");
        assertThat(policy.get(WireField.DIAGNOSTICS_LOG_LEVEL.wireName())).isEqualTo(DIAGNOSTICS_LOG_LEVEL);
        assertThat(policy.get(WireField.SAMPLING_RATIO.wireName())).isEqualTo(SAMPLING_RATIO);
    }

    @Test
    void encodesBothRouteRowsAsExactPrefixToRatioPairsIndexedByRoutePrefix() {
        Map<String, Double> routes = new LinkedHashMap<>();
        routes.put("/checkout", 0.6d);
        routes.put("/search", 0.2d);

        CompositeData request = PrivateControlRequestEncoder.encodeRequest(
                snapshot(false, false, false, false, false, routes));
        CompositeData policy = (CompositeData) request.get(WireField.RUNTIME_POLICY.wireName());
        TabularData table = (TabularData) policy.get(WireField.SAMPLING_ROUTE_RATIOS.wireName());

        assertThat(table.getTabularType().getIndexNames()).containsExactly(WireField.ROUTE_PREFIX.wireName());
        assertThat(table.size()).isEqualTo(2);

        CompositeData checkoutRow = table.get(new Object[]{"/checkout"});
        assertThat(checkoutRow.get(WireField.ROUTE_PREFIX.wireName())).isEqualTo("/checkout");
        assertThat(checkoutRow.get(WireField.ROUTE_RATIO.wireName())).isEqualTo(0.6d);

        CompositeData searchRow = table.get(new Object[]{"/search"});
        assertThat(searchRow.get(WireField.ROUTE_PREFIX.wireName())).isEqualTo("/search");
        assertThat(searchRow.get(WireField.ROUTE_RATIO.wireName())).isEqualTo(0.2d);
    }

    // ── one-hot boolean coverage: каждый policy-boolean уникально наблюдаем на wire ────────

    @Test
    void oneHotExporterEnabled() {
        assertOneHotObservable(WireField.EXPORTER_ENABLED,
                snapshotWithBooleans(true, false, false, false, false));
    }

    @Test
    void oneHotSamplingEnabled() {
        assertOneHotObservable(WireField.SAMPLING_ENABLED,
                snapshotWithBooleans(false, true, false, false, false));
    }

    @Test
    void oneHotTraceControlHeadersEnabled() {
        assertOneHotObservable(WireField.TRACE_CONTROL_HEADERS_ENABLED,
                snapshotWithBooleans(false, false, true, false, false));
    }

    @Test
    void oneHotValidationEnabled() {
        assertOneHotObservable(WireField.VALIDATION_ENABLED,
                snapshotWithBooleans(false, false, false, true, false));
    }

    @Test
    void oneHotValidationStrict() {
        assertOneHotObservable(WireField.VALIDATION_STRICT,
                snapshotWithBooleans(false, false, false, false, true));
    }

    private static void assertOneHotObservable(WireField expectedTrueField, PrivateControlSnapshot snapshot) {
        CompositeData request = PrivateControlRequestEncoder.encodeRequest(snapshot);
        CompositeData policy = (CompositeData) request.get(WireField.RUNTIME_POLICY.wireName());

        for (WireField booleanField : new WireField[]{
                WireField.EXPORTER_ENABLED, WireField.SAMPLING_ENABLED,
                WireField.TRACE_CONTROL_HEADERS_ENABLED, WireField.VALIDATION_ENABLED,
                WireField.VALIDATION_STRICT}) {
            boolean expected = booleanField == expectedTrueField;
            assertThat(policy.get(booleanField.wireName()))
                    .as(booleanField.wireName())
                    .isEqualTo(expected);
        }
    }

    // ── candidateDigest: два отдельных контракта ────────────────────────────────────────────

    @Test
    void lowLevelEncodeRequestWiresTheGivenCandidateDigestVerbatim() {
        PrivateControlSnapshot snapshot = snapshotWithBooleans(false, false, false, false, false);
        CompositeData request = PrivateControlRequestEncoder.encodeRequest(snapshot);

        assertThat(request.get(WireField.CANDIDATE_DIGEST.wireName())).isEqualTo(snapshot.candidateDigest());
    }

    @Test
    void convenienceEncodeApplyRequestComputesCandidateDigestFromPolicyItself() {
        CompleteTracingSettings policy = CompleteTracingSettings.builder()
                .samplingEnabled(true)
                .samplingRatio(SAMPLING_RATIO)
                .samplingRouteRatios(Map.of("/checkout", 0.6d))
                .validationEnabled(true)
                .validationStrict(false)
                .exporterEnabled(true)
                .traceControlHeadersEnabled(false)
                .diagnosticsLogLevel(DIAGNOSTICS_LOG_LEVEL)
                .build();
        ExpectedSettingsRevision expectedBase =
                new ExpectedSettingsRevision(EXPECTED_RUNTIME_INSTANCE_ID, EXPECTED_ACCEPTED_SEQUENCE);

        CompositeData request = PrivateControlRequestEncoder.encodeApplyRequest(
                expectedBase, ATTEMPT_ID, SOURCE_AUTHORITY, APPLY_TRIGGER, SOURCE_REVISION, policy);

        assertThat(request.get(WireField.CANDIDATE_DIGEST.wireName()))
                .isEqualTo(CandidateDigest.sha256(policy));
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────

    private static PrivateControlSnapshot snapshotWithBooleans(boolean exporterEnabled,
                                                                 boolean samplingEnabled,
                                                                 boolean traceControlHeadersEnabled,
                                                                 boolean validationEnabled,
                                                                 boolean validationStrict) {
        return snapshot(exporterEnabled, samplingEnabled, traceControlHeadersEnabled,
                validationEnabled, validationStrict, Map.of());
    }

    private static PrivateControlSnapshot snapshot(boolean exporterEnabled,
                                                     boolean samplingEnabled,
                                                     boolean traceControlHeadersEnabled,
                                                     boolean validationEnabled,
                                                     boolean validationStrict,
                                                     Map<String, Double> samplingRouteRatios) {
        return new PrivateControlSnapshot(
                EXPECTED_RUNTIME_INSTANCE_ID,
                EXPECTED_ACCEPTED_SEQUENCE,
                ATTEMPT_ID,
                SOURCE_AUTHORITY,
                APPLY_TRIGGER,
                SOURCE_REVISION,
                CANDIDATE_DIGEST,
                samplingEnabled,
                SAMPLING_RATIO,
                samplingRouteRatios,
                validationEnabled,
                validationStrict,
                exporterEnabled,
                traceControlHeadersEnabled,
                DIAGNOSTICS_LOG_LEVEL);
    }
}
