package space.br1440.platform.tracing.otel.javaagent.jmx.observation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ObservationSnapshotGetterGateTest {

    private static final String SOURCE_ROOT = "platform-starters-tracing/"
            + "platform-tracing-otel-javaagent-extension/src/main/java/space/br1440/platform/tracing/otel/"
            + "javaagent/jmx/";

    private static final Map<String, String> OBSERVATION_SOURCE_GETTERS = Map.of(
            "sampling/SamplingObservationSource.java", "getSamplingObservation",
            "scrubbing/ScrubbingObservationSource.java", "getScrubbingObservation",
            "validation/ValidationObservationSource.java", "getValidationObservation",
            "export/ExportObservationSource.java", "getExportObservation",
            "metrics/ProcessorMetricsObservationSource.java", "getProcessorMetricsObservation",
            "diagnostics/DiagnosticsObservationSource.java", "getDiagnosticsObservation");

    @Test
    void observationGettersUsePrecomputedGeneratedContractSnapshots() throws IOException {
        // given
        Path repositoryRoot = repositoryRoot();

        // when
        for (Map.Entry<String, String> entry : OBSERVATION_SOURCE_GETTERS.entrySet()) {
            String source = Files.readString(
                    repositoryRoot.resolve(SOURCE_ROOT + entry.getKey()),
                    StandardCharsets.UTF_8).replace("\r\n", "\n");
            String expectedGetter = "public CompositeData " + entry.getValue()
                    + "() {\n        return snapshotStore.current();\n    }";

            // then
            assertThat(source).contains(expectedGetter);
            assertThat(source).contains("JmxOpenTypes");
            assertThat(source).contains("JmxWireContract");
            assertThat(source).doesNotContain("JmxObservationWireContract");
            assertThat(source).doesNotContain("buildObservationType(");
            assertThat(source).doesNotContain("stringLongMapType(");
            assertThat(source).doesNotContain("stringDoubleMapType(");
        }
        assertThat(Files.exists(repositoryRoot.resolve(
                SOURCE_ROOT + "observation/JmxObservationWireContract.java"))).isFalse();
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Cannot locate repository root from user.dir");
        }
        return current;
    }
}
