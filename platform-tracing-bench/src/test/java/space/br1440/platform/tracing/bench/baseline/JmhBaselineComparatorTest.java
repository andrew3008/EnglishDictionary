package space.br1440.platform.tracing.bench.baseline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class JmhBaselineComparatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void matchingEntry_comparesLatency() throws Exception {
        Path baseline = writeArray(baselineEntry("space.example.Benchmark.method", "avgt", 100.0));
        Path current = writeArray(baselineEntry("space.example.Benchmark.method", "avgt", 112.0));

        JmhBaselineCompareReport report = JmhBaselineComparator.compare(
                baseline, current, "test-profile");

        assertThat(report.matched()).isEqualTo(1);
        assertThat(report.newEntries()).isZero();
        assertThat(report.hardWarnings()).isEqualTo(1);
        assertThat(report.hardFailures()).isZero();
    }

    @Test
    void avgtLatencyRegression_triggersHardFail() throws Exception {
        Path baseline = writeArray(baselineEntry("space.example.Benchmark.method", "avgt", 100.0));
        Path current = writeArray(baselineEntry("space.example.Benchmark.method", "avgt", 130.0));

        JmhBaselineCompareReport report = JmhBaselineComparator.compare(
                baseline, current, "test-profile");

        assertThat(report.hardFailures()).isEqualTo(1);
        assertThat(report.hasHardFailures()).isTrue();
        assertThat(report.hasBlockingFailures(false)).isTrue();
    }

    @Test
    void sampleLatencyRegression_isDiagnosticOnly() throws Exception {
        Path baseline = writeArray(baselineEntry("space.example.Benchmark.method", "sample", 100.0));
        Path current = writeArray(baselineEntry("space.example.Benchmark.method", "sample", 130.0));

        JmhBaselineCompareReport report = JmhBaselineComparator.compare(
                baseline, current, "test-profile");

        assertThat(report.hardFailures()).isZero();
        assertThat(report.diagnosticFailures()).isEqualTo(1);
        assertThat(report.hasHardFailures()).isFalse();
        assertThat(report.hasDiagnosticFailures()).isTrue();
        assertThat(report.hasBlockingFailures(false)).isFalse();
        assertThat(report.hasBlockingFailures(true)).isTrue();
    }

    @Test
    void allocRegression_triggersHardFailInSampleMode() throws Exception {
        Path baseline = writeArray(baselineEntryWithAlloc("space.example.Benchmark.method", "sample", 100.0, 100.0));
        Path current = writeArray(baselineEntryWithAlloc("space.example.Benchmark.method", "sample", 100.0, 120.0));

        JmhBaselineCompareReport report = JmhBaselineComparator.compare(
                baseline, current, "test-profile");

        assertThat(report.hardFailures()).isEqualTo(1);
        assertThat(report.diagnosticFailures()).isZero();
        assertThat(report.hasBlockingFailures(false)).isTrue();
    }

    @Test
    void missingBaselineEntry_reportedAsNew() throws Exception {
        Path baseline = writeArray(MAPPER.createArrayNode());
        Path current = writeArray(baselineEntry("space.example.Benchmark.new", "avgt", 100.0));

        JmhBaselineCompareReport report = JmhBaselineComparator.compare(
                baseline, current, "test-profile");

        assertThat(report.matched()).isZero();
        assertThat(report.newEntries()).isEqualTo(1);
        assertThat(report.gateUnreliable()).isFalse();
    }

    @Test
    void allCurrentEntriesNewAgainstNonEmptyBaseline_gateUnreliable() throws Exception {
        Path baseline = writeArray(baselineEntry("space.example.Benchmark.old", "avgt", 100.0));
        Path current = writeArray(baselineEntry("space.example.Benchmark.new", "avgt", 100.0));

        JmhBaselineCompareReport report = JmhBaselineComparator.compare(
                baseline, current, "test-profile");

        assertThat(report.matched()).isZero();
        assertThat(report.newEntries()).isEqualTo(1);
        assertThat(report.gateUnreliable()).isTrue();
    }

    @Test
    void largeBaselineFixture_doesNotSkipValidEntries() throws Exception {
        ArrayNode baseline = MAPPER.createArrayNode();
        ArrayNode current = MAPPER.createArrayNode();
        for (int i = 0; i < 100; i++) {
            baseline.add(baselineEntry("space.example.Benchmark.method" + i, "avgt", 100.0 + i));
            current.add(baselineEntry("space.example.Benchmark.method" + i, "avgt", 100.0 + i));
        }
        Path baselineFile = writeArray(baseline);
        Path currentFile = writeArray(current);

        JmhBaselineCompareReport report = JmhBaselineComparator.compare(
                baselineFile, currentFile, "test-profile");

        assertThat(report.matched()).isEqualTo(100);
        assertThat(report.newEntries()).isZero();
        assertThat(report.gateUnreliable()).isFalse();
    }

    @Test
    void compositeSamplerFixture_matchesAllBaselineEntries() throws Exception {
        Path baseline = resourcePath("composite-sampler-baseline-slice.json");
        Path current = resourcePath("composite-sampler-current-slice.json");

        JmhBaselineCompareReport report = JmhBaselineComparator.compare(
                baseline, current, "windows-i5-13500-pr0");

        assertThat(report.currentEntries()).isEqualTo(2);
        assertThat(report.matched()).isEqualTo(2);
        assertThat(report.newEntries()).isZero();
    }

    @Test
    void committedBaseline_compositeSamplerEntries_present() throws Exception {
        Path baseline = Path.of("baselines/windows-i5-13500-pr0/results.json");
        if (!Files.exists(baseline)) {
            return;
        }
        ArrayNode root = (ArrayNode) MAPPER.readTree(baseline.toFile());
        long compositeCount = 0;
        for (var entry : root) {
            if (entry.path("benchmark").asText("").contains("CompositeSampler")) {
                compositeCount++;
            }
        }
        assertThat(compositeCount).isGreaterThanOrEqualTo(24);
    }

    @Test
    void committedBaseline_containsValidationHardGateEntries() throws Exception {
        Path baseline = Path.of("baselines/windows-i5-13500-pr0/results.json");
        if (!Files.exists(baseline)) {
            return;
        }
        ArrayNode root = (ArrayNode) MAPPER.readTree(baseline.toFile());
        long validationCount = 0;
        for (var entry : root) {
            String name = entry.path("benchmark").asText("");
            if (JmhBenchmarkGatePolicy.isValidationHardGateBenchmark(name)
                    && "avgt".equals(entry.path("mode").asText(""))) {
                validationCount++;
            }
        }
        assertThat(validationCount).isEqualTo(6);
    }

    @Test
    void committedBaseline_validationEntries_includeAllocNorm() throws Exception {
        Path baseline = Path.of("baselines/windows-i5-13500-pr0/results.json");
        if (!Files.exists(baseline)) {
            return;
        }
        ArrayNode root = (ArrayNode) MAPPER.readTree(baseline.toFile());
        int withAlloc = 0;
        for (var entry : root) {
            String name = entry.path("benchmark").asText("");
            if (!JmhBenchmarkGatePolicy.isValidationHardGateBenchmark(name)
                    || !"avgt".equals(entry.path("mode").asText(""))) {
                continue;
            }
            var secondary = entry.get("secondaryMetrics");
            if (secondary != null && secondary.has("\u00b7gc.alloc.rate.norm")) {
                withAlloc++;
            }
        }
        assertThat(withAlloc).isEqualTo(6);
    }

    @Test
    void validationOnlyCurrent_doesNotMatchCompositeSamplerFixture() throws Exception {
        Path baseline = resourcePath("composite-sampler-baseline-slice.json");
        Path validationOnly = resourcePath("validation-only-current-slice.json");

        JmhBaselineCompareReport report = JmhBaselineComparator.compare(
                baseline, validationOnly, "test-profile");

        assertThat(report.matched()).isZero();
        assertThat(report.newEntries()).isEqualTo(2);
        assertThat(report.baselineEntries()).isEqualTo(2);
    }

    @Test
    void validationDiagnosticAvgtRegression_isDiagnosticOnly() throws Exception {
        String diagnostic = JmhBenchmarkGatePolicy.VALIDATION_BENCHMARK_CLASS
                + ".validationStrictAllowedMissingAttrDiagnostic";
        Path baseline = writeArray(baselineEntry(diagnostic, "avgt", 1000.0));
        Path current = writeArray(baselineEntry(diagnostic, "avgt", 1500.0));

        JmhBaselineCompareReport report = JmhBaselineComparator.compare(
                baseline, current, "test-profile");

        assertThat(report.hardFailures()).isZero();
        assertThat(report.diagnosticFailures()).isEqualTo(1);
        assertThat(report.hasBlockingFailures(false)).isFalse();
    }

    private static ObjectNode baselineEntry(String benchmark, String mode, double score) {
        ObjectNode entry = MAPPER.createObjectNode();
        entry.put("benchmark", benchmark);
        entry.put("mode", mode);
        ObjectNode primary = entry.putObject("primaryMetric");
        primary.put("score", score);
        return entry;
    }

    private static ObjectNode baselineEntryWithAlloc(
            String benchmark, String mode, double score, double allocNorm) {
        ObjectNode entry = baselineEntry(benchmark, mode, score);
        ObjectNode secondary = entry.putObject("secondaryMetrics");
        ObjectNode alloc = secondary.putObject("\u00b7gc.alloc.rate.norm");
        alloc.put("score", allocNorm);
        return entry;
    }

    private Path writeArray(ArrayNode array) throws Exception {
        Path file = tempDir.resolve("results-" + System.nanoTime() + "-" + array.size() + ".json");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), array);
        return file;
    }

    private Path writeArray(ObjectNode entry) throws Exception {
        ArrayNode array = MAPPER.createArrayNode().add(entry);
        return writeArray(array);
    }

    private Path resourcePath(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/jmh-baseline-fixtures/" + name)) {
            Path file = tempDir.resolve(name);
            Files.writeString(file, new String(input.readAllBytes()));
            return file;
        }
    }
}
