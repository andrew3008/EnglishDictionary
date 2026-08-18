package space.br1440.platform.tracing.otel.javaagent.distribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;

class ControlledAgentDistributionTest {

    private static final String EMBEDDED_EXTENSION = "extensions/platform-tracing-otel-javaagent-extension.jar";
    private static final String IDENTITY_RESOURCE = "META-INF/platform-tracing/agent-logging-schema.properties";

    @Test
    void embedded_extension_is_at_canonical_path() throws IOException {
        // given
        Path controlledAgentJar = Path.of(requiredProperty("platformTracing.controlledAgentJar"));

        // when
        List<String> entries = listEntries(controlledAgentJar);

        // then
        assertThat(entries).contains(EMBEDDED_EXTENSION);
    }

    @Test
    void distribution_contains_exactly_one_platform_extension() throws IOException {
        // given
        Path controlledAgentJar = Path.of(requiredProperty("platformTracing.controlledAgentJar"));

        // when
        List<String> extensionJars = listEntries(controlledAgentJar).stream()
                .filter(name -> name.startsWith("extensions/") && name.endsWith(".jar"))
                .toList();

        // then
        assertThat(extensionJars).containsExactly(EMBEDDED_EXTENSION);
    }

    @Test
    void embedded_extension_is_byte_identical_to_resolved_artifact() throws IOException {
        // given
        Path extensionJar = Path.of(requiredProperty("platformTracing.extensionJar"));
        Path controlledAgentJar = Path.of(requiredProperty("platformTracing.controlledAgentJar"));

        // when
        byte[] embeddedExtension = readEntry(controlledAgentJar, EMBEDDED_EXTENSION);
        byte[] publishedExtension = Files.readAllBytes(extensionJar);

        // then
        assertThat(embeddedExtension).isEqualTo(publishedExtension);
    }

    @Test
    void distribution_has_no_stale_classifier_layout() throws IOException {
        // given
        Path controlledAgentJar = Path.of(requiredProperty("platformTracing.controlledAgentJar"));

        // when
        List<String> stale = listEntries(controlledAgentJar).stream()
                .filter(name -> name.contains("javaagent-extension") && !name.equals(EMBEDDED_EXTENSION))
                .toList();

        // then
        assertThat(stale).isEmpty();
    }

    @Test
    void distribution_has_no_additional_extension_candidates() throws IOException {
        // given
        Path controlledAgentJar = Path.of(requiredProperty("platformTracing.controlledAgentJar"));

        // when
        List<String> candidates = listEntries(controlledAgentJar).stream()
                .filter(name -> name.endsWith(".jar"))
                .filter(name -> name.contains("extension") || name.startsWith("extensions/"))
                .toList();

        // then
        assertThat(candidates).containsExactly(EMBEDDED_EXTENSION);
    }

    @Test
    void upstream_agent_artifact_is_present_and_readable() throws IOException {
        // given
        Path controlledAgentJar = Path.of(requiredProperty("platformTracing.controlledAgentJar"));

        // when
        Manifest manifest;
        try (JarFile jar = new JarFile(controlledAgentJar.toFile())) {
            manifest = jar.getManifest();
        }
        List<String> entries = listEntries(controlledAgentJar);

        // then
        assertThat(manifest).isNotNull();
        assertThat(manifest.getMainAttributes().getValue("Premain-Class")).isNotBlank();
        assertThat(entries)
                .anyMatch(name -> name.startsWith("io/opentelemetry/javaagent/"))
                .hasSizeGreaterThan(1);
    }

    @Test
    void distribution_has_no_schema_package_or_identity_outside_embedded_extension() throws IOException {
        // given
        Path controlledAgentJar = Path.of(requiredProperty("platformTracing.controlledAgentJar"));

        // when
        List<String> outerEntries = listEntries(controlledAgentJar);
        List<String> embeddedEntries = listJarEntries(readEntry(controlledAgentJar, EMBEDDED_EXTENSION));

        // then
        assertThat(outerEntries)
                .noneMatch(name -> name.startsWith("space/br1440/platform/logging/"))
                .doesNotContain(IDENTITY_RESOURCE);
        assertThat(embeddedEntries)
                .contains(IDENTITY_RESOURCE)
                .noneMatch(name -> name.startsWith("space/br1440/platform/logging/"));
    }

