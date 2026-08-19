package space.br1440.platform.tracing.otel.javaagent.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadiness;

class AgentExtensionArtifactSurfaceTest {

    private static final String EXTENSION_NAMESPACE =
            "space/br1440/platform/tracing/otel/javaagent/";
    private static final String IDENTITY_RESOURCE =
            "META-INF/platform-tracing/agent-logging-schema.properties";
    private static final String FIELD_SANITIZER_CLASS =
            "space/br1440/platform/tracing/otel/javaagent/logging/FieldSanitizer.class";
    private static final String FIELD_NAME_POLICY_CLASS =
            "space/br1440/platform/tracing/otel/javaagent/logging/AgentLogFieldNamePolicy.class";
    private static final String TEXT_SANITIZER_CLASS =
            "space/br1440/platform/tracing/otel/javaagent/logging/AgentLogTextSanitizer.class";
    private static final String INPUT_POLICY_CLASS =
            "space/br1440/platform/tracing/otel/javaagent/logging/AgentLogInputPolicy.class";
    private static final String FIELD_SANITIZER_MARKER = "FieldSanitizer";
    private static final String INPUT_POLICY_MARKER = "AgentLogInputPolicy";
    private static final String MASKING_YAML = "platform-log-masking-rules.yaml";
    private static final String SCHEMA_RESOURCE = "platform-log-event.schema.json";
    private static final String MANIFEST_FRAGMENT =
            "agent-logging-schema-manifest.mf";
    private static final String PIN_CLASS =
            "space/br1440/platform/tracing/otel/javaagent/bootstrap/PinnedUpstreamAcpProviders.class";
    private static final String PIN_JSON = "opentelemetry-javaagent-2.30.0-acp-pin.json";
    private static final List<String> FORBIDDEN_PREFIXES = List.of(
            "org/springframework/",
            "ch/qos/logback/",
            "reactor/",
            "jakarta/servlet/",
            "space/br1440/platform/logging/configuration/",
            "space/br1440/platform/logging/logback/",
            "space/br1440/platform/logging/logbook/",
            "space/br1440/platform/logging/aspect/",
            "space/br1440/platform/logging/masking/",
            "space/br1440/platform/logging/mdc/",
            "space/br1440/platform/tracing/otel/runtime/otel/",
            "space/br1440/platform/tracing/autoconfigure/"
    );

    @Test
    void library_jar_is_shallow_and_does_not_embed_shared_modules() throws IOException {
        // given
        Path libraryJar = Path.of(requiredProperty("platformTracing.libraryJar"));

        // when
        List<String> entries = listEntries(libraryJar);

        // then
        assertThat(entries)
                .noneMatch(name -> name.startsWith("space/br1440/platform/tracing/api/"))
                .noneMatch(name -> name.startsWith("space/br1440/platform/tracing/runtime/"))
                .noneMatch(name -> name.startsWith("space/br1440/platform/common/util/"))
                .noneMatch(name -> name.startsWith("org/slf4j/"))
                .doesNotContain(IDENTITY_RESOURCE, SCHEMA_RESOURCE)
                .noneMatch(name -> name.startsWith("space/br1440/platform/logging/"));
    }

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
    void extension_jar_does_not_contain_forbidden_application_packages() throws IOException {
        // given
        Path extensionJar = Path.of(requiredProperty("platformTracing.extensionJar"));

        // when
        List<String> violations = listEntries(extensionJar).stream()
                .filter(name -> FORBIDDEN_PREFIXES.stream().anyMatch(name::startsWith))
                .toList();

        // then
        assertThat(violations).isEmpty();
    }

    @Test
    void extension_jar_contains_runtime_policy_and_not_application_otel() throws IOException {
        // given
        Path extensionJar = Path.of(requiredProperty("platformTracing.extensionJar"));

        // when
        List<String> entries = listEntries(extensionJar);

        // then
        assertThat(entries)
                .anyMatch(name -> name.startsWith("space/br1440/platform/tracing/runtime/sampling/"))
                .anyMatch(name -> name.startsWith("space/br1440/platform/tracing/runtime/versioned/"))
                .noneMatch(name -> name.startsWith("space/br1440/platform/tracing/otel/sampling/"))
                .noneMatch(name -> name.contains("OtelTracingRuntime.class"));
    }

