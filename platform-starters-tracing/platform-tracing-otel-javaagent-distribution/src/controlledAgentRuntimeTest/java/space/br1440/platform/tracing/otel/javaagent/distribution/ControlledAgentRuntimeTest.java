package space.br1440.platform.tracing.otel.javaagent.distribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ControlledAgentRuntimeTest {

    private static final String ACP_PIN_RESOURCE = "META-INF/platform-tracing/upstream-acp-pin.properties";

    @TempDir
    Path temporaryDirectory;

    @Test
    void controlled_agent_starts_with_exact_attestation_and_ready_extension()
            throws IOException, InterruptedException, URISyntaxException {
        // given
        Path controlledAgent = requiredPath("platformTracing.controlledAgentJar");
        Path extension = requiredPath("platformTracing.extensionJar");
        String distributionVersion = requiredProperty("platformTracing.distributionVersion");
        Properties pin = loadProperties(readEntry(extension, ACP_PIN_RESOURCE));
        List<String> reviewedProviders = readContiguousProviders(pin);
        String expectedPinSha = sha256Hex(providerPreimage(reviewedProviders));
        String expectedUpstreamSha = pin.getProperty("upstream.sha256");
        String expectedPinFormat = pin.getProperty("format.version");
        String expectedExtensionSha = sha256Hex(Files.readAllBytes(extension));
        Path javaExecutable = Path.of(
                System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
        Path probeClassPath = Path.of(ControlledAgentRuntimeProbe.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
        Path stdoutFile = temporaryDirectory.resolve("child.stdout.log");
        Path stderrFile = temporaryDirectory.resolve("child.stderr.log");
        ProcessBuilder processBuilder = new ProcessBuilder(childCommand(
                javaExecutable,
                controlledAgent,
                probeClassPath,
                distributionVersion,
                expectedUpstreamSha,
                expectedExtensionSha));
        removeContaminatingEnvironment(processBuilder.environment());
        processBuilder.redirectOutput(stdoutFile.toFile());
        processBuilder.redirectError(stderrFile.toFile());

        // when
        Process process = processBuilder.start();
        boolean finished = process.waitFor(45, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
        String stdout = Files.readString(stdoutFile, StandardCharsets.UTF_8);
        String stderr = Files.readString(stderrFile, StandardCharsets.UTF_8);
        Integer exitCode = finished ? process.exitValue() : null;
        List<String> evidenceMarkers = stderr.lines()
                .filter(line -> line.contains("\"event_code\":\"PTA-BOOT-010\""))
                .toList();

        // then
        assertThat(finished).as("Controlled agent child JVM must terminate").isTrue();
        assertThat(exitCode)
                .as("Controlled agent child JVM failed; stdout=%s, stderr=%s", stdout, stderr)
                .isZero();
        assertThat(stdout.lines().filter(line -> line.startsWith("CONTROLLED_AGENT_PROBE_OK")))
                .containsExactly("CONTROLLED_AGENT_PROBE_OK version=" + distributionVersion
                        + " profile=platform-agent-secure-v1 capabilities=7");
        assertThat(stdout).doesNotContain("\"event_code\":\"PTA-");
        assertThat(evidenceMarkers).hasSize(1);
        assertThat(evidenceMarkers.getFirst())
                .contains("\"pin_sha256\":\"" + expectedPinSha + "\"")
                .contains("\"set_sha256\":\"" + expectedPinSha + "\"")
                .contains("\"upstream_agent_sha256\":\"" + expectedUpstreamSha + "\"")
                .contains("\"pin_count\":\"" + reviewedProviders.size() + "\"")
                .contains("\"pin_format_version\":\"" + expectedPinFormat + "\"");
        assertThat(stderr)
                .doesNotContain("No SLF4J providers were found")
                .doesNotContain("Defaulting to no-operation (NOP) logger implementation")
                .doesNotContain("Failed to load extension")
                .doesNotContain("Unable to load extension")
                .doesNotContain("FATAL");
    }

    private static List<String> childCommand(
            Path javaExecutable,
            Path controlledAgent,
            Path probeClassPath,
            String distributionVersion,
            String expectedUpstreamSha,
            String expectedExtensionSha
    ) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toString());
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Dsun.stdout.encoding=UTF-8");
        command.add("-Dsun.stderr.encoding=UTF-8");
        command.add("-Dotel.traces.exporter=logging");
        command.add("-Dotel.metrics.exporter=none");
        command.add("-Dotel.logs.exporter=none");
        command.add("-Dplatform.environment=dev");
        command.add("-Dplatform.tracing.spi-attestation.mode=EXACT_EVIDENCE");
        command.add("-Dplatform.tracing.service.name=controlled-agent-runtime-test");
        command.add("-Dplatform.tracing.service.version=1.0.0");
        command.add("-Dplatform.tracing.service.environment=test");
        command.add("-Dplatform.tracing.service.c-group=controlled-agent-tests");
        command.add("-Dplatform.tracing.resource.validation-mode=STRICT");
        command.add("-javaagent:" + controlledAgent);
        command.add("-cp");
        command.add(probeClassPath.toString());
        command.add(ControlledAgentRuntimeProbe.class.getName());
        command.add(distributionVersion);
        command.add(controlledAgent.toString());
        command.add(expectedUpstreamSha);
        command.add(expectedExtensionSha);
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

    private static byte[] readEntry(Path archive, String name) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry entry = zip.getEntry(name);
            if (entry == null || entry.isDirectory()) {
                throw new IllegalStateException("Required ZIP entry is missing: " + name);
            }
            return zip.getInputStream(entry).readAllBytes();
        }
    }

    private static Properties loadProperties(byte[] bytes) throws IOException {
        Properties properties = new Properties();
        properties.load(new ByteArrayInputStream(bytes));
        return properties;
    }

    private static List<String> readContiguousProviders(Properties pin) {
        long providerCount = pin.stringPropertyNames().stream()
                .filter(name -> name.startsWith("provider."))
                .count();
        if (providerCount <= 0 || providerCount > Integer.MAX_VALUE) {
            throw new IllegalStateException("ACP pin resource provider count is outside the test range");
        }
        Set<String> expectedKeys = new LinkedHashSet<>(List.of("format.version", "upstream.sha256"));
        List<String> providers = new ArrayList<>((int) providerCount);
        for (int index = 0; index < providerCount; index++) {
            String key = "provider." + index;
            String provider = pin.getProperty(key);
            if (provider == null || provider.isBlank()) {
                throw new IllegalStateException("ACP pin resource provider indexes are not contiguous");
            }
            expectedKeys.add(key);
            providers.add(provider);
        }
        if (!pin.stringPropertyNames().equals(expectedKeys)) {
            throw new IllegalStateException(
                    "ACP pin resource keys are unexpected: " + pin.stringPropertyNames());
        }
        List<String> sorted = providers.stream().sorted().toList();
        if (!providers.equals(sorted) || providers.size() != new LinkedHashSet<>(providers).size()) {
            throw new IllegalStateException("ACP pin resource providers are not sorted and unique");
        }
        return List.copyOf(providers);
    }

    private static byte[] providerPreimage(List<String> providers) {
        StringBuilder preimage = new StringBuilder();
        for (String provider : providers) {
            preimage.append(provider).append('\n');
        }
        return preimage.toString().getBytes(StandardCharsets.UTF_8);
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
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
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

}
