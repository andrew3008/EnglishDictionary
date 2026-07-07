package space.br1440.platform.tracing.bench.baseline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class JmhBaselineComparator {

    public static final double WARN_LATENCY_PCT = 10.0;
    public static final double FAIL_LATENCY_PCT = 25.0;
    public static final double WARN_ALLOC_PCT = 5.0;
    public static final double FAIL_ALLOC_PCT = 15.0;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JmhBaselineComparator() {
    }

    public static JmhBaselineCompareReport compare(Path baselineFile, Path currentFile, String profileId)
            throws IOException {
        JsonNode baselineRoot = readArray(baselineFile);
        JsonNode currentRoot = readArray(currentFile);

        Map<String, JsonNode> baselineByKey = indexByKey(baselineRoot);

        JmhBaselineCompareReport.Builder report = JmhBaselineCompareReport.builder(profileId)
                .baselineEntries(baselineRoot.size())
                .currentEntries(currentRoot.size());

        int matched = 0;
        int newEntries = 0;
        int hardWarnings = 0;
        int hardFailures = 0;
        int diagnosticWarnings = 0;
        int diagnosticFailures = 0;

        for (JsonNode entry : currentRoot) {
            String key = JmhResultKey.canonicalKey(entry);
            JsonNode base = baselineByKey.get(key);
            if (base == null) {
                newEntries++;
                report.addLine(String.format(
                        "NEW       %s (%s) — not in baseline, skipped",
                        text(entry, "benchmark"),
                        text(entry, "mode")));
                continue;
            }

            matched++;
            String mode = text(entry, "mode");
            String benchmark = text(entry, "benchmark");
            double latencyDelta = comparePct(
                    score(base, "primaryMetric"),
                    score(entry, "primaryMetric"));
            String line = String.format(
                    "%-90s %-8s latency %+.1f%%",
                    benchmark,
                    mode,
                    latencyDelta);

            Double allocDelta = compareAlloc(base, entry);
            if (allocDelta != null) {
                line += String.format(" alloc %+.1f%%", allocDelta);
            }
            report.addLine(line);

            JmhBenchmarkGatePolicy.GateClass latencyGate = JmhBenchmarkGatePolicy.gateClassFor(mode, benchmark);
            if (latencyDelta > FAIL_LATENCY_PCT) {
                recordLatencyFailure(report, latencyGate, entry, mode, latencyDelta);
                if (latencyGate == JmhBenchmarkGatePolicy.GateClass.HARD) {
                    hardFailures++;
                } else {
                    diagnosticFailures++;
                }
            } else if (latencyDelta > WARN_LATENCY_PCT) {
                recordLatencyWarning(report, latencyGate, entry, mode, latencyDelta);
                if (latencyGate == JmhBenchmarkGatePolicy.GateClass.HARD) {
                    hardWarnings++;
                } else {
                    diagnosticWarnings++;
                }
            }

            if (allocDelta != null) {
                boolean diagnosticAlloc = JmhBenchmarkGatePolicy.isDiagnosticBenchmark(benchmark);
                if (allocDelta > FAIL_ALLOC_PCT) {
                    if (diagnosticAlloc) {
                        diagnosticFailures++;
                        report.addDiagnosticFailure(formatAllocFailure(entry, mode, allocDelta, FAIL_ALLOC_PCT));
                    } else {
                        hardFailures++;
                        report.addHardFailure(formatAllocFailure(entry, mode, allocDelta, FAIL_ALLOC_PCT));
                    }
                } else if (allocDelta > WARN_ALLOC_PCT) {
                    if (diagnosticAlloc) {
                        diagnosticWarnings++;
                        report.addDiagnosticWarning(formatAllocWarning(entry, mode, allocDelta, WARN_ALLOC_PCT));
                    } else {
                        hardWarnings++;
                        report.addHardWarning(formatAllocWarning(entry, mode, allocDelta, WARN_ALLOC_PCT));
                    }
                }
            }
        }

        return report.matched(matched)
                .newEntries(newEntries)
                .hardWarnings(hardWarnings)
                .hardFailures(hardFailures)
                .diagnosticWarnings(diagnosticWarnings)
                .diagnosticFailures(diagnosticFailures)
                .build();
    }

    static Map<String, JsonNode> indexByKey(JsonNode entries) {
        Map<String, JsonNode> indexed = new HashMap<>(entries.size() * 2);
        for (JsonNode entry : entries) {
            indexed.put(JmhResultKey.canonicalKey(entry), entry);
        }
        return indexed;
    }

    private static void recordLatencyFailure(
            JmhBaselineCompareReport.Builder report,
            JmhBenchmarkGatePolicy.GateClass gate,
            JsonNode entry,
            String mode,
            double latencyDelta) {
        String message = formatLatencyFailure(entry, mode, latencyDelta, FAIL_LATENCY_PCT);
        if (gate == JmhBenchmarkGatePolicy.GateClass.HARD) {
            report.addHardFailure(message);
        } else {
            report.addDiagnosticFailure(message);
        }
    }

    private static void recordLatencyWarning(
            JmhBaselineCompareReport.Builder report,
            JmhBenchmarkGatePolicy.GateClass gate,
            JsonNode entry,
            String mode,
            double latencyDelta) {
        String message = formatLatencyWarning(entry, mode, latencyDelta, WARN_LATENCY_PCT);
        if (gate == JmhBenchmarkGatePolicy.GateClass.HARD) {
            report.addHardWarning(message);
        } else {
            report.addDiagnosticWarning(message);
        }
    }

    private static String formatLatencyFailure(
            JsonNode entry, String mode, double delta, double threshold) {
        return String.format(
                "%s (%s): latency %+.1f%% > %.0f%%",
                text(entry, "benchmark"),
                mode,
                delta,
                threshold);
    }

    private static String formatLatencyWarning(
            JsonNode entry, String mode, double delta, double threshold) {
        return String.format(
                "%s (%s): latency %+.1f%% > %.0f%%",
                text(entry, "benchmark"),
                mode,
                delta,
                threshold);
    }

    private static String formatAllocFailure(
            JsonNode entry, String mode, double delta, double threshold) {
        return String.format(
                "%s (%s): alloc %+.1f%% > %.0f%%",
                text(entry, "benchmark"),
                mode,
                delta,
                threshold);
    }

    private static String formatAllocWarning(
            JsonNode entry, String mode, double delta, double threshold) {
        return String.format(
                "%s (%s): alloc %+.1f%% > %.0f%%",
                text(entry, "benchmark"),
                mode,
                delta,
                threshold);
    }

    private static JsonNode readArray(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            JsonNode root = MAPPER.readTree(input);
            if (!root.isArray()) {
                throw new IllegalArgumentException("Expected JSON array in " + file);
            }
            return root;
        }
    }

    private static String text(JsonNode entry, String field) {
        JsonNode value = entry.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    private static double score(JsonNode entry, String metricField) {
        JsonNode metric = entry.get(metricField);
        if (metric == null || metric.isNull()) {
            return 0.0d;
        }
        return metric.path("score").asDouble(0.0d);
    }

    private static Double compareAlloc(JsonNode base, JsonNode current) {
        Double baseAlloc = allocScore(base);
        Double currentAlloc = allocScore(current);
        if (baseAlloc == null || currentAlloc == null) {
            return null;
        }
        return comparePct(baseAlloc, currentAlloc);
    }

    private static Double allocScore(JsonNode entry) {
        JsonNode secondary = entry.get("secondaryMetrics");
        if (secondary == null || secondary.isNull()) {
            return null;
        }
        JsonNode dotted = secondary.get("\u00b7gc.alloc.rate.norm");
        if (dotted != null && !dotted.isNull()) {
            return dotted.path("score").asDouble();
        }
        JsonNode plain = secondary.get("gc.alloc.rate.norm");
        if (plain != null && !plain.isNull()) {
            return plain.path("score").asDouble();
        }
        Iterator<String> names = secondary.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (name.endsWith("gc.alloc.rate.norm")) {
                return secondary.get(name).path("score").asDouble();
            }
        }
        return null;
    }

    static double comparePct(double baseScore, double currentScore) {
        if (baseScore == 0.0d) {
            return 0.0d;
        }
        return ((currentScore - baseScore) / baseScore) * 100.0d;
    }
}
