package space.br1440.platform.tracing.otel.javaagent.control.state;

import org.junit.jupiter.api.Test;

import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularData;
import javax.management.openmbean.TabularDataSupport;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Codec parity: Agent encode → CompositeData → Agent decode helpers.
 *
 * <p>Spring-side AcceptedCompleteSnapshotDecoder lives in the Spring module
 * and is tested there. Here we verify the Agent encoder produces a
 * CompositeData that satisfies all schema contracts so a Spring decoder
 * implementation following the same spec will achieve semantic equality.
 */
class PrivateControlCodecParityTest {

    @Test
    void encode_allTenLeaves_presentAndCorrect() throws Exception {
        String runtimeId = UUID.randomUUID().toString();
        Map<String, Double> routes = new TreeMap<>();
        routes.put("/orders", 0.5d);
        routes.put("/health", 0.0d);

        EffectiveTracingPolicy policy = EffectiveTracingPolicy.of(
                true, 0.8d, routes, true, false, true, true, PolicyLogLevel.WARN);
        AcceptedPolicyStateStore store = new AcceptedPolicyStateStore(runtimeId, policy);
        PrivateControlOpenTypeCodec codec = new PrivateControlOpenTypeCodec();

        CompositeData cd = codec.encodeAcceptedComplete(store.current());

        assertThat(cd.get("acceptedSequence")).isEqualTo(0L);
        assertThat(cd.get("runtimeInstanceId")).isEqualTo(runtimeId);
        assertThat(cd.get("samplingEnabled")).isEqualTo(true);
        assertThat(cd.get("samplingRatio")).isEqualTo(0.8d);
        assertThat(cd.get("validationEnabled")).isEqualTo(true);
        assertThat(cd.get("validationStrict")).isEqualTo(false);
        assertThat(cd.get("exporterEnabled")).isEqualTo(true);
        assertThat(cd.get("traceControlHeadersEnabled")).isEqualTo(true);
        assertThat(cd.get("diagnosticsLogLevel")).isEqualTo("WARN");

        TabularData routesOut = (TabularData) cd.get("samplingRouteRatios");
        assertThat(routesOut.size()).isEqualTo(2);
    }

    @Test
    void encode_routeRatios_keyOrdering_isNaturalOrder() throws Exception {
        Map<String, Double> input = new java.util.LinkedHashMap<>();
        input.put("/z", 0.9d);
        input.put("/a", 0.1d);
        EffectiveTracingPolicy policy = EffectiveTracingPolicy.of(
                true, 1.0d, input, false, false, true, true, PolicyLogLevel.INFO);
        AcceptedPolicyStateStore store = new AcceptedPolicyStateStore("r1", policy);
        PrivateControlOpenTypeCodec codec = new PrivateControlOpenTypeCodec();

        TabularData routes = (TabularData) codec.encodeAcceptedComplete(store.current())
                .get("samplingRouteRatios");
        java.util.List<String> keys = routes.values().stream()
                .map(row -> (String) ((CompositeData) row).get("routePrefix"))
                .toList();
        assertThat(keys).isSortedAccordingTo(java.util.Comparator.naturalOrder());
    }

    @Test
    void encode_sequencePreserved_afterCommit() throws Exception {
        String runtimeId = UUID.randomUUID().toString();
        EffectiveTracingPolicy p0 = EffectiveTracingPolicy.of(
                true, 1.0d, Map.of(), true, false, true, true, PolicyLogLevel.INFO);
        EffectiveTracingPolicy p1 = EffectiveTracingPolicy.of(
                false, 0.5d, Map.of(), true, false, true, true, PolicyLogLevel.INFO);
        AcceptedPolicyStateStore store = new AcceptedPolicyStateStore(runtimeId, p0);
        PrivateControlOpenTypeCodec codec = new PrivateControlOpenTypeCodec();

        // baseline: sequence = 0
        assertThat(codec.encodeAcceptedComplete(store.current()).get("acceptedSequence"))
                .isEqualTo(0L);

        // commit: sequence becomes 1
        store.commit(store.current().base(), p1);
        assertThat(codec.encodeAcceptedComplete(store.current()).get("acceptedSequence"))
                .isEqualTo(1L);

        // read does not change sequence
        codec.encodeAcceptedComplete(store.current());
        assertThat(store.current().acceptedSequence()).isEqualTo(1L);
    }

    @Test
    void encode_typeName_isExact() throws Exception {
        AcceptedPolicyStateStore store = new AcceptedPolicyStateStore(
                "r2",
                EffectiveTracingPolicy.of(true, 1.0d, Map.of(), false, false, true, true,
                        PolicyLogLevel.DEBUG));
        PrivateControlOpenTypeCodec codec = new PrivateControlOpenTypeCodec();
        CompositeData cd = codec.encodeAcceptedComplete(store.current());
        assertThat(cd.getCompositeType().getTypeName()).isEqualTo("AcceptedCompleteSnapshot/v1");
    }
}
