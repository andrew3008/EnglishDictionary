package space.br1440.platform.tracing.autoconfigure.control.jmx.protocol;

import org.junit.jupiter.api.Test;

import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke-тест низкоуровневого {@link PrivateControlRequestEncoder#encodeRequest(PrivateControlSnapshot)}
 * (package-private тестовый вход, не второй публичный API).
 */
class PrivateControlRequestEncoderTest {

    @Test
    void encodesExactCompleteSnapshotWithDeterministicRouteTable() {
        Map<String, Double> routes = new LinkedHashMap<>();
        routes.put("/z", 0.2d);
        routes.put("/a", 0.8d);

        CompositeData request = PrivateControlRequestEncoder.encodeRequest(
                new PrivateControlSnapshot(
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
                "applyTrigger", "attemptId", "candidateDigest",
                "expectedAcceptedSequence", "expectedRuntimeInstanceId",
                "protocolVersion", "runtimePolicy", "sourceAuthority", "sourceRevision");
        CompositeData policy = (CompositeData) request.get("runtimePolicy");
        assertThat(policy.getCompositeType().keySet()).hasSize(8);
        TabularData table = (TabularData) policy.get("samplingRouteRatios");
        assertThat(table.size()).isEqualTo(2);
        assertThat(table.getTabularType().getIndexNames()).containsExactly("routePrefix");
    }
}
