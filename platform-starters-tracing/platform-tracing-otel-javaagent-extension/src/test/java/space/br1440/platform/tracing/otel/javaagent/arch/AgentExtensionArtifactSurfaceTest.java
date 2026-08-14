package space.br1440.platform.tracing.otel.javaagent.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;

class AgentExtensionArtifactSurfaceTest {

    private static final String EXTENSION_NAMESPACE =
            "space/br1440/platform/tracing/otel/javaagent/";
    private static final String EMBEDDED_EXTENSION =
            "extensions/platform-tracing-otel-javaagent-extension.jar";

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
}
