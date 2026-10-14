package space.br1440.platform.tracing.otel.javaagent.distribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

import org.awaitility.core.ConditionTimeoutException;
import org.awaitility.core.TerminalFailureException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

class ControlledAgentRuntimeTest {

    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration KILL_TIMEOUT = Duration.ofSeconds(10);

    private static final String EXPECTED_PROBE_CAPABILITIES = "7";
    private static final String PROBE_OK_PREFIX = "CONTROLLED_AGENT_PROBE_OK";
    private static final String BOOTSTRAP_POLICY_PROBE_OK_PREFIX = "CONTROLLED_AGENT_BOOTSTRAP_POLICY_OK";
    private static final String VERIFY_BOOTSTRAP_POLICY_ARGUMENT = "verify-bootstrap-policy";
    private static final String REMOTE_JMX_DENIED_OK_PREFIX = ControlledAgentRemoteJmxDenialProbe.OK_PREFIX;
    private static final String ANY_PTA_EVENT_CODE = "\"event_code\":\"PTA-";

    private static final Map<String, String> BOOTSTRAP_POLICY_ENVIRONMENT = Map.of(
            "PLATFORM_TRACING_SAMPLING_ENABLED", "true",
            "PLATFORM_TRACING_SAMPLING_RATIO", "1.0",
            "PLATFORM_TRACING_SAMPLING_ROUTE_RATIOS", "/health=0.0,/orders=0.25",
            "PLATFORM_TRACING_JAVAAGENT_SAMPLING_DROP_PATHS", "/bootstrap-policy-probe",
            "PLATFORM_TRACING_JAVAAGENT_SPAN_ATTRIBUTES_VALIDATION_LEVEL", "ERROR",
            "PLATFORM_TRACING_EXPORTER_ENABLED", "false",
            "PLATFORM_TRACING_PROPAGATION_TRACE_CONTROL_HEADERS_ENABLED", "false",
            "PLATFORM_TRACING_DIAGNOSTICS_LOG_LEVEL", "DEBUG"
    );

    private static final String OTLP_PARTIAL_SUCCESS_PROBE_OK_PREFIX = "OTLP_PARTIAL_SUCCESS_PROBE_OK";
    private static final Pattern OTLP_PARTIAL_SUCCESS_LINE_PATTERN = Pattern.compile(
            OTLP_PARTIAL_SUCCESS_PROBE_OK_PREFIX + " partialSuccessBatches=(\\d+) rejectedSpans=(\\d+) exportedSpans=(\\d+)"
    );
    private static final String SPAN_ATTRIBUTE_VALIDATION_DIAGNOSTIC_OK_PREFIX = "SPAN_ATTRIBUTE_VALIDATION_DIAGNOSTIC_OK";
    private static final Pattern SPAN_ATTRIBUTE_VALIDATION_DIAGNOSTIC_LINE_PATTERN = Pattern.compile(
            SPAN_ATTRIBUTE_VALIDATION_DIAGNOSTIC_OK_PREFIX
                    + " level=(WARNING|ERROR|DISABLED) "
                    + "violationsBefore=(\\d+) violationsAfter=(\\d+) traceId=([0-9a-f]{32}) spanId=([0-9a-f]{16})"
    );
    private static final String VALIDATION_MISSING_ATTRIBUTE_KEY = "platform.validation.missing";
    private static final String VALIDATION_ERROR_DIAGNOSTIC_MARKER = "\"event_code\":\"PTA-VALID-003\"";
    private static final String ERROR_LEVEL_MARKER = "\"level\":\"ERROR\"";
    private static final Duration EXPORT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration EXPORT_POLL_INTERVAL = Duration.ofMillis(100);
    private static final Duration CHILD_READY_POLL_INTERVAL = Duration.ofMillis(100);

    @TempDir
    Path temporaryDirectory;

    @Test
    void controlled_agent_error_validation_remains_diagnostic_on_invalid_span() throws Exception {
        // given
        AgentTestFixture fixture = AgentTestFixture.load();

        try (OtlpTraceCollector collector = OtlpTraceCollector.startAcceptingAllSpans()) {

            // when
            ChildProcessResult result = runSpanAttributeValidationDiagnosticProbe(fixture, collector.otlpEndpoint());

            // then
            assertProcessCompletedSuccessfully(result);
            StimulusSpan stimulus = assertSpanAttributeValidationDiagnosticObserved(result);
            assertInvalidSpanExportedWithViolationMarker(collector, stimulus);
            assertErrorDiagnosticEmitted(result);
        }
    }

    @Test
    void controlled_agent_copies_request_id_from_local_parent_onto_exported_child() throws Exception {
        // given
        AgentTestFixture fixture = AgentTestFixture.load();

        try (OtlpTraceCollector collector = OtlpTraceCollector.startAcceptingAllSpans()) {

            // when
            ChildProcessResult result = runRequestIdPropagationProbe(fixture, collector.otlpEndpoint());

            // then
            assertProcessCompletedSuccessfully(result);
            assertThat(result.stdout()).contains(RequestIdPropagationProbe.OK_PREFIX);
            OtlpTraceCollector.ReceivedSpan child = awaitExportedSpan(collector, RequestIdPropagationProbe.CHILD_SPAN_NAME);
            OtlpTraceCollector.ReceivedSpan parent = awaitExportedSpan(collector, RequestIdPropagationProbe.PARENT_SPAN_NAME);
            assertThat(RequestIdPropagationProbe.REQUEST_ID_KEY)
                    .isEqualTo(PlatformAttributes.PLATFORM_REQUEST_ID);
            assertThat(child.stringAttributes())
                    .containsEntry(RequestIdPropagationProbe.REQUEST_ID_KEY, RequestIdPropagationProbe.REQUEST_ID);
            assertThat(parent.stringAttributes())
                    .containsEntry(RequestIdPropagationProbe.REQUEST_ID_KEY, RequestIdPropagationProbe.REQUEST_ID);
            assertThat(child.traceId()).isEqualTo(parent.traceId());
            assertThat(child.parentSpanId()).isEqualTo(parent.spanId());
        }
    }

