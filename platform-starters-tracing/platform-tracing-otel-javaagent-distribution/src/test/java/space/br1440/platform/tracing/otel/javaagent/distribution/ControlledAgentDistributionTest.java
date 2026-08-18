package space.br1440.platform.tracing.otel.javaagent.distribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
