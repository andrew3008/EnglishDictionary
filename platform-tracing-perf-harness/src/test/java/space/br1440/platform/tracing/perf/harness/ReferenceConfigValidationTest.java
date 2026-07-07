package space.br1440.platform.tracing.perf.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ReferenceConfigValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static JsonSchema REFERENCE_SCHEMA;

    private static final List<String> K8S_TEMPLATES = List.of(
            "config/k8s/namespace.yaml",
            "config/k8s/perf-app-deployment.yaml",
            "config/k8s/perf-app-service.yaml",
            "config/k8s/perf-app-configmap.yaml",
            "config/k8s/otel-collector-configmap.yaml",
            "config/k8s/otel-collector-deployment.yaml",
            "config/k8s/otel-collector-service.yaml",
            "config/k8s/k6-job.yaml",
            "config/k8s/k6-scenarios-configmap.yaml",
            "config/k8s/reference-sink-deployment.yaml",
            "config/k8s/reference-sink-service.yaml"
    );

    @BeforeAll
    static void loadSchema() throws Exception {
        JsonNode schemaNode = MAPPER.readTree(
                Path.of("evidence/templates/reference-summary.schema.json").toFile());
        REFERENCE_SCHEMA = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(schemaNode);
    }

    @Test
    void k8sTemplates_exist() throws Exception {
        for (String rel : K8S_TEMPLATES) {
            assertThat(Files.exists(Path.of(rel)))
                    .as("K8s template %s", rel)
                    .isTrue();
        }
    }

    @Test
    void collectorConfig_doesNotUseLoggingAsSoleTracesExporter() throws Exception {
        String yaml = Files.readString(Path.of("config/k8s/otel-collector-configmap.yaml"));
        assertThat(yaml).contains("otlphttp/reference_sink");
        assertThat(yaml).doesNotContain("exporters: [logging]");
        assertThat(yaml).contains("memory_limiter");
    }

    @Test
    void w004Checklist_containsKeyContractTerms() throws Exception {
        String checklist = Files.readString(Path.of("evidence/templates/W-004-resolution-checklist.md"));
        List.of(
                "cpu.per1kRps",
                "container_memory_working_set_bytes/cAdvisor",
                "constant-arrival-rate",
                "dropped_iterations",
                "throttleRatioPct",
                "jfrStartMode",
                "backpressureEvidenceValid",
                "reproductionRunCount",
                "budgetStatus"
        ).forEach(term -> assertThat(checklist).as("checklist term: %s", term).contains(term));
    }

    @Test
    void e2PreflightAndRunbook_exist() {
        assertThat(Path.of("evidence/templates/PR-9H-E2-preflight-checklist.md")).exists();
        assertThat(Path.of("evidence/templates/PR-9H-E2-runbook.md")).exists();
        assertThat(Path.of("evidence/reference/README.md")).exists();
    }

    @Test
    void gitignore_ignoresRawReferenceArtifacts() throws Exception {
        String gitignore = Files.readString(Path.of("../.gitignore"));
        assertThat(gitignore).contains("platform-tracing-perf-harness/evidence/reference/**/*.jfr");
        assertThat(gitignore).contains("platform-tracing-perf-harness/evidence/reference/**/*.hprof");
    }

    @Test
    void k6ScenariosConfigMapTemplate_exists() throws Exception {
        String yaml = Files.readString(Path.of("config/k8s/k6-scenarios-configmap.yaml"));
        assertThat(yaml).contains("perf-harness-k6-scenarios");
        assertThat(yaml).contains("prepareK6ReferenceConfigMap");
    }

    @Test
    void skeletonSummaryStructure_isSchemaValid() throws Exception {
        ObjectNode skeleton = (ObjectNode) eligibleBaseline().deepCopy();
        skeleton.put("nonAuthoritative", true);
        skeleton.put("w004Eligible", false);
        skeleton.putArray("nonAuthoritativeReasons")
                .add("singleRunOnly")
                .add("provisionalBudgetOnly")
                .add("missingMetrics");
        skeleton.remove("budgets");
        skeleton.set("budgets", MAPPER.createObjectNode()
                .set("cpuOverheadPct", budgetEntry(3.0, "provisional")));
        ((ObjectNode) skeleton.path("budgets")).set("rssOverheadPct", budgetEntry(10.0, "provisional"));
        ((ObjectNode) skeleton.path("budgets")).set("p99LatencyDeltaPct", budgetEntry(15.0, "provisional"));
        ((ObjectNode) skeleton.path("budgets")).set("errorRatePct", budgetEntry(0.1, "provisional"));
        skeleton.set("reproducibility", MAPPER.createObjectNode().put("reproductionRunCount", 1));
        ObjectNode caveats = MAPPER.createObjectNode();
        caveats.putArray("missingMetrics").add("skeleton-only");
        caveats.put("notForProductionReadinessReason", "E2 first run");
        skeleton.set("caveats", caveats);
        assertValid(skeleton);
    }

    private static ObjectNode budgetEntry(double threshold, String status) {
        ObjectNode entry = MAPPER.createObjectNode();
        entry.put("threshold", threshold);
        entry.put("budgetStatus", status);
        return entry;
    }

    @Test
    void sampleLocalReferenceSummary_validatesAgainstSchema() throws Exception {
        JsonNode sample = MAPPER.readTree(
                Path.of("evidence/reference-local/examples/sample-local-reference-summary.json").toFile());
        assertValid(sample);
        assertThat(sample.path("labTier").asText()).isEqualTo("LOCAL_REFERENCE_LAB");
        assertThat(sample.path("w004Eligible").asBoolean()).isFalse();
        assertThat(sample.path("nonAuthoritative").asBoolean()).isTrue();
    }

    @Test
    void localLabTier_withW004EligibleTrue_fails() throws Exception {
        JsonNode invalid = MAPPER.readTree(
                Path.of("evidence/reference-local/examples/sample-local-reference-summary.json").toFile());
        ObjectNode copy = invalid.deepCopy();
        copy.put("w004Eligible", true);
        assertInvalid(copy);
    }

    @Test
    void localLabTier_withoutLocalEnvironmentReason_fails() throws Exception {
        JsonNode invalid = MAPPER.readTree(
                Path.of("evidence/reference-local/examples/sample-local-reference-summary.json").toFile());
        ObjectNode copy = invalid.deepCopy();
        copy.putArray("nonAuthoritativeReasons").add("singleRunOnly");
        assertInvalid(copy, "localEnvironment");
    }

    @Test
    void localLabTier_skeletonStructure_validates() throws Exception {
        ObjectNode skeleton = MAPPER.createObjectNode();
        skeleton.put("evidenceTier", "REFERENCE");
        skeleton.put("labTier", "LOCAL_REFERENCE_LAB");
        skeleton.put("nonAuthoritative", true);
        skeleton.putArray("nonAuthoritativeReasons")
                .add("localEnvironment").add("singleRunOnly").add("provisionalBudgetOnly").add("missingMetrics");
        skeleton.put("w004Eligible", false);
        skeleton.put("profileId", "gentoo-local-reference-lab-v1");
        skeleton.put("runId", "test");
        skeleton.put("gitCommit", "abc");
        skeleton.putObject("environment").put("clusterAlias", "kind").put("namespace", "platform-tracing-reference-lab");
        skeleton.putObject("scenario").put("scenarioId", "S0").put("loadModel", "constant-arrival-rate");
        skeleton.putObject("metrics");
        skeleton.putObject("artifacts");
        skeleton.putObject("classification");
        skeleton.putObject("budgets");
        skeleton.putObject("reproducibility").put("reproductionRunCount", 1);
        assertValid(skeleton);
    }

    @Test
    void e2lLocalLabDocs_exist() {
        assertThat(Path.of("evidence/templates/PR-9H-E2L-local-preflight-checklist.md")).exists();
        assertThat(Path.of("evidence/templates/PR-9H-E2L-local-reference-lab-runbook.md")).exists();
        assertThat(Path.of("scripts/local-reference-lab/inventory-gentoo.sh")).exists();
        assertThat(Path.of("scripts/local-reference-lab/summarize-local-jfr.sh")).exists();
    }

    @Test
    void jfrFix1Summaries_parserMetadataRemainNonAuthoritative() throws Exception {
        Path run = Path.of("evidence/reference-local/gentoo-local-reference-lab-v1/20260614-local-jfr-fix-1");
        for (String scenario : List.of("jfr-smoke", "S0", "S1", "S4")) {
            JsonNode jfr = MAPPER.readTree(run.resolve(scenario).resolve("jfr-summary.json").toFile());
            assertThat(jfr.path("labTier").asText()).isEqualTo("LOCAL_REFERENCE_LAB");
            assertThat(jfr.path("w004Eligible").asBoolean()).isFalse();
            assertThat(jfr.path("nonAuthoritative").asBoolean()).isTrue();
            assertThat(jfr.path("sha256").asText()).matches("[a-f0-9]{64}");
            assertThat(jfr.path("fileSizeBytes").asLong()).isGreaterThan(0);
            assertThat(jfr.path("parserTool").asText()).isNotBlank();
            assertThat(jfr.path("storageRef").asText()).startsWith("file:///var/tmp/");
            assertThat(jfr.path("storageRef").asText()).doesNotContain("platform-tracing-perf-harness");
        }
    }

    @Test
    void jfrFinalize1Summaries_parseableDurationBound() throws Exception {
        Path run = Path.of("evidence/reference-local/gentoo-local-reference-lab-v1/20260614-local-jfr-finalize-1");
        for (String scenario : List.of("jfr-smoke", "S0", "S1", "S4")) {
            JsonNode jfr = MAPPER.readTree(run.resolve(scenario).resolve("jfr-summary.json").toFile());
            assertThat(jfr.path("status").asText()).isEqualTo("collected");
            assertThat(jfr.path("parserStatus").asText()).isEqualTo("ok");
            assertThat(jfr.path("finalizationMode").asText()).isEqualTo("durationBound");
            assertThat(jfr.path("w004Eligible").asBoolean()).isFalse();
        }
        JsonNode refS0 = MAPPER.readTree(run.resolve("S0/reference-summary.json").toFile());
        assertThat(refS0.path("labTier").asText()).isEqualTo("LOCAL_REFERENCE_LAB");
        assertThat(refS0.path("w004Eligible").asBoolean()).isFalse();
        assertThat(refS0.path("caveats").path("missingMetrics").size()).isZero();
        assertThat(refS0.path("metrics").path("gc").path("pauseCount").asInt()).isGreaterThan(0);
    }

    @Test
    void gitignore_ignoresRawReferenceLocalArtifacts() throws Exception {
        String gitignore = Files.readString(Path.of("../.gitignore"));
        assertThat(gitignore).contains("evidence/reference-local/**/*.jfr");
    }

    @Test
    void sampleReferenceSummary_validatesAgainstSchema() throws Exception {
        JsonNode sample = MAPPER.readTree(
                Path.of("evidence/reference-examples/sample-reference-summary.json").toFile());
        assertValid(sample);
        assertThat(sample.path("w004Eligible").asBoolean()).isFalse();
        assertThat(sample.path("nonAuthoritative").asBoolean()).isTrue();
        assertThat(sample.path("nonAuthoritativeReasons").isArray()).isTrue();
    }

    @Test
    void w004EligibleTrue_withEmptyMetrics_fails() throws Exception {
        JsonNode invalid = eligibleBaseline();
        ((ObjectNode) invalid).putObject("metrics");
        assertInvalid(invalid, "metrics");
    }

    @Test
    void w004EligibleTrue_withMissingMetricsInCaveats_fails() throws Exception {
        JsonNode invalid = eligibleBaseline();
        ObjectNode caveats = ((ObjectNode) invalid).putObject("caveats");
        caveats.putArray("missingMetrics").add("cpu.per1kRps");
        assertInvalid(invalid);
    }

    @Test
    void w004EligibleTrue_withDroppedIterationsNonZero_fails() throws Exception {
        JsonNode invalid = eligibleBaseline();
        ObjectNode load = (ObjectNode) invalid.path("metrics").path("load");
        load.put("droppedIterations", 1);
        assertInvalid(invalid, "droppedIterations");
    }

    @Test
    void w004EligibleTrue_withAchievedRpsPctBelowThreshold_fails() throws Exception {
        JsonNode invalid = eligibleBaseline();
        ObjectNode load = (ObjectNode) invalid.path("metrics").path("load");
        load.put("achievedRpsPct", 0.90);
        assertInvalid(invalid, "achievedRpsPct");
    }

    @Test
    void w004EligibleTrue_withThrottleRatioAboveFive_fails() throws Exception {
        JsonNode invalid = eligibleBaseline();
        ObjectNode cpu = (ObjectNode) invalid.path("metrics").path("cpu");
        cpu.put("throttleRatioPct", 6.0);
        assertInvalid(invalid, "throttleRatioPct");
    }

    @ParameterizedTest
    @ValueSource(strings = {"jcmd", "unknown", "k6/other"})
    void w004EligibleTrue_withInvalidLatencyOrJfrSource_fails(String badValue) throws Exception {
        if (badValue.startsWith("k6")) {
            JsonNode invalid = eligibleBaseline();
            ((ObjectNode) invalid.path("metrics").path("latency")).put("source", badValue);
            assertInvalid(invalid, "source");
        } else {
            JsonNode invalid = eligibleBaseline();
            ((ObjectNode) invalid.path("environment")).put("jfrStartMode", badValue);
            assertInvalid(invalid, "jfrStartMode");
        }
    }

    @Test
    void w004EligibleTrue_withWrongMemoryMetricSource_fails() throws Exception {
        JsonNode invalid = eligibleBaseline();
        ((ObjectNode) invalid.path("metrics").path("memory"))
                .put("metricSource", "jvm_heap_used");
        assertInvalid(invalid, "metricSource");
    }

    @Test
    void nonAuthoritativeTrue_withoutReasons_fails() throws Exception {
        JsonNode invalid = MAPPER.readTree(
                Path.of("evidence/reference-examples/sample-reference-summary.json").toFile());
        ObjectNode copy = invalid.deepCopy();
        copy.put("nonAuthoritative", true);
        copy.remove("nonAuthoritativeReasons");
        assertInvalid(copy, "nonAuthoritativeReasons");
    }

    @Test
    void w004EligibleTrue_withProvisionalBudgets_fails() throws Exception {
        JsonNode invalid = eligibleBaseline();
        ObjectNode budgets = (ObjectNode) invalid.path("budgets");
        ((ObjectNode) budgets.path("cpuOverheadPct")).put("budgetStatus", "provisional");
        assertInvalid(invalid, "budgetStatus");
    }

    @Test
    void w004EligibleTrue_withSingleRunOnly_fails() throws Exception {
        JsonNode invalid = eligibleBaseline();
        ((ObjectNode) invalid.path("reproducibility")).put("reproductionRunCount", 1);
        assertInvalid(invalid, "reproductionRunCount");
    }

    @Test
    void w004EligibleTrue_withDebugExporterBackpressure_fails() throws Exception {
        JsonNode invalid = eligibleBaseline();
        ((ObjectNode) invalid.path("metrics").path("collector"))
                .put("backpressureEvidenceValid", false);
        assertInvalid(invalid, "backpressureEvidenceValid");
    }

    private static JsonNode eligibleBaseline() throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("evidenceTier", "REFERENCE");
        root.put("nonAuthoritative", false);
        root.put("w004Eligible", true);
        root.put("profileId", "test-profile");
        root.put("runId", "test-run");
        root.put("gitCommit", "abc1234");

        ObjectNode env = root.putObject("environment");
        env.put("clusterAlias", "lab");
        env.put("namespace", "perf");
        env.put("javaVersion", "21");
        env.put("springBootVersion", "3.4.0");
        env.put("otelAgentVersion", "2.10.0");
        env.put("otelExtensionVersion", "1.0.0");
        env.put("collectorImage", "otel/opentelemetry-collector-contrib:0.154.0");
        env.put("collectorVersion", "0.154.0");
        env.put("cgroupVersion", "v2");
        env.put("kernelVersion", "6.1.0");
        env.put("k8sNodeProfile", "perf-pool");
        env.put("jfrSettings", "settings=profile");
        env.put("jfrStartMode", "startup");

        ObjectNode scenario = root.putObject("scenario");
        scenario.put("scenarioId", "S4");
        scenario.put("loadModel", "constant-arrival-rate");
        scenario.put("targetRps", 300);
        scenario.put("duration", "10m");
        scenario.put("warmupDuration", "2m");

        ObjectNode metrics = root.putObject("metrics");
        ObjectNode load = metrics.putObject("load");
        load.put("executor", "constant-arrival-rate");
        load.put("targetRps", 300);
        load.put("achievedRpsAvg", 298.5);
        load.put("achievedRpsPct", 0.995);
        load.put("droppedIterations", 0);
        load.put("warmupSec", 120);
        load.put("steadyWindowSec", 600);

        ObjectNode cpu = metrics.putObject("cpu");
        cpu.put("coresRateAvg", 0.45);
        cpu.put("per1kRps", 1.507);
        cpu.put("throttleRatioPct", 1.2);
        cpu.put("steadyWindowSec", 600);
        cpu.put("promqlQuery", "rate(container_cpu_usage_seconds_total[10m])");

        ObjectNode memory = metrics.putObject("memory");
        memory.put("workingSetBytesAvg", 512_000_000L);
        memory.put("workingSetBytesMax", 520_000_000L);
        memory.put("metricSource", "container_memory_working_set_bytes/cAdvisor");
        memory.put("cgroupVersion", "v2");

        ObjectNode latency = metrics.putObject("latency");
        latency.put("p95Ms", 12.0);
        latency.put("p99Ms", 25.0);
        latency.put("source", "k6/http_req_duration/constant-arrival-rate");
        latency.put("steadyWindowStart", "2026-06-14T10:02:00Z");
        latency.put("steadyWindowEnd", "2026-06-14T10:12:00Z");

        ObjectNode errorRate = metrics.putObject("errorRate");
        errorRate.put("pct", 0.01);

        ObjectNode gc = metrics.putObject("gc");
        gc.put("totalPauseMs", 120.0);
        gc.put("maxSinglePauseMs", 15.0);
        gc.put("gcType", "G1");

        ObjectNode collector = metrics.putObject("collector");
        collector.put("backpressureEvidenceValid", true);
        collector.put("queueSizeMax", 10.0);
        collector.put("queueCapacity", 1000.0);
        collector.put("sendFailedSpans", 0);
        collector.put("droppedSpans", 0);
        collector.put("receiverRefusedSpans", 0);

        ObjectNode artifacts = root.putObject("artifacts");
        artifacts.put("k6Summary", "k6-summary.json");
        artifacts.put("prometheusSnapshot", "prometheus/steady.json");
        artifacts.put("command", "command.txt");
        ObjectNode jfr = artifacts.putObject("jfr");
        jfr.put("storageRef", "s3://lab/jfr/run.jfr");
        jfr.put("sha256", "a".repeat(64));

        ObjectNode classification = root.putObject("classification");
        classification.put("w004Candidate", true);

        ObjectNode budgets = root.putObject("budgets");
        putBudget(budgets, "cpuOverheadPct", 3.0);
        putBudget(budgets, "rssOverheadPct", 10.0);
        putBudget(budgets, "p99LatencyDeltaPct", 15.0);
        putBudget(budgets, "errorRatePct", 0.1);

        ObjectNode repro = root.putObject("reproducibility");
        repro.put("reproductionRunCount", 2);
        ObjectNode variance = repro.putObject("variancePct");
        variance.put("cpu", 2.0);
        variance.put("rss", 3.0);
        variance.put("p99Latency", 4.0);

        ObjectNode caveats = root.putObject("caveats");
        caveats.putArray("missingMetrics");

        return root;
    }

    private static void putBudget(ObjectNode budgets, String name, double threshold) {
        ObjectNode entry = budgets.putObject(name);
        entry.put("threshold", threshold);
        entry.put("budgetStatus", "sre-approved");
    }

    private static void assertValid(JsonNode node) {
        Set<ValidationMessage> errors = REFERENCE_SCHEMA.validate(node);
        assertThat(errors)
                .as("validation errors: %s", formatErrors(errors))
                .isEmpty();
    }

    private static void assertInvalid(JsonNode node, String... expectedFragments) {
        Set<ValidationMessage> errors = REFERENCE_SCHEMA.validate(node);
        assertThat(errors).isNotEmpty();
        if (expectedFragments.length > 0) {
            String joined = formatErrors(errors);
            for (String fragment : expectedFragments) {
                assertThat(joined.toLowerCase()).contains(fragment.toLowerCase());
            }
        }
    }

    private static String formatErrors(Set<ValidationMessage> errors) {
        return errors.stream()
                .map(ValidationMessage::getMessage)
                .collect(Collectors.joining("; "));
    }
}
