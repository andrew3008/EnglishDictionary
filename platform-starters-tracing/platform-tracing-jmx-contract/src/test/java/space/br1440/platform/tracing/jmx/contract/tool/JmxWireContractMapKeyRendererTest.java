package space.br1440.platform.tracing.jmx.contract.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Objects;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class JmxWireContractMapKeyRendererTest {

    private static Class<?> generatedContract;

    @BeforeAll
    static void compileGeneratedContract() throws Exception {
        ContractDocument contract = ContractDocumentFactory.load(contractManifest());
        String packageName = "space.br1440.platform.tracing.jmx.contract.generated.mapkeys";
        generatedContract = GeneratedContractSourceCompiler.compileAndLoad(
                "jmx-wire-map-keys-",
                "renderWireContract",
                new Class<?>[]{ContractDocument.class, String.class},
                new Object[]{contract, packageName},
                packageName,
                "JmxWireContract"
        );
    }

    @Test
    void rendersConstantsForDeclaredMapKeys() throws Exception {
        // given
        Class<?> contractClass = generatedContract;

        // when
        Object exportBatches = contractClass
                .getField("KEY_SAFE_EXPORTER_METRICS_EXPORT_BATCHES")
                .get(null);
        Object circuitBreakerFailures = contractClass
                .getField("KEY_SCRUBBING_METRICS_CIRCUIT_BREAKERS_FAILURES_TOTAL")
                .get(null);
        Object skippedCircuitOpen = contractClass
                .getField("KEY_SCRUBBING_METRICS_SKIPPED_CIRCUIT_OPEN")
                .get(null);

        // then
        assertThat(exportBatches).isEqualTo("export_batches");
        assertThat(circuitBreakerFailures).isEqualTo("circuit_breakers.failures.total");
        assertThat(skippedCircuitOpen).isEqualTo("skipped_circuit_open");
    }

    private static Path contractManifest() throws URISyntaxException {
        return Path.of(Objects.requireNonNull(
                JmxWireContractMapKeyRendererTest.class.getResource("/jmx-wire-contract.yaml"),
                "jmx-wire-contract.yaml"
        ).toURI());
    }
}