    @Test
    void invalid_extension_configuration_fails_bootstrap_without_platform_export()
            throws IOException, InterruptedException, URISyntaxException {
        // given
        AgentTestFixture fixture = AgentTestFixture.load();
        String controlSpanName = "extension-configuration-control-" + UUID.randomUUID();
        String invalidSpanName = "extension-configuration-invalid-" + UUID.randomUUID();
        ChildProcessResult control;
        ChildProcessResult invalid;
        OtlpTraceCollector controlCollector;
        OtlpTraceCollector invalidCollector;

        try (OtlpTraceCollector startedControlCollector = OtlpTraceCollector.startAcceptingAllSpans();
             OtlpTraceCollector startedInvalidCollector = OtlpTraceCollector.startAcceptingAllSpans()) {
            controlCollector = startedControlCollector;
            invalidCollector = startedInvalidCollector;

            // when
            control = runInvalidExtensionConfigurationProbe(
                    fixture, controlCollector.otlpEndpoint(), controlSpanName, false
            );
            invalid = runInvalidExtensionConfigurationProbe(
                    fixture, invalidCollector.otlpEndpoint(), invalidSpanName, true
            );
        }

        // then
        assertProcessCompletedSuccessfully(control);
        assertThat(control.stdout()).contains(InvalidExtensionConfigurationProbe.MAIN_REACHED_MARKER);
        assertExtensionConfigurationProbeReadiness(control, "READY", "", controlSpanName);
        assertThat(controlCollector.receivedSpans())
                .extracting(OtlpTraceCollector.ReceivedSpan::name)
                .contains(controlSpanName);

        assertProcessCompletedSuccessfully(invalid);
        assertThat(invalid.stdout()).contains(InvalidExtensionConfigurationProbe.MAIN_REACHED_MARKER);
        assertExtensionConfigurationProbeReadiness(
                invalid, "FAILED", "CONFIGURATION_INITIALIZATION_FAILED", invalidSpanName
        );
        assertThat(invalidCollector.receivedSpans()).isEmpty();
    }

    @Test
    void invalid_max_span_age_environment_fails_bootstrap_without_platform_export()
            throws IOException, InterruptedException, URISyntaxException {
        // given
        AgentTestFixture fixture = AgentTestFixture.load();
        String spanName = "extension-configuration-max-span-age-" + UUID.randomUUID();

        try (OtlpTraceCollector collector = OtlpTraceCollector.startAcceptingAllSpans()) {
            // when
            ChildProcessResult result = runMaxSpanAgeEnvironmentProbe(fixture, collector.otlpEndpoint(), spanName);

            // then
            assertProcessCompletedSuccessfully(result);
            assertThat(result.stdout()).contains(InvalidExtensionConfigurationProbe.MAIN_REACHED_MARKER);
            assertExtensionConfigurationProbeReadiness(
                    result, "FAILED", "CONFIGURATION_INITIALIZATION_FAILED", spanName
            );
            assertThat(collector.receivedSpans()).isEmpty();
        }
    }

    @Test
    void controlled_agent_starts_with_attested_identity_and_ready_extension() throws IOException, InterruptedException, URISyntaxException {
        // given
        AgentTestFixture fixture = AgentTestFixture.load();

        // when
        ChildProcessResult result = runControlledAgentProbe(fixture);

        // then
        assertProcessCompletedSuccessfully(result);
        assertProbeReportedExpectedIdentity(result, fixture.distributionVersion());
        assertNoBootstrapAttestationLeakedToStdout(result);
        assertStderrIsFreeOfLoggingAndExtensionFailures(result);
    }

    @Test
    void controlled_agent_reads_complete_bootstrap_policy_from_environment_before_spring()
            throws IOException, InterruptedException, URISyntaxException {
        // given
        AgentTestFixture fixture = AgentTestFixture.load();

        // when
        ChildProcessResult result = runBootstrapPolicyProbe(fixture);

        // then
        assertProcessCompletedSuccessfully(result);
        assertProbeReportedExpectedIdentity(result, fixture.distributionVersion());
        assertBootstrapPolicyObserved(result);
        assertStderrIsFreeOfLoggingAndExtensionFailures(result);
    }

    @Test
    void management_agent_argument_fails_readiness_without_registering_mutation()
            throws IOException, InterruptedException, URISyntaxException {
        // given
        AgentTestFixture fixture = AgentTestFixture.load();

        // when
        ChildProcessResult result = runRemoteJmxDenialProbe(fixture);

        // then
        assertProcessCompletedSuccessfully(result);
        assertRemoteJmxDenialObserved(result);
    }

    @Test
    void late_local_management_connector_can_discover_mutation_mbean() throws Exception {
        // given
        AgentTestFixture fixture = AgentTestFixture.load();

        // when
        // then
        assertLateLocalManagementConnectorDiscoversMutationMBean(fixture);
    }

    @Test
    void controlled_agent_reports_otlp_partial_success_via_export_observation() throws Exception {
        // given
        AgentTestFixture fixture = AgentTestFixture.load();

        try (OtlpTraceCollector collector = OtlpTraceCollector.startRejectingOneSpanPerBatch()) {

            // when
            ChildProcessResult result = runOtlpPartialSuccessProbe(fixture, collector.otlpEndpoint());

            // then
            assertProcessCompletedSuccessfully(result);
            assertOtlpPartialSuccessObserved(result);
            assertThat(collector.receivedBatches()).isGreaterThanOrEqualTo(1);
        }
    }

