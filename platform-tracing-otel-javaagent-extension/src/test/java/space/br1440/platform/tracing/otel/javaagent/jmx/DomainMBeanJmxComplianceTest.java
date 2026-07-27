package space.br1440.platform.tracing.otel.javaagent.jmx;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.javaagent.sampler.SamplerStateHolder;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Защищает полный live-контракт восьми production MBean от неявного изменения.
 */
class DomainMBeanJmxComplianceTest {

    private static final String BASELINE_RESOURCE = "/jmx-management-contract-baseline.json";
    private static final List<ObjectName> OBJECT_NAMES = List.of(
            PlatformTracingObjectNames.SAMPLING,
            PlatformTracingObjectNames.SCRUBBING,
            PlatformTracingObjectNames.VALIDATION,
            PlatformTracingObjectNames.EXPORT,
            PlatformTracingObjectNames.PROCESSOR_METRICS,
            PlatformTracingObjectNames.DIAGNOSTICS,
            PlatformTracingObjectNames.CONTROL_PROTOCOL,
            PlatformTracingObjectNames.EXTENSION_READINESS);

    private final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    private PlatformTracingJmxRegistrar registrar;

    @AfterEach
    void cleanup() {
        if (registrar != null) {
            registrar.unregisterAllMBeans();
        }
    }

    @Test
    void liveManagementContractMatchesCommittedBaseline() throws Exception {
        registrar = new PlatformTracingJmxRegistrar();
        registrar.setConfigHolder(holderWith(0.5));

        JmxManagementContractSnapshot.Snapshot snapshot =
                JmxManagementContractSnapshot.capture(server, OBJECT_NAMES);
        String baseline = readBaseline();
        Metadata metadata = metadata(baseline);
        String actual = JmxManagementContractSnapshot.toJson(
                snapshot,
                metadata.generatedFromCommit(),
                metadata.javaVersion());

        if (Boolean.getBoolean("jmx.contract.baseline.update")) {
            Path output = Path.of(requiredProperty("jmx.contract.baseline.path"));
            Files.createDirectories(output.getParent());
            String updated = JmxManagementContractSnapshot.toJson(
                    snapshot,
                    requiredProperty("jmx.contract.baseline.commit"),
                    System.getProperty("java.version"));
            Files.writeString(output, updated, StandardCharsets.UTF_8);
            return;
        }

        assertThat(actual)
                .withFailMessage(() -> actionableDiff(baseline, actual))
                .isEqualTo(baseline);
    }

    private static String readBaseline() throws IOException {
        try (var stream = DomainMBeanJmxComplianceTest.class.getResourceAsStream(BASELINE_RESOURCE)) {
            if (stream == null) {
                if (Boolean.getBoolean("jmx.contract.baseline.update")) {
                    return """
                            {
                              "generatedFromCommit": "UPDATE",
                              "javaVersion": "UPDATE"
                            }
                            """;
                }
                throw new AssertionError("Отсутствует baseline " + BASELINE_RESOURCE
                        + ". Запустите ./gradlew jmxManagementContractVerify "
                        + "-PupdateJmxContractBaseline.");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Metadata metadata(String baseline) {
        return new Metadata(
                jsonString(baseline, "generatedFromCommit"),
                jsonString(baseline, "javaVersion"));
    }

    private static String jsonString(String json, String property) {
        String marker = "\"" + property + "\": \"";
        int start = json.indexOf(marker);
        if (start < 0) {
            return "UNKNOWN";
        }
        int valueStart = start + marker.length();
        int end = json.indexOf('"', valueStart);
        return end < 0 ? "UNKNOWN" : json.substring(valueStart, end);
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Не задано обязательное system property: " + name);
        }
        return value;
    }

    private static String actionableDiff(String expected, String actual) {
        List<String> expectedLines = expected.lines().toList();
        List<String> actualLines = actual.lines().toList();
        int max = Math.max(expectedLines.size(), actualLines.size());
        for (int index = 0; index < max; index++) {
            String expectedLine = index < expectedLines.size() ? expectedLines.get(index) : "<EOF>";
            String actualLine = index < actualLines.size() ? actualLines.get(index) : "<EOF>";
            if (!expectedLine.equals(actualLine)) {
                return "JMX management contract изменился на строке " + (index + 1)
                        + "\nexpected: " + expectedLine
                        + "\nactual:   " + actualLine
                        + "\nДля намеренного обновления выполните "
                        + "./gradlew jmxManagementContractVerify -PupdateJmxContractBaseline";
            }
        }
        return "JMX management contract отличается.";
    }

    private static SamplerStateHolder holderWith(double ratio) {
        return new SamplerStateHolder(
                true, Collections.emptyList(), Collections.emptyList(), Map.of(), ratio);
    }

    private record Metadata(String generatedFromCommit, String javaVersion) {
    }
}
