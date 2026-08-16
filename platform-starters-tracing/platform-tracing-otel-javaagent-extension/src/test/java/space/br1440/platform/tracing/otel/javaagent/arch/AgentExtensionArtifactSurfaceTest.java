package space.br1440.platform.tracing.otel.javaagent.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;

class AgentExtensionArtifactSurfaceTest {

    private static final String EXTENSION_NAMESPACE =
            "space/br1440/platform/tracing/otel/javaagent/";
    private static final String EMBEDDED_EXTENSION =
            "extensions/platform-tracing-otel-javaagent-extension.jar";
    private static final String SLF4J_PROVIDER =
            "org/slf4j/simple/SimpleServiceProvider.class";
    private static final String SLF4J_PROVIDER_REGISTRATION =
            "META-INF/services/org.slf4j.spi.SLF4JServiceProvider";
    private static final String SIMPLE_LOGGER_CONFIGURATION = "simplelogger.properties";

    @Test
    void extension_jar_does_not_publish_test_support_surface() throws IOException {
        // given
        Path extensionJar = Path.of(requiredProperty("platformTracing.extensionJar"));

        // when
        List<String> violations = findTestSupportEntries(extensionJar);

        // then
        assertThat(violations).isEmpty();
    }

    @Test
    void controlled_agent_embeds_the_exact_extension_jar() throws IOException {
        // given
        Path extensionJar = Path.of(requiredProperty("platformTracing.extensionJar"));
        Path controlledAgentJar = Path.of(requiredProperty("platformTracing.controlledAgentJar"));

        // when
        byte[] embeddedExtension = readEntry(controlledAgentJar, EMBEDDED_EXTENSION);

        // then
        assertThat(embeddedExtension).isEqualTo(Files.readAllBytes(extensionJar));
    }

    @Test
    void extension_jar_embeds_the_stderr_logging_provider() throws IOException {
        // given
        Path extensionJar = Path.of(requiredProperty("platformTracing.extensionJar"));

        // when
        byte[] providerClass = readEntry(extensionJar, SLF4J_PROVIDER);
        String providerRegistration = new String(
                readEntry(extensionJar, SLF4J_PROVIDER_REGISTRATION),
                StandardCharsets.UTF_8
        ).trim();

        // then
        assertThat(providerClass).isNotEmpty();
        assertThat(providerRegistration).isEqualTo("org.slf4j.simple.SimpleServiceProvider");
    }

    @Test
    void controlled_agent_exposes_the_stderr_logging_configuration_to_the_agent_classloader()
            throws IOException {
        // given
        Path controlledAgentJar = Path.of(requiredProperty("platformTracing.controlledAgentJar"));

        // when
        String configuration = new String(
                readEntry(controlledAgentJar, SIMPLE_LOGGER_CONFIGURATION),
                StandardCharsets.UTF_8
        );

        // then
        assertThat(configuration)
                .contains("org.slf4j.simpleLogger.logFile=System.err")
                .contains("org.slf4j.simpleLogger.showDateTime=true")
                .contains("org.slf4j.simpleLogger.showThreadName=true")
                .contains("org.slf4j.simpleLogger.showLogName=true");
    }

    @Test
    void controlled_agent_writes_platform_logs_to_stderr_without_nop_fallback()
            throws IOException, InterruptedException {
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
                "-javaagent:" + controlledAgentJar,
                "-version"
        );
        processBuilder.environment().put("OTEL_SDK_DISABLED", "true");
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
                .contains("space.br1440.platform.tracing")
                .containsPattern("(?m)^\\d{4}-\\d{2}-\\d{2}T.* \\[main] \\[(WARN|INFO|ERROR)] ");
            assertThat(stdout).doesNotContain("space.br1440.platform.tracing");
        } finally {
            Files.deleteIfExists(stdoutFile);
            Files.deleteIfExists(stderrFile);
        }
    }

    private static List<String> findTestSupportEntries(Path extensionJar) throws IOException {
        List<String> violations = new ArrayList<>();
        try (ZipFile zip = new ZipFile(extensionJar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (isTestSupportEntry(name)) {
                    violations.add(name);
                }
            }
        }
        return violations;
    }

    private static boolean isTestSupportEntry(String name) {
        if (!name.startsWith(EXTENSION_NAMESPACE)) {
            return false;
        }
        if (name.equals(EXTENSION_NAMESPACE + "scrubbing/ScrubbingRuntimeSupport.class")) {
            return true;
        }
        if (name.contains("/testsupport/") || name.contains("/fixtures/") || name.contains("/harness/")) {
            return true;
        }
        return name.endsWith("Test.class")
                || name.endsWith("TestFixtures.class")
                || name.endsWith("TestSupport.class");
    }

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
