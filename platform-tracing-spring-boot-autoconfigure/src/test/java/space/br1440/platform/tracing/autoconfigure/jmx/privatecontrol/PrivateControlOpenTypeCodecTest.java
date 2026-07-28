package space.br1440.platform.tracing.autoconfigure.jmx.privatecontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;

import org.junit.jupiter.api.Test;

class PrivateControlOpenTypeCodecTest {

    @Test
    void encodesExactCompleteSnapshotWithDeterministicRouteTable() {
        PrivateControlOpenTypeCodec codec = new PrivateControlOpenTypeCodec();
        Map<String, Double> routes = new LinkedHashMap<>();
        routes.put("/z", 0.2d);
        routes.put("/a", 0.8d);

        CompositeData request = codec.encodeRequest(
                new PrivateControlOpenTypeCodec.PrivateControlSnapshot(
                        UUID.randomUUID().toString(),
                        7L,
                        UUID.randomUUID().toString(),
                        "CONFIG_SERVER",
                        "REFRESH",
                        "revision-7",
                        "a".repeat(64),
                        true,
                        0.5d,
                        routes,
                        true,
                        false,
                        true,
                        true,
                        "INFO"));

        assertThat(request.getCompositeType().getTypeName())
                .isEqualTo("space.br1440.platform.tracing.control.ApplySnapshotRequest.v1");
        assertThat(request.getCompositeType().keySet()).containsExactlyInAnyOrder(
                "applyTrigger",
                "attemptId",
                "candidateDigest",
                "expectedAcceptedSequence",
                "expectedRuntimeInstanceId",
                "protocolVersion",
                "runtimePolicy",
                "sourceAuthority",
                "sourceRevision");
        CompositeData policy = (CompositeData) request.get("runtimePolicy");
        assertThat(policy.getCompositeType().keySet()).hasSize(8);
        javax.management.openmbean.TabularData table =
                (javax.management.openmbean.TabularData) policy.get("samplingRouteRatios");
        assertThat(table.size()).isEqualTo(2);
        assertThat(table.getTabularType().getIndexNames()).containsExactly("routePrefix");
    }

    @Test
    void rejectsUnsupportedResponseProtocolAsContractFailure() throws Exception {
        PrivateControlOpenTypeCodec codec = new PrivateControlOpenTypeCodec();
        CompositeData valid = codec.encodeResponse(
                new PrivateControlOpenTypeCodec.PrivateControlResponse(
                        "APPLIED",
                        UUID.randomUUID().toString(),
                        UUID.randomUUID().toString(),
                        8L,
                        "a".repeat(64),
                        "NONE",
                        false,
                        new String[0],
                        new String[0]));
        Map<String, Object> values = new HashMap<>();
        for (String key : valid.getCompositeType().keySet()) {
            values.put(key, valid.get(key));
        }
        values.put("protocolVersion", 2);
        CompositeData incompatible = new CompositeDataSupport(
                valid.getCompositeType(),
                values);

        assertThatThrownBy(() -> codec.decodeResponse(incompatible))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported private control response protocol");
    }
}
