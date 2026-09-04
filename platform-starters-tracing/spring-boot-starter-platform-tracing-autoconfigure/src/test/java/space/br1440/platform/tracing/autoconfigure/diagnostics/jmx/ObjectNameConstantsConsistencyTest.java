package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

final class ObjectNameConstantsConsistencyTest {

    @Test
    void generatedSchemasHaveIdenticalIdentity() {
        assertThat(JmxWireContract.SCHEMA_ID)
                .isEqualTo(space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated
                        .JmxWireContract.SCHEMA_ID);

        assertThat(JmxWireContract.SCHEMA_DIGEST_SHA256)
                .isEqualTo(space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated
                        .JmxWireContract.SCHEMA_DIGEST_SHA256);

        assertThat(JmxWireContract.DESCRIPTOR_DIGEST_SHA256)
                .isEqualTo(space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated
                        .JmxWireContract.DESCRIPTOR_DIGEST_SHA256);
    }

    @Test
    void springOpenTypesExactlyMatchCanonicalAgentSchema() {
        assertThat(JmxOpenTypes.ROUTE_RATIO)
                .isEqualTo(space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated
                        .JmxOpenTypes.ROUTE_RATIO);

        assertThat(JmxOpenTypes.ROUTE_RATIOS)
                .isEqualTo(space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated
                        .JmxOpenTypes.ROUTE_RATIOS);

        assertThat(JmxOpenTypes.RUNTIME_POLICY)
                .isEqualTo(space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated
                        .JmxOpenTypes.RUNTIME_POLICY);

        assertThat(JmxOpenTypes.APPLY_SNAPSHOT_REQUEST)
                .isEqualTo(space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated
                        .JmxOpenTypes.APPLY_SNAPSHOT_REQUEST);

        assertThat(JmxOpenTypes.APPLY_SNAPSHOT_RESPONSE)
                .isEqualTo(space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated
                        .JmxOpenTypes.APPLY_SNAPSHOT_RESPONSE);

        assertThat(JmxOpenTypes.ACCEPTED_POLICY_SNAPSHOT)
                .isEqualTo(space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated
                        .JmxOpenTypes.ACCEPTED_POLICY_SNAPSHOT);
    }

}