    @Test
    void controlled_agent_writes_platform_logs_to_stderr_without_nop_fallback() throws IOException, InterruptedException {
        // given
        Path controlledAgentJar = Path.of(requiredProperty("platformTracing.controlledAgentJar"));
        Path javaExecutable = Path.of(
                System.getProperty("java.home"),
                "bin",
                isWindows() ? "java.exe" : "java"
        );
        ProcessBuilder processBuilder = new ProcessBuilder(
                javaExecutable.toString(),
                "-Dfile.encoding=UTF-8",
                "-Dsun.stderr.encoding=UTF-8",
                "-Dotel.traces.exporter=none",
                "-Dotel.metrics.exporter=none",
                "-Dotel.logs.exporter=none",
                "-javaagent:" + controlledAgentJar,
                "-version"
        );
        Path stdoutFile = Files.createTempFile("platform-controlled-agent-stdout-", ".log");
        Path stderrFile = Files.createTempFile("platform-controlled-agent-stderr-", ".log");
        processBuilder.redirectOutput(stdoutFile.toFile());
        processBuilder.redirectError(stderrFile.toFile());

        try {
            // when
            Process process = processBuilder.start();
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            }
            String stdout = Files.readString(stdoutFile, StandardCharsets.UTF_8);
            String stderr = Files.readString(stderrFile, StandardCharsets.UTF_8);

            // then
            assertThat(finished).as("Controlled Agent child JVM must terminate").isTrue();
            assertThat(process.exitValue()).isZero();
            assertThat(stderr)
                    .doesNotContain("No SLF4J providers were found")
                    .doesNotContain("Defaulting to no-operation (NOP) logger implementation")
                    .contains("\"event_code\":\"PTA-")
                    .contains("\"message\":");
            assertThat(stdout).doesNotContain("\"event_code\":\"PTA-");
        } finally {
            Files.deleteIfExists(stdoutFile);
            Files.deleteIfExists(stderrFile);
        }
    }

    @Test
    void resolved_upstream_agent_matches_reviewed_sha256() throws IOException {
        // given
        Path upstreamAgentJar = Path.of(requiredProperty("platformTracing.upstreamAgentJar"));
        Path baseline = Path.of(requiredProperty("platformTracing.acpPinBaseline"));

        // when
        String actual = sha256Hex(Files.readAllBytes(upstreamAgentJar));
        String expected = readJsonStringField(Files.readString(baseline, StandardCharsets.UTF_8), "upstreamSha256");

        // then
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void extracted_upstream_acp_names_match_reviewed_pin() throws IOException {
        // given
        Path upstreamAgentJar = Path.of(requiredProperty("platformTracing.upstreamAgentJar"));
        Path baseline = Path.of(requiredProperty("platformTracing.acpPinBaseline"));
        String baselineJson = Files.readString(baseline, StandardCharsets.UTF_8);

        // when
        List<String> extracted = extractUpstreamAcpNames(upstreamAgentJar);

        // then
        assertThat(extracted).containsExactly(
                "io.opentelemetry.contrib.baggage.processor.BaggageProcessorCustomizer",
                "io.opentelemetry.javaagent.shaded.instrumentation.resources.internal."
                        + "ResourceProviderPropertiesCustomizer",
                "io.opentelemetry.javaagent.tooling.AgentTracerProviderConfigurer",
                "io.opentelemetry.javaagent.tooling.config.ConfigurationPropertiesSupplier",
                "io.opentelemetry.javaagent.tooling.config.OtlpProtocolPropertiesSupplier");
        assertThat(extracted).allMatch(baselineJson::contains);
    }

    @Test
    void exact_evidence_marker_matches_reviewed_pin() throws IOException, InterruptedException {
        // given
        Path controlledAgentJar = Path.of(requiredProperty("platformTracing.controlledAgentJar"));
        Path baseline = Path.of(requiredProperty("platformTracing.acpPinBaseline"));
        String expectedPinSha = readJsonStringField(
                Files.readString(baseline, StandardCharsets.UTF_8), "pinSha256");
        String expectedUpstreamSha = readJsonStringField(
                Files.readString(baseline, StandardCharsets.UTF_8), "upstreamSha256");
        Path javaExecutable = Path.of(
                System.getProperty("java.home"),
                "bin",
                isWindows() ? "java.exe" : "java"
        );
        ProcessBuilder processBuilder = new ProcessBuilder(
                javaExecutable.toString(),
                "-Dfile.encoding=UTF-8",
                "-Dsun.stderr.encoding=UTF-8",
                "-Dotel.traces.exporter=none",
                "-Dotel.metrics.exporter=none",
                "-Dotel.logs.exporter=none",
                "-Dplatform.tracing.spi-attestation.mode=EXACT_EVIDENCE",
                "-javaagent:" + controlledAgentJar,
                "-version"
        );
        Path stdoutFile = Files.createTempFile("platform-exact-stdout-", ".log");
        Path stderrFile = Files.createTempFile("platform-exact-stderr-", ".log");
        processBuilder.redirectOutput(stdoutFile.toFile());
        processBuilder.redirectError(stderrFile.toFile());

        try {
            // when
            Process process = processBuilder.start();
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            }
            String stderr = Files.readString(stderrFile, StandardCharsets.UTF_8);
            long markers = stderr.lines()
                    .filter(line -> line.contains("\"event_code\":\"PTA-BOOT-010\""))
                    .count();

            // then
            assertThat(finished).isTrue();
            assertThat(process.exitValue()).isZero();
            assertThat(markers).isEqualTo(1);
            assertThat(stderr)
                    .contains("\"pin_sha256\":\"" + expectedPinSha + "\"")
                    .contains("\"set_sha256\":\"" + expectedPinSha + "\"")
                    .contains("\"upstream_agent_sha256\":\"" + expectedUpstreamSha + "\"")
                    .contains("\"pin_count\":\"5\"")
                    .contains("\"pin_format_version\":\"1\"");
        } finally {
            Files.deleteIfExists(stdoutFile);
            Files.deleteIfExists(stderrFile);
        }
    }

    private static List<String> listEntries(Path jar) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                names.add(entries.nextElement().getName());
            }
        }
        return names;
    }

    private static List<String> listJarEntries(byte[] jarBytes) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                names.add(entry.getName());
            }
        }
        return names;
    }

    @SuppressWarnings("SameParameterValue")
    private static byte[] readEntry(Path jar, String entryName) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(entryName);
            assertThat(entry).as("Embedded extension entry must be present").isNotNull();
            return zip.getInputStream(entry).readAllBytes();
        }
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
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format(Locale.ROOT, "%02x", value & 0xFF));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }

    private static List<String> extractUpstreamAcpNames(Path agentJar) throws IOException {
        String acpSpi = "io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider";
        String descriptorPath = "inst/META-INF/services/" + acpSpi;
        try (ZipFile zip = new ZipFile(agentJar.toFile())) {
            List<String> descriptors = new ArrayList<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.endsWith(".jar")) {
                    throw new IllegalStateException("Nested JAR is not part of the 2.30.0 loader contract: " + name);
                }
                if (name.contains("META-INF/services/" + acpSpi)) {
                    descriptors.add(name);
                }
            }
            assertThat(descriptors).containsExactly(descriptorPath);
            ZipEntry descriptor = zip.getEntry(descriptorPath);
            assertThat(descriptor).isNotNull();
            String text = new String(zip.getInputStream(descriptor).readAllBytes(), StandardCharsets.UTF_8);
            List<String> names = new ArrayList<>();
            for (String line : text.split("\n", -1)) {
                int comment = line.indexOf('#');
                String name = (comment >= 0 ? line.substring(0, comment) : line).trim();
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
            names.sort(String::compareTo);
            return List.copyOf(names);
        }
    }

    private static String readJsonStringField(String json, String field) {
        String needle = "\"" + field + "\": \"";
        int start = json.indexOf(needle);
        if (start < 0) {
            throw new IllegalStateException("Missing JSON field: " + field);
        }
        int valueStart = start + needle.length();
        int valueEnd = json.indexOf('"', valueStart);
        if (valueEnd < 0) {
            throw new IllegalStateException("Unterminated JSON field: " + field);
        }
        return json.substring(valueStart, valueEnd);
    }
}