    @Test
    void extension_jar_contains_split_log_policies_and_not_legacy_types() throws IOException {
        // given
        Path extensionJar = Path.of(requiredProperty("platformTracing.extensionJar"));

        // when
        List<String> entries = listEntries(extensionJar);
        List<String> sanitizerHits = findUtf8Marker(extensionJar, FIELD_SANITIZER_MARKER);
        List<String> policyHits = findUtf8Marker(extensionJar, INPUT_POLICY_MARKER);

        // then
        assertThat(entries).contains(FIELD_NAME_POLICY_CLASS, TEXT_SANITIZER_CLASS);
        assertThat(entries).doesNotContain(FIELD_SANITIZER_CLASS, INPUT_POLICY_CLASS);
        assertThat(entries).noneMatch(name -> name.endsWith(MASKING_YAML));
        assertThat(sanitizerHits).isEmpty();
        assertThat(policyHits).isEmpty();
    }

    @Test
    void extension_jar_does_not_contain_platform_logging_schema_resources() throws IOException {
        // given
        Path extensionJar = Path.of(requiredProperty("platformTracing.extensionJar"));

        // when
        List<String> entries = listEntries(extensionJar);

        // then
        assertThat(entries)
                .doesNotContain(SCHEMA_RESOURCE)
                .noneMatch(name -> name.startsWith("space/br1440/platform/logging/"));
    }

    @Test
    void extension_jar_embeds_generated_pin_class_and_not_baseline_json() throws IOException {
        // given
        Path extensionJar = Path.of(requiredProperty("platformTracing.extensionJar"));

        // when
        List<String> entries = listEntries(extensionJar);
        long pinClassCount = entries.stream().filter(PIN_CLASS::equals).count();

        // then
        assertThat(pinClassCount).isEqualTo(1);
        assertThat(entries).noneMatch(name -> name.endsWith(PIN_JSON));
    }

    @Test
    void extension_jar_embeds_schema_identity_only() throws IOException {
        // given
        Path extensionJar = Path.of(requiredProperty("platformTracing.extensionJar"));

        // when
        List<String> entries = listEntries(extensionJar);
        long identityCount = entries.stream().filter(IDENTITY_RESOURCE::equals).count();
        Properties identity = new Properties();
        identity.load(new ByteArrayInputStream(readEntry(extensionJar, IDENTITY_RESOURCE)));
        Manifest manifest;
        try (JarFile jar = new JarFile(extensionJar.toFile())) {
            manifest = jar.getManifest();
        }

        // then
        assertThat(identityCount).isEqualTo(1);
        assertThat(entries).doesNotContain(MANIFEST_FRAGMENT);
        assertThat(identity.getProperty("schema.hashAlgorithm"))
                .isEqualTo("PLATFORM_LOG_SCHEMA_SET_SHA256_V1");
        assertThat(identity.getProperty("schema.sha256")).isNotBlank();
        assertThat(manifest.getMainAttributes().getValue("Platform-Log-Schema-SHA256"))
                .isEqualTo(identity.getProperty("schema.sha256"));
        assertThat(manifest.getMainAttributes().getValue("Platform-Log-Schema-Version"))
                .isEqualTo(identity.getProperty("schema.semanticVersion"));
        assertThat(manifest.getMainAttributes().getValue("Implementation-Title"))
                .isEqualTo(PlatformExtensionReadiness.IMPLEMENTATION_TITLE);
        assertThat(manifest.getMainAttributes().getValue("Implementation-Version"))
                .isNotBlank()
                .isNotEqualTo("development");
    }

    @Test
    void extension_jar_does_not_embed_slf4j() throws IOException {
        // given
        Path extensionJar = Path.of(requiredProperty("platformTracing.extensionJar"));

        // when
        List<String> entries = listEntries(extensionJar);

        // then
        assertThat(entries)
                .noneMatch(name -> name.startsWith("org/slf4j/"))
                .noneMatch(name -> name.equals("simplelogger.properties"))
                .noneMatch(name -> name.equals("META-INF/services/org.slf4j.spi.SLF4JServiceProvider"));
    }

    private static List<String> findTestSupportEntries(Path extensionJar) throws IOException {
        List<String> violations = new ArrayList<>();
        for (String name : listEntries(extensionJar)) {
            if (isTestSupportEntry(name)) {
                violations.add(name);
            }
        }
        return violations;
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

    private static List<String> findUtf8Marker(Path jar, String marker) throws IOException {
        byte[] needle = marker.getBytes(StandardCharsets.UTF_8);
        List<String> hits = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !(name.endsWith(".class")
                        || name.endsWith(".properties")
                        || name.endsWith(".json")
                        || name.endsWith(".yaml")
                        || name.endsWith(".txt")
                        || name.endsWith(".md")
                        || name.endsWith(".mf"))) {
                    continue;
                }
                byte[] bytes = zip.getInputStream(entry).readAllBytes();
                if (indexOf(bytes, needle) >= 0) {
                    hits.add(name);
                }
            }
        }
        return hits;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
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
