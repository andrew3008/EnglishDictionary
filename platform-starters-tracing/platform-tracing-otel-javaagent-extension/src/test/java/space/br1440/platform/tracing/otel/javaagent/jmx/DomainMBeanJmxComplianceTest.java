package space.br1440.platform.tracing.otel.javaagent.jmx;

import static org.assertj.core.api.Assertions.assertThat;
import static space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures.boundSampling;
import static space.br1440.platform.tracing.otel.javaagent.jmx.registration.JmxRegistrationTestFixtures.initializingReadinessProjection;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.CategoryBSamplingAccess;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.processor.ValidatingSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.sampler.CompositeSampler;

class DomainMBeanJmxComplianceTest {

    private static final String BASELINE_RESOURCE = "/jmx-management-contract-baseline.json";
    private static final List<ObjectName> OBJECT_NAMES = List.of(
            DomainObjectNames.SAMPLING,
            DomainObjectNames.SCRUBBING,
            DomainObjectNames.VALIDATION,
            DomainObjectNames.EXPORT,
            DomainObjectNames.PROCESSOR_METRICS,
            DomainObjectNames.DIAGNOSTICS,
            DomainObjectNames.EXTENSION_READINESS
    );

    private final MBeanServer server = MBeanServerFactory.newMBeanServer();
    private final List<AutoCloseable> registrations = new ArrayList<>();
    private PlatformTracingMBeanRegistrar registrar;

    @AfterEach
    void cleanup() {
        for (int index = registrations.size() - 1; index >= 0; index--) {
            try {
                registrations.get(index).close();
            } catch (Exception ignored) {
                // Следующий handle должен быть закрыт даже после локальной ошибки deregistration.
            }
        }
    }

    @Test
    void liveManagementContractMatchesCommittedBaseline() throws Exception {
        // given
        AgentInstanceId agentInstanceId = AgentInstanceId.random();
        registrar = new PlatformTracingMBeanRegistrar(server, agentInstanceId);
        wireRequiredComponents(registrar, samplingPolicy(agentInstanceId));

        // when
        JmxManagementContractSnapshot.Snapshot snapshot = JmxManagementContractSnapshot.capture(server, OBJECT_NAMES);
        String actual = JmxManagementContractSnapshot.toJson(snapshot);
        String baselineOutput = System.getProperty("jmx.contract.baseline.path");

        if (baselineOutput != null) {
            Path output = Path.of(baselineOutput);
            Files.createDirectories(output.getParent());
            Files.writeString(output, actual, StandardCharsets.UTF_8);
            return;
        }

        // then
        String baseline = readBaseline();
        assertThat(normalizeLineEndings(actual))
                .withFailMessage(() -> actionableDiff(normalizeLineEndings(baseline), normalizeLineEndings(actual)))
                .isEqualTo(normalizeLineEndings(baseline));
    }

    @Test
    void platformDomainMBeansExposeNoMutatingOperations() throws Exception {
        // given
        AgentInstanceId agentInstanceId = AgentInstanceId.random();
        registrar = new PlatformTracingMBeanRegistrar(server, agentInstanceId);
        wireRequiredComponents(registrar, samplingPolicy(agentInstanceId));

        // when/then
        List<ObjectName> objectNames = List.of(
                DomainObjectNames.SAMPLING,
                DomainObjectNames.SCRUBBING,
                DomainObjectNames.VALIDATION,
                DomainObjectNames.EXPORT,
                DomainObjectNames.PROCESSOR_METRICS,
                DomainObjectNames.DIAGNOSTICS);
        for (ObjectName name : objectNames) {
            MBeanInfo info = server.getMBeanInfo(name);

            assertThat(info.getOperations())
                    .allMatch(operation -> operation.getImpact() != MBeanOperationInfo.ACTION
                            && operation.getImpact() != MBeanOperationInfo.ACTION_INFO);

            assertThat(info.getAttributes())
                    .allMatch(attribute -> !attribute.isWritable());
        }
    }

    private void wireRequiredComponents(PlatformTracingMBeanRegistrar registrar,
                                        TracingSettingsSource acceptedPolicy) {
        CompositeSampler sampler = new CompositeSampler(
                acceptedPolicy, CategoryBSamplingAccess.snapshot(Collections.emptyList(), Collections.emptyList()));
        ValidatingSpanProcessor validating = new ValidatingSpanProcessor(acceptedPolicy);

        trackRegistration(registrar.registerDiagnosticReadiness(
                initializingReadinessProjection(registrar.agentInstanceId()))
        );
        registrar.setCompositeSampler(sampler);
        registrar.setValidating(validating);
        trackRegistration(registrar.registerRuntimeControls(acceptedPolicy));
    }

    private void trackRegistration(AutoCloseable registration) {
        registrations.add(registration);
    }

    private static String readBaseline() throws IOException {
        try (InputStream stream = DomainMBeanJmxComplianceTest.class.getResourceAsStream(BASELINE_RESOURCE)) {
            if (stream == null) {
                throw new AssertionError("Missing baseline " + BASELINE_RESOURCE
                        + ". Run the module test task with -PupdateJmxContractBaseline.");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
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
                return "JMX management contract changed on line " + (index + 1)
                        + "\nexpected: " + expectedLine
                        + "\nactual:   " + actualLine
                        + "\nTo update the baseline intentionally, run the module test task "
                        + "with -PupdateJmxContractBaseline";
            }
        }
        return "JMX management contract differs.";
    }

    private static TracingSettingsSource samplingPolicy(AgentInstanceId agentInstanceId) {
        return boundSampling(agentInstanceId, true, Map.of(), 0.5);
    }
}