    private ChildProcessResult runInvalidExtensionConfigurationProbe(AgentTestFixture fixture,
                                                                     String otlpEndpoint,
                                                                     String spanName,
                                                                     boolean invalidScanInterval)
            throws IOException, InterruptedException, URISyntaxException {
        Path javaExecutable = javaExecutable();
        Path probeClassPath = probeClassPath(InvalidExtensionConfigurationProbe.class);
        String label = invalidScanInterval ? "extension-configuration-invalid" : "extension-configuration-control";
        Path stdoutFile = temporaryDirectory.resolve(label + ".stdout.log");
        Path stderrFile = temporaryDirectory.resolve(label + ".stderr.log");

        ProcessBuilder processBuilder = new ProcessBuilder(
                invalidExtensionConfigurationChildCommand(
                        javaExecutable,
                        probeClassPath,
                        fixture,
                        otlpEndpoint,
                        spanName,
                        invalidScanInterval
                )
        );
        removeContaminatingEnvironment(processBuilder.environment());
        processBuilder.redirectOutput(stdoutFile.toFile());
        processBuilder.redirectError(stderrFile.toFile());

        Process process = processBuilder.start();
        boolean finished = process.waitFor(WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly().waitFor(KILL_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }

        String stdout = Files.readString(stdoutFile, StandardCharsets.UTF_8);
        String stderr = Files.readString(stderrFile, StandardCharsets.UTF_8);
        Integer exitCode = finished ? process.exitValue() : null;
        return new ChildProcessResult(finished, exitCode, stdout, stderr);
    }

    private ChildProcessResult runMaxSpanAgeEnvironmentProbe(AgentTestFixture fixture,
                                                             String otlpEndpoint,
                                                             String spanName)
            throws IOException, InterruptedException, URISyntaxException {
        Path javaExecutable = javaExecutable();
        Path probeClassPath = probeClassPath(InvalidExtensionConfigurationProbe.class);
        Path stdoutFile = temporaryDirectory.resolve("extension-configuration-max-span-age.stdout.log");
        Path stderrFile = temporaryDirectory.resolve("extension-configuration-max-span-age.stderr.log");

        ProcessBuilder processBuilder = new ProcessBuilder(
                invalidExtensionConfigurationChildCommand(
                        javaExecutable,
                        probeClassPath,
                        fixture,
                        otlpEndpoint,
                        spanName,
                        false
                )
        );
        removeContaminatingEnvironment(processBuilder.environment());
        processBuilder.environment().put("PLATFORM_TRACING_SPAN_TIMEOUT_MAX_SPAN_AGE", "0");
        processBuilder.redirectOutput(stdoutFile.toFile());
        processBuilder.redirectError(stderrFile.toFile());

        Process process = processBuilder.start();
        boolean finished = process.waitFor(WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly().waitFor(KILL_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }

        String stdout = Files.readString(stdoutFile, StandardCharsets.UTF_8);
        String stderr = Files.readString(stderrFile, StandardCharsets.UTF_8);
        Integer exitCode = finished ? process.exitValue() : null;
        return new ChildProcessResult(finished, exitCode, stdout, stderr);
    }

    private ChildProcessResult runControlledAgentProbe(AgentTestFixture fixture)
            throws IOException, InterruptedException, URISyntaxException {
        return runControlledAgentProbe(fixture, false);
    }

    private ChildProcessResult runBootstrapPolicyProbe(AgentTestFixture fixture)
            throws IOException, InterruptedException, URISyntaxException {
        return runControlledAgentProbe(fixture, true);
    }

    private ChildProcessResult runControlledAgentProbe(AgentTestFixture fixture,
                                                       boolean verifyBootstrapPolicy)
            throws IOException, InterruptedException, URISyntaxException {
        Path javaExecutable = javaExecutable();
        Path probeClassPath = probeClassPath(ControlledAgentRuntimeProbe.class);
        String filePrefix = verifyBootstrapPolicy ? "bootstrap-policy-child" : "child";
        Path stdoutFile = temporaryDirectory.resolve(filePrefix + ".stdout.log");
        Path stderrFile = temporaryDirectory.resolve(filePrefix + ".stderr.log");

        List<String> command = childCommand(
                javaExecutable,
                probeClassPath,
                fixture,
                verifyBootstrapPolicy);
        if (verifyBootstrapPolicy) {
            command.add(VERIFY_BOOTSTRAP_POLICY_ARGUMENT);
        }

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        removeContaminatingEnvironment(processBuilder.environment());
        if (verifyBootstrapPolicy) {
            processBuilder.environment().putAll(BOOTSTRAP_POLICY_ENVIRONMENT);
        }
        processBuilder.redirectOutput(stdoutFile.toFile());
        processBuilder.redirectError(stderrFile.toFile());

        Process process = processBuilder.start();
        boolean finished = process.waitFor(WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly().waitFor(KILL_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }

        String stdout = Files.readString(stdoutFile, StandardCharsets.UTF_8);
        String stderr = Files.readString(stderrFile, StandardCharsets.UTF_8);
        Integer exitCode = finished ? process.exitValue() : null;

        return new ChildProcessResult(finished, exitCode, stdout, stderr);
    }

    private ChildProcessResult runRemoteJmxDenialProbe(AgentTestFixture fixture) throws IOException, InterruptedException, URISyntaxException {
        Path javaExecutable = javaExecutable();
        Path probeClassPath = probeClassPath(ControlledAgentRemoteJmxDenialProbe.class);
        Path stdoutFile = temporaryDirectory.resolve("remote-jmx-denial-child.stdout.log");
        Path stderrFile = temporaryDirectory.resolve("remote-jmx-denial-child.stderr.log");

        ProcessBuilder processBuilder = new ProcessBuilder(
                remoteJmxDenialChildCommand(javaExecutable, probeClassPath, fixture)
        );
        removeContaminatingEnvironment(processBuilder.environment());
        processBuilder.redirectOutput(stdoutFile.toFile());
        processBuilder.redirectError(stderrFile.toFile());

        Process process = processBuilder.start();
        boolean finished = process.waitFor(WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly().waitFor(KILL_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }

        String stdout = Files.readString(stdoutFile, StandardCharsets.UTF_8);
        String stderr = Files.readString(stderrFile, StandardCharsets.UTF_8);
        Integer exitCode = finished ? process.exitValue() : null;

        return new ChildProcessResult(finished, exitCode, stdout, stderr);
    }

    private void assertLateLocalManagementConnectorDiscoversMutationMBean(AgentTestFixture fixture)
            throws IOException, InterruptedException, URISyntaxException, AttachNotSupportedException {
        Path javaExecutable = javaExecutable();
        Path probeClassPath = probeClassPath(ControlledAgentLateAttachProbe.class);
        Path stdoutFile = temporaryDirectory.resolve("late-attach-child.stdout.log");
        Path stderrFile = temporaryDirectory.resolve("late-attach-child.stderr.log");

        ProcessBuilder processBuilder = new ProcessBuilder(
                lateAttachChildCommand(javaExecutable, probeClassPath, fixture));
        removeContaminatingEnvironment(processBuilder.environment());
        processBuilder.redirectOutput(stdoutFile.toFile());
        processBuilder.redirectError(stderrFile.toFile());

        Process process = processBuilder.start();
        ChildProcessResult result;
        try {
            long childPid = awaitLateAttachProbePid(process, stdoutFile, stderrFile);
            assertMutationMBeanVisibleThroughLocalManagementConnector(childPid);
        } finally {
            result = stopLateAttachChild(process, stdoutFile, stderrFile);
        }

        assertProcessCompletedSuccessfully(result);
    }

    private static long awaitLateAttachProbePid(Process process, Path stdoutFile, Path stderrFile) throws IOException {
        try {
            return await("late attach probe READY line")
                    .atMost(WAIT_TIMEOUT)
                    .pollInterval(CHILD_READY_POLL_INTERVAL)
                    .failFast("late attach probe exited before reporting READY", () -> !process.isAlive())
                    .until(() -> reportedPid(stdoutFile), Optional::isPresent)
                    .orElseThrow();
        } catch (ConditionTimeoutException | TerminalFailureException failure) {
            throw new AssertionError(
                    "Late attach probe never reported READY; stdout=%s, stderr=%s".formatted(
                            Files.readString(stdoutFile, StandardCharsets.UTF_8),
                            Files.readString(stderrFile, StandardCharsets.UTF_8)),
                    failure
            );
        }
    }

    private static Optional<Long> reportedPid(Path stdoutFile) throws IOException {
        String readyPrefix = ControlledAgentLateAttachProbe.READY_LINE_PREFIX;
        return Files.readString(stdoutFile, StandardCharsets.UTF_8)
                .lines()
                .filter(line -> line.startsWith(readyPrefix))
                .findFirst()
                .map(line -> Long.parseLong(line.substring(readyPrefix.length()).trim()));
    }

    private static void assertMutationMBeanVisibleThroughLocalManagementConnector(long childPid)
            throws AttachNotSupportedException, IOException {
        VirtualMachine virtualMachine = VirtualMachine.attach(Long.toString(childPid));
        String serviceAddress;
        try {
            serviceAddress = virtualMachine.startLocalManagementAgent();
        } finally {
            virtualMachine.detach();
        }

        try (JMXConnector connector = JMXConnectorFactory.connect(new JMXServiceURL(serviceAddress))) {
            assertThat(connector.getMBeanServerConnection()
                    .isRegistered(JmxWireContract.SETTINGS_MUTATION_OBJECT_NAME))
                    .as("a management connector started after startup discovers the mutation MBean")
                    .isTrue();
        }
    }

    private static ChildProcessResult stopLateAttachChild(Process process, Path stdoutFile, Path stderrFile)
            throws IOException, InterruptedException {
        process.getOutputStream().close();
        boolean finished = process.waitFor(KILL_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly().waitFor(KILL_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }

        String stdout = Files.readString(stdoutFile, StandardCharsets.UTF_8);
        String stderr = Files.readString(stderrFile, StandardCharsets.UTF_8);
        Integer exitCode = finished ? process.exitValue() : null;

        return new ChildProcessResult(finished, exitCode, stdout, stderr);
    }

    private ChildProcessResult runSpanAttributeValidationDiagnosticProbe(AgentTestFixture fixture, String otlpEndpoint)
            throws IOException, InterruptedException, URISyntaxException {
        Path javaExecutable = javaExecutable();
        Path probeClassPath = probeClassPath(SpanAttributeValidationDiagnosticProbe.class);
        Path stdoutFile = temporaryDirectory.resolve("validation-diagnostic-child.stdout.log");
        Path stderrFile = temporaryDirectory.resolve("validation-diagnostic-child.stderr.log");

        ProcessBuilder processBuilder = new ProcessBuilder(
                spanAttributeValidationDiagnosticChildCommand(javaExecutable, probeClassPath, fixture, otlpEndpoint)
        );
        removeContaminatingEnvironment(processBuilder.environment());
        processBuilder.redirectOutput(stdoutFile.toFile());
        processBuilder.redirectError(stderrFile.toFile());

        Process process = processBuilder.start();
        boolean finished = process.waitFor(WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly().waitFor(KILL_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }

        String stdout = Files.readString(stdoutFile, StandardCharsets.UTF_8);
        String stderr = Files.readString(stderrFile, StandardCharsets.UTF_8);
        Integer exitCode = finished ? process.exitValue() : null;

        return new ChildProcessResult(finished, exitCode, stdout, stderr);
    }

    private ChildProcessResult runRequestIdPropagationProbe(AgentTestFixture fixture, String otlpEndpoint)
            throws IOException, InterruptedException, URISyntaxException {
        Path javaExecutable = javaExecutable();
        Path probeClassPath = probeClassPath(RequestIdPropagationProbe.class);
        Path stdoutFile = temporaryDirectory.resolve("request-id-propagation-child.stdout.log");
        Path stderrFile = temporaryDirectory.resolve("request-id-propagation-child.stderr.log");

        ProcessBuilder processBuilder = new ProcessBuilder(
                requestIdPropagationChildCommand(javaExecutable, probeClassPath, fixture, otlpEndpoint)
        );
        removeContaminatingEnvironment(processBuilder.environment());
        processBuilder.redirectOutput(stdoutFile.toFile());
        processBuilder.redirectError(stderrFile.toFile());

        Process process = processBuilder.start();
        boolean finished = process.waitFor(WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly().waitFor(KILL_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }

        String stdout = Files.readString(stdoutFile, StandardCharsets.UTF_8);
        String stderr = Files.readString(stderrFile, StandardCharsets.UTF_8);
        Integer exitCode = finished ? process.exitValue() : null;

        return new ChildProcessResult(finished, exitCode, stdout, stderr);
    }

    private ChildProcessResult runOtlpPartialSuccessProbe(AgentTestFixture fixture, String otlpEndpoint)
            throws IOException, InterruptedException, URISyntaxException {
        Path javaExecutable = javaExecutable();
        Path probeClassPath = probeClassPath(OtlpPartialSuccessProbe.class);
        Path stdoutFile = temporaryDirectory.resolve("otlp-child.stdout.log");
        Path stderrFile = temporaryDirectory.resolve("otlp-child.stderr.log");

        ProcessBuilder processBuilder = new ProcessBuilder(
                otlpPartialSuccessChildCommand(javaExecutable, probeClassPath, fixture, otlpEndpoint));
        removeContaminatingEnvironment(processBuilder.environment());
        processBuilder.redirectOutput(stdoutFile.toFile());
        processBuilder.redirectError(stderrFile.toFile());

        Process process = processBuilder.start();
        boolean finished = process.waitFor(WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly().waitFor(KILL_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }

        String stdout = Files.readString(stdoutFile, StandardCharsets.UTF_8);
        String stderr = Files.readString(stderrFile, StandardCharsets.UTF_8);
        Integer exitCode = finished ? process.exitValue() : null;

        return new ChildProcessResult(finished, exitCode, stdout, stderr);
    }

    private void assertExtensionConfigurationProbeReadiness(ChildProcessResult result,
                                                            String lifecycle,
                                                            String failureCode,
                                                            String spanName) {
        String expectedLine = InvalidExtensionConfigurationProbe.RESULT_PREFIX
                + " lifecycle=" + lifecycle
                + " failureCode=" + failureCode
                + " spanName=" + spanName;

        assertThat(result.stdout()
                .lines()
                .filter(line -> line.startsWith(InvalidExtensionConfigurationProbe.RESULT_PREFIX)))
                .as("Child stdout must contain exactly one extension configuration probe line: %s", result.stdout())
                .containsExactly(expectedLine);
    }

    private void assertProcessCompletedSuccessfully(ChildProcessResult result) {
        assertThat(result.finished()).as("Controlled agent child JVM must terminate").isTrue();
        assertThat(result.exitCode())
                .as("Controlled agent child JVM failed; stdout=%s, stderr=%s",
                        result.stdout(), result.stderr())
                .isZero();
    }

    private void assertProbeReportedExpectedIdentity(ChildProcessResult result, String distributionVersion) {
        String expectedLine = PROBE_OK_PREFIX
                + " version=" + distributionVersion
                + " schema=" + JmxWireContract.SCHEMA_ID
                + " capabilities=" + EXPECTED_PROBE_CAPABILITIES;

        assertThat(result.stdout()
                .lines()
                .filter(line -> line.startsWith(PROBE_OK_PREFIX)))
                .containsExactly(expectedLine);
    }

    private void assertBootstrapPolicyObserved(ChildProcessResult result) {
        assertThat(result.stdout()
                .lines()
                .filter(line -> line.startsWith(BOOTSTRAP_POLICY_PROBE_OK_PREFIX)))
                .as("Child stdout must contain exactly one bootstrap policy probe line: %s", result.stdout())
                .containsExactly(BOOTSTRAP_POLICY_PROBE_OK_PREFIX + " sequence=0");
    }

    private void assertRemoteJmxDenialObserved(ChildProcessResult result) {
        String expectedLine = REMOTE_JMX_DENIED_OK_PREFIX
                + " failureCode=" + ControlledAgentRemoteJmxDenialProbe.EXPECTED_FAILURE_CODE
                + " mutationRegistered=false readinessRegistered=true";

        assertThat(result.stdout()
                .lines()
                .filter(line -> line.startsWith(REMOTE_JMX_DENIED_OK_PREFIX)))
                .as("Remote JMX denial probe must report the terminal FAILED snapshot; stdout=%s, stderr=%s",
                        result.stdout(), result.stderr())
                .containsExactly(expectedLine);
    }

    private void assertNoBootstrapAttestationLeakedToStdout(ChildProcessResult result) {
        assertThat(result.stdout()).doesNotContain(ANY_PTA_EVENT_CODE);
    }

    private void assertStderrIsFreeOfLoggingAndExtensionFailures(ChildProcessResult result) {
        assertThat(result.stderr())
                .doesNotContain("No SLF4J providers were found")
                .doesNotContain("Defaulting to no-operation (NOP) logger implementation")
                .doesNotContain("Failed to load extension")
                .doesNotContain("Unable to load extension")
                .doesNotContain("FATAL");
    }

    private StimulusSpan assertSpanAttributeValidationDiagnosticObserved(ChildProcessResult result) {
        List<String> matchingLines = result.stdout()
                .lines()
                .filter(line -> line.startsWith(SPAN_ATTRIBUTE_VALIDATION_DIAGNOSTIC_OK_PREFIX))
                .toList();
        assertThat(matchingLines)
                .as("Child stdout must contain exactly one diagnostic probe line: %s", result.stdout())
                .hasSize(1);

        Matcher matcher = SPAN_ATTRIBUTE_VALIDATION_DIAGNOSTIC_LINE_PATTERN.matcher(matchingLines.getFirst());
        assertThat(matcher.matches())
                .as("Probe line does not match the expected shape: %s", matchingLines.getFirst())
                .isTrue();

        assertThat(matcher.group(1)).isEqualTo("ERROR");
        long violationsBefore = Long.parseLong(matcher.group(2));
        long violationsAfter = Long.parseLong(matcher.group(3));

        assertThat(violationsAfter)
                .as("validationViolationsTotal must grow after the invalid-span stimulus")
                .isGreaterThan(violationsBefore);

        return new StimulusSpan(matcher.group(4), matcher.group(5));
    }

    private void assertInvalidSpanExportedWithViolationMarker(OtlpTraceCollector collector, StimulusSpan stimulus) {
        OtlpTraceCollector.ReceivedSpan exported = awaitExportedSpan(collector, stimulus);

        assertThat(exported.traceId()).isEqualTo(stimulus.traceId());
        assertThat(exported.name()).isEqualTo(SpanAttributeValidationDiagnosticProbe.INVALID_SPAN_NAME);
        assertThat(exported.stringAttributes())
                .as("The exported invalid span must carry the validation marker: %s", exported)
                .containsKey(VALIDATION_MISSING_ATTRIBUTE_KEY);
        assertThat(exported.stringAttributes().get(VALIDATION_MISSING_ATTRIBUTE_KEY))
                .as("The marker must name the producer-owned attribute that was missing")
                .contains("platform.trace.result");
    }

    private static OtlpTraceCollector.ReceivedSpan awaitExportedSpan(OtlpTraceCollector collector, String spanName) {
        try {
            return await("export of span " + spanName)
                    .atMost(EXPORT_TIMEOUT)
                    .pollInterval(EXPORT_POLL_INTERVAL)
                    .until(() -> collector.receivedSpans().stream()
                                    .filter(span -> spanName.equals(span.name()))
                                    .findFirst(),
                            Optional::isPresent)
                    .orElseThrow();
        } catch (ConditionTimeoutException timeout) {
            throw new AssertionError(
                    "The span was never exported: name=%s receivedSpans=%s"
                            .formatted(spanName, collector.receivedSpans()),
                    timeout
            );
        }
    }

    private static OtlpTraceCollector.ReceivedSpan awaitExportedSpan(OtlpTraceCollector collector, StimulusSpan stimulus) {
        try {
            return await("export of span " + stimulus.spanId())
                    .atMost(EXPORT_TIMEOUT)
                    .pollInterval(EXPORT_POLL_INTERVAL)
                    .until(() -> exportedSpan(collector, stimulus), Optional::isPresent)
                    .orElseThrow();
        } catch (ConditionTimeoutException timeout) {
            throw new AssertionError(
                    "The invalid span was never exported: spanId=%s receivedSpans=%s"
                            .formatted(stimulus.spanId(), collector.receivedSpans()),
                    timeout
            );
        }
    }

    private static Optional<OtlpTraceCollector.ReceivedSpan> exportedSpan(OtlpTraceCollector collector,
                                                                          StimulusSpan stimulus) {
        return collector.receivedSpans()
                .stream()
                .filter(span -> span.spanId().equals(stimulus.spanId()))
                .findFirst();
    }

    private void assertErrorDiagnosticEmitted(ChildProcessResult result) {
        assertThat(result.stderr()
                .lines()
                .filter(line -> line.contains(VALIDATION_ERROR_DIAGNOSTIC_MARKER))
                .filter(line -> line.contains(ERROR_LEVEL_MARKER)))
                .as("Agent ERROR level must produce a PTA-VALID-003 diagnostic: %s", result.stderr())
                .hasSize(1);
    }

    private void assertOtlpPartialSuccessObserved(ChildProcessResult result) {
        List<String> matchingLines = result.stdout()
                .lines()
                .filter(line -> line.startsWith(OTLP_PARTIAL_SUCCESS_PROBE_OK_PREFIX))
                .toList();
        assertThat(matchingLines)
                .as("Child stdout must contain exactly one OTLP partial-success probe line: %s", result.stdout())
                .hasSize(1);

        Matcher matcher = OTLP_PARTIAL_SUCCESS_LINE_PATTERN.matcher(matchingLines.getFirst());
        assertThat(matcher.matches())
                .as("Probe line does not match the expected shape: %s", matchingLines.getFirst())
                .isTrue();

        long partialSuccessBatches = Long.parseLong(matcher.group(1));
        long rejectedSpans = Long.parseLong(matcher.group(2));
        long exportedSpans = Long.parseLong(matcher.group(3));

        assertThat(partialSuccessBatches)
                .as("partial_success_batches must be observed at least once")
                .isGreaterThanOrEqualTo(1L);

        assertThat(rejectedSpans)
                .as("rejected_spans must reflect the stub collector's rejection")
                .isGreaterThanOrEqualTo(1L);

        assertThat(exportedSpans)
                .as("exported_spans must count the span accepted from the partial batch")
                .isGreaterThanOrEqualTo(1L);
    }

    private static Path javaExecutable() {
        String javaCommand = isWindows() ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", javaCommand);
    }

    private static Path probeClassPath(Class<?> probeClass) throws URISyntaxException {
        return Path.of(probeClass
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
    }

    private static String openTelemetryApiClasspathEntries() {
        return requiredProperty("platformTracing.otlpProbeApiClasspath");
    }

    private static List<String> invalidExtensionConfigurationChildCommand(Path javaExecutable,
                                                                          Path probeClassPath,
                                                                          AgentTestFixture fixture,
                                                                          String otlpEndpoint,
                                                                          String spanName,
                                                                          boolean invalidScanInterval) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toString());
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Dsun.stdout.encoding=UTF-8");
        command.add("-Dsun.stderr.encoding=UTF-8");
        command.add("-Dotel.traces.exporter=otlp");
        command.add("-Dotel.exporter.otlp.traces.protocol=http/protobuf");
        command.add("-Dotel.exporter.otlp.endpoint=" + otlpEndpoint);
        command.add("-Dotel.exporter.otlp.traces.compression=none");
        command.add("-Dotel.metrics.exporter=none");
        command.add("-Dotel.logs.exporter=none");
        command.add("-Dotel.bsp.schedule.delay=200");
        command.add("-Dplatform.tracing.sampling.enabled=true");
        command.add("-Dplatform.tracing.sampling.ratio=1.0");
        command.add("-Dplatform.tracing.service.name=controlled-agent-runtime-test");
        command.add("-Dplatform.tracing.service.version=1.0.0");
        command.add("-Dplatform.tracing.service.environment=test");
        command.add("-Dplatform.tracing.resource.validation-mode=ERROR");
        if (invalidScanInterval) {
            command.add("-Dplatform.tracing.span-timeout.scan-interval=0");
        }
        command.add("-Dplatform.tracing.controlled-agent.expected-sha256=" + fixture.expectedControlledAgentSha());
        command.add("-javaagent:" + fixture.controlledAgent());
        command.add("-cp");
        command.add(probeClassPath + File.pathSeparator + openTelemetryApiClasspathEntries());
        command.add(InvalidExtensionConfigurationProbe.class.getName());
        command.add(spanName);
        return command;
    }

    private static List<String> childCommand(Path javaExecutable,
                                             Path probeClassPath,
                                             AgentTestFixture fixture,
                                             boolean includeApiClasspath) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toString());
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Dsun.stdout.encoding=UTF-8");
        command.add("-Dsun.stderr.encoding=UTF-8");
        command.add("-Dotel.traces.exporter=logging");
        command.add("-Dotel.metrics.exporter=none");
        command.add("-Dotel.logs.exporter=none");
        command.add("-Dplatform.tracing.service.name=controlled-agent-runtime-test");
        command.add("-Dplatform.tracing.service.version=1.0.0");
        command.add("-Dplatform.tracing.service.environment=test");
        command.add("-Dplatform.tracing.resource.validation-mode=ERROR");
        command.add("-Dplatform.tracing.controlled-agent.expected-sha256=" + fixture.expectedControlledAgentSha());
        String apiClasspath = null;
        if (includeApiClasspath) {
            apiClasspath = openTelemetryApiClasspathEntries();
            command.add("-D" + ControlledAgentRuntimeProbe.EXPECTED_API_CLASSPATH_PROPERTY + "=" + apiClasspath);
        }
        command.add("-javaagent:" + fixture.controlledAgent());
        command.add("-cp");
        command.add(includeApiClasspath
                ? probeClassPath + File.pathSeparator + apiClasspath
                : probeClassPath.toString());
        command.add(ControlledAgentRuntimeProbe.class.getName());
        command.add(fixture.distributionVersion());
        command.add(fixture.controlledAgent().toString());
        command.add(fixture.expectedOtelJavaAgentSha());
        command.add(fixture.expectedExtensionSha());
        return command;
    }

    private static List<String> remoteJmxDenialChildCommand(Path javaExecutable,
                                                            Path probeClassPath,
                                                            AgentTestFixture fixture) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toString());
        command.add("-Dcom.sun.management.jmxremote.authenticate=false");
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Dsun.stdout.encoding=UTF-8");
        command.add("-Dsun.stderr.encoding=UTF-8");
        command.add("-Dotel.traces.exporter=logging");
        command.add("-Dotel.metrics.exporter=none");
        command.add("-Dotel.logs.exporter=none");
        command.add("-Dplatform.tracing.service.name=controlled-agent-runtime-test");
        command.add("-Dplatform.tracing.service.version=1.0.0");
        command.add("-Dplatform.tracing.service.environment=test");
        command.add("-Dplatform.tracing.resource.validation-mode=ERROR");
        command.add("-Dplatform.tracing.controlled-agent.expected-sha256=" + fixture.expectedControlledAgentSha());
        command.add("-javaagent:" + fixture.controlledAgent());
        command.add("-cp");
        command.add(probeClassPath.toString());
        command.add(ControlledAgentRemoteJmxDenialProbe.class.getName());
        return command;
    }

    private static List<String> lateAttachChildCommand(Path javaExecutable,
                                                       Path probeClassPath,
                                                       AgentTestFixture fixture) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toString());
        command.add("-Djava.rmi.server.hostname=127.0.0.1");
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Dsun.stdout.encoding=UTF-8");
        command.add("-Dsun.stderr.encoding=UTF-8");
        command.add("-Dotel.traces.exporter=logging");
        command.add("-Dotel.metrics.exporter=none");
        command.add("-Dotel.logs.exporter=none");
        command.add("-Dplatform.tracing.service.name=controlled-agent-runtime-test");
        command.add("-Dplatform.tracing.service.version=1.0.0");
        command.add("-Dplatform.tracing.service.environment=test");
        command.add("-Dplatform.tracing.resource.validation-mode=ERROR");
        command.add("-Dplatform.tracing.controlled-agent.expected-sha256=" + fixture.expectedControlledAgentSha());
        command.add("-javaagent:" + fixture.controlledAgent());
        command.add("-cp");
        command.add(probeClassPath.toString());
        command.add(ControlledAgentLateAttachProbe.class.getName());
        return command;
    }

    private static List<String> spanAttributeValidationDiagnosticChildCommand(Path javaExecutable,
                                                                              Path probeClassPath,
                                                                              AgentTestFixture fixture,
                                                                              String otlpEndpoint) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toString());
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Dsun.stdout.encoding=UTF-8");
        command.add("-Dsun.stderr.encoding=UTF-8");
        command.add("-Dotel.traces.exporter=otlp");
        command.add("-Dotel.exporter.otlp.traces.protocol=http/protobuf");
        command.add("-Dotel.exporter.otlp.endpoint=" + otlpEndpoint);
        command.add("-Dotel.exporter.otlp.traces.compression=none");
        command.add("-Dotel.metrics.exporter=none");
        command.add("-Dotel.logs.exporter=none");
        command.add("-Dotel.bsp.schedule.delay=200");
        command.add("-Dplatform.tracing.sampling.enabled=true");
        command.add("-Dplatform.tracing.sampling.ratio=1.0");
        command.add("-Dplatform.tracing.javaagent.span-attributes.validation.level=ERROR");
        command.add("-Dplatform.tracing.service.name=controlled-agent-runtime-test");
        command.add("-Dplatform.tracing.service.version=1.0.0");
        command.add("-Dplatform.tracing.service.environment=test");
        command.add("-Dplatform.tracing.resource.validation-mode=ERROR");
        command.add("-Dplatform.tracing.controlled-agent.expected-sha256=" + fixture.expectedControlledAgentSha());
        command.add("-javaagent:" + fixture.controlledAgent());
        command.add("-cp");
        command.add(probeClassPath + File.pathSeparator + openTelemetryApiClasspathEntries());
        command.add(SpanAttributeValidationDiagnosticProbe.class.getName());
        return command;
    }

    private static List<String> requestIdPropagationChildCommand(Path javaExecutable,
                                                                 Path probeClassPath,
                                                                 AgentTestFixture fixture,
                                                                 String otlpEndpoint) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toString());
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Dsun.stdout.encoding=UTF-8");
        command.add("-Dsun.stderr.encoding=UTF-8");
        command.add("-Dotel.traces.exporter=otlp");
        command.add("-Dotel.exporter.otlp.traces.protocol=http/protobuf");
        command.add("-Dotel.exporter.otlp.endpoint=" + otlpEndpoint);
        command.add("-Dotel.exporter.otlp.traces.compression=none");
        command.add("-Dotel.metrics.exporter=none");
        command.add("-Dotel.logs.exporter=none");
        command.add("-Dotel.bsp.schedule.delay=200");
        command.add("-Dplatform.tracing.sampling.enabled=true");
        command.add("-Dplatform.tracing.sampling.ratio=1.0");
        command.add("-Dplatform.tracing.service.name=controlled-agent-runtime-test");
        command.add("-Dplatform.tracing.service.version=1.0.0");
        command.add("-Dplatform.tracing.service.environment=test");
        command.add("-Dplatform.tracing.resource.validation-mode=ERROR");
        command.add("-Dplatform.tracing.controlled-agent.expected-sha256=" + fixture.expectedControlledAgentSha());
        command.add("-javaagent:" + fixture.controlledAgent());
        command.add("-cp");
        command.add(probeClassPath + File.pathSeparator + openTelemetryApiClasspathEntries());
        command.add(RequestIdPropagationProbe.class.getName());
        return command;
    }

    private static List<String> otlpPartialSuccessChildCommand(Path javaExecutable,
                                                               Path probeClassPath,
                                                               AgentTestFixture fixture,
                                                               String otlpEndpoint) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toString());
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Dsun.stdout.encoding=UTF-8");
        command.add("-Dsun.stderr.encoding=UTF-8");
        command.add("-Dotel.traces.exporter=otlp");
        command.add("-Dotel.exporter.otlp.traces.protocol=http/protobuf");
        command.add("-Dotel.exporter.otlp.endpoint=" + otlpEndpoint);
        command.add("-Dotel.exporter.otlp.traces.compression=none");
        command.add("-Dotel.metrics.exporter=none");
        command.add("-Dotel.logs.exporter=none");
        command.add("-Dotel.bsp.schedule.delay=200");
        command.add("-Dplatform.tracing.service.name=controlled-agent-runtime-test");
        command.add("-Dplatform.tracing.service.version=1.0.0");
        command.add("-Dplatform.tracing.service.environment=test");
        command.add("-Dplatform.tracing.resource.validation-mode=ERROR");
        command.add("-Dplatform.tracing.controlled-agent.expected-sha256=" + fixture.expectedControlledAgentSha());
        command.add("-javaagent:" + fixture.controlledAgent());
        command.add("-cp");
        command.add(probeClassPath + File.pathSeparator + openTelemetryApiClasspathEntries());
        command.add(OtlpPartialSuccessProbe.class.getName());
        return command;
    }

    private static void removeContaminatingEnvironment(Map<String, String> environment) {
        environment.keySet().removeIf(name -> {
            String normalized = name.toUpperCase(Locale.ROOT);
            return normalized.equals("JAVA_TOOL_OPTIONS")
                    || normalized.equals("_JAVA_OPTIONS")
                    || normalized.equals("JDK_JAVA_OPTIONS")
                    || normalized.equals("CLASSPATH")
                    || normalized.startsWith("OTEL_")
                    || normalized.startsWith("PLATFORM_TRACING_");
        });
    }

    private static Path requiredPath(String name) {
        Path path = Path.of(requiredProperty(name));
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Required test file is missing: " + path);
        }

        return path;
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required test system property is missing: " + name);
        }

        return value;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name")
                .toLowerCase(Locale.ROOT)
                .contains("win");
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format(Locale.ROOT, "%02x", value & 0xFF));
            }

            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private record AgentTestFixture(Path controlledAgent,
                                    Path extension,
                                    String distributionVersion,
                                    String expectedOtelJavaAgentSha,
                                    String expectedExtensionSha,
                                    String expectedControlledAgentSha) {

        static AgentTestFixture load() throws IOException {
            Path controlledAgent = requiredPath("platformTracing.controlledAgentJar");
            Path extension = requiredPath("platformTracing.extensionJar");
            String distributionVersion = requiredProperty("platformTracing.distributionVersion");
            String expectedOtelJavaAgentSha = sha256Hex(Files.readAllBytes(requiredPath("platformTracing.otelJavaAgentJar")));
            String expectedExtensionSha = sha256Hex(Files.readAllBytes(extension));
            String expectedControlledAgentSha = sha256Hex(Files.readAllBytes(controlledAgent));
            return new AgentTestFixture(
                    controlledAgent, extension, distributionVersion,
                    expectedOtelJavaAgentSha, expectedExtensionSha, expectedControlledAgentSha
            );
        }
    }

    private record ChildProcessResult(boolean finished, Integer exitCode, String stdout, String stderr) {
    }

    private record StimulusSpan(String traceId, String spanId) {
    }
}
