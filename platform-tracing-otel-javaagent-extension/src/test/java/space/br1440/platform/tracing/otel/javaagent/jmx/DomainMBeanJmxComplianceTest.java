package space.br1440.platform.tracing.otel.javaagent.jmx;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.state.AcceptedPolicyAccess;
import space.br1440.platform.tracing.otel.javaagent.processor.ValidatingSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.sampler.CompositeSampler;

import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
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
 * Защищает live-контракт production read-only MBean от неявного изменения.
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
        wireRequiredComponents(registrar, holderWith(0.5));

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

        assertThat(normalizeLineEndings(actual))
                .withFailMessage(() -> actionableDiff(normalizeLineEndings(baseline), normalizeLineEndings(actual)))
                .isEqualTo(normalizeLineEndings(baseline));
    }

    @Test
    void platformDomainMBeansExposeNoMutatingOperations() throws Exception {
        registrar = new PlatformTracingJmxRegistrar();
        wireRequiredComponents(registrar, holderWith(0.5));

        for (ObjectName name : List.of(
                PlatformTracingObjectNames.SAMPLING,
                PlatformTracingObjectNames.SCRUBBING,
                PlatformTracingObjectNames.VALIDATION,
                PlatformTracingObjectNames.EXPORT,
                PlatformTracingObjectNames.PROCESSOR_METRICS,
                PlatformTracingObjectNames.DIAGNOSTICS)) {
            MBeanInfo info = server.getMBeanInfo(name);
            assertThat(info.getOperations())
                    .allMatch(operation -> operation.getImpact() != MBeanOperationInfo.ACTION
                            && operation.getImpact() != MBeanOperationInfo.ACTION_INFO);
            assertThat(info.getAttributes())
                    .allMatch(attribute -> !attribute.isWritable());
        }
    }

    @Test
    void legacyControlProtocolMBeanNotRegisteredOnPlatformServer() {
        assertThat(server.isRegistered(PlatformTracingObjectNames.CONTROL_PROTOCOL)).isFalse();
    }

    private static void wireRequiredComponents(
            PlatformTracingJmxRegistrar registrar,
            AcceptedPolicyAccess acceptedPolicy) {
        CompositeSampler sampler = new CompositeSampler(acceptedPolicy);
        ValidatingSpanProcessor validating = new ValidatingSpanProcessor(acceptedPolicy);

        registrar.setCompositeSampler(sampler);
        registrar.setValidating(validating);
        registrar.setAcceptedPolicy(acceptedPolicy);
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

    private static String normalizeLineEndings(String text) {
        return text.replace("\r\n", "\n");
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

    private static AcceptedPolicyAccess holderWith(double ratio) {
        return AcceptedPolicyAccess.isolatedSampling(
                true, Collections.emptyList(), Collections.emptyList(), Map.of(), ratio);
    }

    private record Metadata(String generatedFromCommit, String javaVersion) {
    }
}
