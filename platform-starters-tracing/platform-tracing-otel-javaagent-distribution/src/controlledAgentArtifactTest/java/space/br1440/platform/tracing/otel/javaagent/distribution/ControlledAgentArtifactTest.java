package space.br1440.platform.tracing.otel.javaagent.distribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;

class ControlledAgentArtifactTest {

    private static final String EXTENSION_ENTRY =
            "extensions/platform-tracing-otel-javaagent-extension.jar";
    private static final String IDENTITY_ENTRY =
            "META-INF/platform-tracing/javaagent-distribution.properties";
    private static final String FORBIDDEN_NOTICES_ENTRY =
            "META-INF/platform-tracing/THIRD-PARTY-NOTICES.txt";
    private static final String MANIFEST_ENTRY = "META-INF/MANIFEST.MF";
    private static final String EXTENSION_NAMESPACE =
            "space/br1440/platform/tracing/otel/javaagent/";
    private static final String SCHEMA_IDENTITY_RESOURCE =
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
    private static final String MANIFEST_FRAGMENT = "agent-logging-schema-manifest.mf";
    private static final String PIN_CLASS =
            "space/br1440/platform/tracing/otel/javaagent/bootstrap/PinnedUpstreamAcpProviders.class";
    private static final String ACP_PIN_RESOURCE =
            "META-INF/platform-tracing/upstream-acp-pin.properties";
    private static final List<String> FORBIDDEN_EXTENSION_PREFIXES = List.of(
            "org/springframework/",
            "ch/qos/logback/",
            "reactor/",
            "jakarta/servlet/",
            "io/opentelemetry/api/",
            "io/opentelemetry/context/",
            "io/opentelemetry/common/",
            "space/br1440/platform/logging/configuration/",
            "space/br1440/platform/logging/logback/",
            "space/br1440/platform/logging/logbook/",
            "space/br1440/platform/logging/aspect/",
            "space/br1440/platform/logging/masking/",
            "space/br1440/platform/logging/mdc/",
            "space/br1440/platform/tracing/otel/runtime/otel/",
            "space/br1440/platform/tracing/autoconfigure/");
    private static final String UPSTREAM_COORDINATE =
            "io.opentelemetry.javaagent:opentelemetry-javaagent:2.30.0";
    private static final LocalDateTime CANONICAL_TIMESTAMP =
            LocalDateTime.of(1980, 2, 1, 0, 0, 0);
    private static final Comparator<String> UTF_8_ORDER = ControlledAgentArtifactTest::compareUtf8;

    @Test
    void output_is_an_exact_upstream_payload_plus_controlled_entries() throws IOException {
        // given
        Path controlledAgent = requiredPath("platformTracing.controlledAgentJar");
        Path upstreamAgent = requiredPath("platformTracing.upstreamAgentJar");
        Map<String, byte[]> upstreamEntries = readEntries(upstreamAgent);
        Set<String> expectedNames = new LinkedHashSet<>(upstreamEntries.keySet());
        expectedNames.add(EXTENSION_ENTRY);
        expectedNames.add(IDENTITY_ENTRY);

        // when
        Map<String, byte[]> controlledEntries = readEntries(controlledAgent);

        // then
        assertThat(controlledEntries.keySet()).containsExactlyInAnyOrderElementsOf(expectedNames);
        for (Map.Entry<String, byte[]> upstreamEntry : upstreamEntries.entrySet()) {
            assertThat(controlledEntries.get(upstreamEntry.getKey()))
                    .as("Payload of upstream entry %s", upstreamEntry.getKey())
                    .isEqualTo(upstreamEntry.getValue());
        }
        assertThat(controlledEntries.get(MANIFEST_ENTRY))
                .isEqualTo(upstreamEntries.get(MANIFEST_ENTRY));
    }

    @Test
    void embedded_extension_is_byte_identical_to_input() throws IOException {
        // given
        Path controlledAgent = requiredPath("platformTracing.controlledAgentJar");
        Path extension = requiredPath("platformTracing.extensionJar");

        // when
        Map<String, byte[]> entries = readEntries(controlledAgent);
        List<String> extensionCandidates = entries.keySet().stream()
                .filter(name -> name.startsWith("extensions/") && name.endsWith(".jar"))
                .toList();

        // then
        assertThat(extensionCandidates).containsExactly(EXTENSION_ENTRY);
        assertThat(entries.get(EXTENSION_ENTRY)).isEqualTo(Files.readAllBytes(extension));
    }

    @Test
    void third_party_notices_are_not_embedded() throws IOException {
        // given
        Path controlledAgent = requiredPath("platformTracing.controlledAgentJar");

        // when
        Map<String, byte[]> entries = readEntries(controlledAgent);

        // then
        assertThat(entries).doesNotContainKey(FORBIDDEN_NOTICES_ENTRY);
    }

    @Test
    void identity_is_canonical_and_binds_all_owned_inputs() throws IOException {
        // given
        Path controlledAgent = requiredPath("platformTracing.controlledAgentJar");
        Path upstreamAgent = requiredPath("platformTracing.upstreamAgentJar");
        Path extension = requiredPath("platformTracing.extensionJar");
        String version = requiredProperty("platformTracing.distributionVersion");
        String expectedIdentity = "distribution.artifact=platform-tracing-javaagent\n"
                + "distribution.group=space.br1440.platform.starters.tracing\n"
                + "distribution.version=" + version + "\n"
                + "extension.path=" + EXTENSION_ENTRY + "\n"
                + "extension.sha256=" + sha256Hex(Files.readAllBytes(extension)) + "\n"
                + "format.version=1\n"
                + "minimum.java=21\n"
                + "upstream.coordinate=" + UPSTREAM_COORDINATE + "\n"
                + "upstream.sha256=" + sha256Hex(Files.readAllBytes(upstreamAgent)) + "\n";

        // when
        byte[] identity = readEntry(controlledAgent, IDENTITY_ENTRY);

        // then
        assertThat(identity).isEqualTo(expectedIdentity.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void upstream_manifest_contract_is_preserved() throws IOException {
        // given
        Path controlledAgent = requiredPath("platformTracing.controlledAgentJar");

        // when
        Manifest manifest = new Manifest(new ByteArrayInputStream(readEntry(controlledAgent, MANIFEST_ENTRY)));
        Attributes attributes = manifest.getMainAttributes();

        // then
        assertThat(attributes.getValue("Implementation-Title")).isEqualTo("javaagent");
        assertThat(attributes.getValue("Implementation-Version")).isEqualTo("2.30.0");
        assertThat(attributes.getValue("Main-Class"))
                .isEqualTo("io.opentelemetry.javaagent.OpenTelemetryAgent");
        assertThat(attributes.getValue("Agent-Class"))
                .isEqualTo("io.opentelemetry.javaagent.OpenTelemetryAgent");
        assertThat(attributes.getValue("Premain-Class"))
                .isEqualTo("io.opentelemetry.javaagent.OpenTelemetryAgent");
        assertThat(attributes.getValue("Can-Redefine-Classes")).isEqualTo("true");
        assertThat(attributes.getValue("Can-Retransform-Classes")).isEqualTo("true");
        assertThat(attributes.getValue(Attributes.Name.CLASS_PATH)).isNull();
    }

    @Test
    void extension_manifest_has_release_train_identity() throws IOException {
        // given
        Path controlledAgent = requiredPath("platformTracing.controlledAgentJar");
        String version = requiredProperty("platformTracing.distributionVersion");

        // when
        byte[] extensionBytes = readEntry(controlledAgent, EXTENSION_ENTRY);
        Manifest manifest = readNestedManifest(extensionBytes);

        // then
        assertThat(manifest.getMainAttributes().getValue("Implementation-Title"))
                .isEqualTo("platform-tracing-otel-javaagent-extension");
        assertThat(manifest.getMainAttributes().getValue("Implementation-Version"))
                .isEqualTo(version)
                .isNotEqualToIgnoringCase("development");
    }

    @Test
    void extension_does_not_publish_test_support_surface() throws IOException {
        // given
        Path extension = requiredPath("platformTracing.extensionJar");

        // when
        List<String> violations = readEntries(extension).keySet().stream()
                .filter(ControlledAgentArtifactTest::isTestSupportEntry)
                .toList();

        // then
        assertThat(violations).isEmpty();
    }

    @Test
    void extension_does_not_embed_forbidden_or_agent_provided_packages() throws IOException {
        // given
        Path extension = requiredPath("platformTracing.extensionJar");

        // when
        List<String> violations = readEntries(extension).keySet().stream()
                .filter(name -> FORBIDDEN_EXTENSION_PREFIXES.stream().anyMatch(name::startsWith))
                .toList();

        // then
        assertThat(violations).isEmpty();
    }

    @Test
    void extension_contains_runtime_policy_and_not_application_otel() throws IOException {
        // given
        Path extension = requiredPath("platformTracing.extensionJar");

        // when
        Set<String> entries = readEntries(extension).keySet();

        // then
        assertThat(entries)
                .anyMatch(name -> name.startsWith("space/br1440/platform/tracing/runtime/sampling/"))
                .anyMatch(name -> name.startsWith("space/br1440/platform/tracing/runtime/versioned/"))
                .noneMatch(name -> name.startsWith("space/br1440/platform/tracing/otel/sampling/"))
                .noneMatch(name -> name.contains("OtelTracingRuntime.class"));
    }

    @Test
    void extension_contains_split_log_policies_and_not_legacy_types() throws IOException {
        // given
        Path extension = requiredPath("platformTracing.extensionJar");

        // when
        Map<String, byte[]> entries = readEntries(extension);
        List<String> sanitizerHits = findUtf8Marker(entries, FIELD_SANITIZER_MARKER);
        List<String> policyHits = findUtf8Marker(entries, INPUT_POLICY_MARKER);

        // then
        assertThat(entries.keySet()).contains(FIELD_NAME_POLICY_CLASS, TEXT_SANITIZER_CLASS);
        assertThat(entries.keySet()).doesNotContain(FIELD_SANITIZER_CLASS, INPUT_POLICY_CLASS);
        assertThat(entries.keySet()).noneMatch(name -> name.endsWith(MASKING_YAML));
        assertThat(sanitizerHits).isEmpty();
        assertThat(policyHits).isEmpty();
    }

    @Test
    void extension_excludes_raw_schema_and_platform_logging_packages() throws IOException {
        // given
        Path extension = requiredPath("platformTracing.extensionJar");

        // when
        Set<String> entries = readEntries(extension).keySet();

        // then
        assertThat(entries)
                .doesNotContain(SCHEMA_RESOURCE)
                .noneMatch(name -> name.startsWith("space/br1440/platform/logging/"));
    }

    @Test
    void extension_embeds_pin_class_and_single_reviewed_resource() throws IOException {
        // given
        Path extension = requiredPath("platformTracing.extensionJar");

        // when
        Set<String> entries = readEntries(extension).keySet();
        long pinClassCount = entries.stream().filter(PIN_CLASS::equals).count();
        long pinResourceCount = entries.stream().filter(ACP_PIN_RESOURCE::equals).count();

        // then
        assertThat(pinClassCount).isEqualTo(1);
        assertThat(pinResourceCount).isEqualTo(1);
    }

    @Test
    void extension_schema_identity_is_the_only_schema_contract() throws IOException {
        // given
        Path extension = requiredPath("platformTracing.extensionJar");

        // when
        Map<String, byte[]> entries = readEntries(extension);
        Properties identity = new Properties();
        identity.load(new ByteArrayInputStream(entries.get(SCHEMA_IDENTITY_RESOURCE)));
        Manifest manifest = new Manifest(new ByteArrayInputStream(entries.get(MANIFEST_ENTRY)));

        // then
        assertThat(entries.keySet().stream().filter(SCHEMA_IDENTITY_RESOURCE::equals).count())
                .isEqualTo(1);
        assertThat(entries).doesNotContainKeys(SCHEMA_RESOURCE, MANIFEST_FRAGMENT);
        assertThat(identity.getProperty("schema.hashAlgorithm"))
                .isEqualTo("PLATFORM_LOG_SCHEMA_SET_SHA256_V1");
        assertThat(identity.getProperty("schema.name")).isEqualTo("platform-log-event-v1");
        assertThat(identity.getProperty("schema.sha256")).isNotBlank();
        assertThat(manifest.getMainAttributes().getValue("Platform-Log-Schema-SHA256"))
                .isEqualTo(identity.getProperty("schema.sha256"));
        assertThat(manifest.getMainAttributes().getValue("Platform-Log-Schema-Version"))
                .isEqualTo(identity.getProperty("schema.semanticVersion"));
    }

    @Test
    void extension_does_not_embed_slf4j() throws IOException {
        // given
        Path extension = requiredPath("platformTracing.extensionJar");

        // when
        Set<String> entries = readEntries(extension).keySet();

        // then
        assertThat(entries)
                .noneMatch(name -> name.startsWith("org/slf4j/"))
                .doesNotContain(
                        "simplelogger.properties",
                        "META-INF/services/org.slf4j.spi.SLF4JServiceProvider");
    }

    @Test
    void archive_uses_the_reviewed_canonical_zip_profile() throws IOException {
        // given
        Path controlledAgent = requiredPath("platformTracing.controlledAgentJar");

        // when
        List<String> actualOrder = new ArrayList<>();
        try (ZipFile zip = new ZipFile(controlledAgent.toFile(), StandardCharsets.UTF_8)) {
            assertThat(zip.getComment()).isNull();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                actualOrder.add(entry.getName());
                assertThat(entry.getTimeLocal())
                        .as("Timestamp of %s", entry.getName())
                        .isEqualTo(CANONICAL_TIMESTAMP);
                assertThat(entry.getComment()).as("Comment of %s", entry.getName()).isNull();
                assertThat(entry.getExtra()).as("Extra field of %s", entry.getName()).isNull();
                assertThat(entry.getMethod())
                        .as("Compression method of %s", entry.getName())
                        .isEqualTo(entry.isDirectory() ? ZipEntry.STORED : ZipEntry.DEFLATED);
            }
        }
        List<String> expectedOrder = actualOrder.stream()
                .filter(name -> !MANIFEST_ENTRY.equals(name))
                .sorted(UTF_8_ORDER)
                .toList();
        List<String> expectedWithManifest = new ArrayList<>(expectedOrder.size() + 1);
        expectedWithManifest.add(MANIFEST_ENTRY);
        expectedWithManifest.addAll(expectedOrder);

        // then
        assertThat(actualOrder).containsExactlyElementsOf(expectedWithManifest);
    }

    @Test
    void reviewed_upstream_hash_and_acp_set_match_resolved_artifact() throws IOException {
        // given
        Path upstreamAgent = requiredPath("platformTracing.upstreamAgentJar");
        Path extension = requiredPath("platformTracing.extensionJar");
        Properties pin = loadProperties(readEntry(extension, ACP_PIN_RESOURCE));
        List<String> reviewedProviders = readContiguousProviders(pin);

        // when
        String actualUpstreamHash = sha256Hex(Files.readAllBytes(upstreamAgent));
        List<String> actualProviders = extractUpstreamAcpNames(upstreamAgent);

        // then
        assertThat(pin.getProperty("format.version")).isEqualTo("1");
        assertThat(actualUpstreamHash).isEqualTo(pin.getProperty("upstream.sha256"));
        assertThat(actualProviders).containsExactlyElementsOf(reviewedProviders);
    }

    private static boolean isTestSupportEntry(String name) {
        if (!name.startsWith(EXTENSION_NAMESPACE)) {
            return false;
        }
        if (name.equals(EXTENSION_NAMESPACE + "scrubbing/ScrubbingRuntimeSupport.class")) {
            return true;
        }
        if (name.contains("/testsupport/")
                || name.contains("/fixtures/")
                || name.contains("/harness/")) {
            return true;
        }
        return name.endsWith("Test.class")
                || name.endsWith("TestFixtures.class")
                || name.endsWith("TestSupport.class");
    }

    private static List<String> findUtf8Marker(Map<String, byte[]> entries, String marker) {
        byte[] needle = marker.getBytes(StandardCharsets.UTF_8);
        List<String> hits = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String name = entry.getKey();
            if (!(name.endsWith(".class")
                    || name.endsWith(".properties")
                    || name.endsWith(".json")
                    || name.endsWith(".yaml")
                    || name.endsWith(".txt")
                    || name.endsWith(".md")
                    || name.endsWith(".mf"))) {
                continue;
            }
            if (indexOf(entry.getValue(), needle) >= 0) {
                hits.add(name);
            }
        }
        return hits;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int index = 0; index <= haystack.length - needle.length; index++) {
            for (int offset = 0; offset < needle.length; offset++) {
                if (haystack[index + offset] != needle[offset]) {
                    continue outer;
                }
            }
            return index;
        }
        return -1;
    }

    private static Map<String, byte[]> readEntries(Path archive) throws IOException {
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                byte[] previous = result.put(entry.getName(), zip.getInputStream(entry).readAllBytes());
                if (previous != null) {
                    throw new IllegalStateException("Duplicate ZIP entry: " + entry.getName());
                }
            }
        }
        return result;
    }

    private static byte[] readEntry(Path archive, String name) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry entry = zip.getEntry(name);
            if (entry == null) {
                throw new IllegalStateException("Required ZIP entry is missing: " + name);
            }
            return zip.getInputStream(entry).readAllBytes();
        }
    }

    private static Manifest readNestedManifest(byte[] archive) throws IOException {
        Path temporary = Files.createTempFile("platform-extension-", ".jar");
        try {
            Files.write(temporary, archive);
            return new Manifest(new ByteArrayInputStream(readEntry(temporary, MANIFEST_ENTRY)));
        } finally {
            Files.deleteIfExists(temporary);
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

    private static List<String> extractUpstreamAcpNames(Path agentJar) throws IOException {
        String acpSpi = "io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider";
        String descriptorPath = "inst/META-INF/services/" + acpSpi;
        try (ZipFile zip = new ZipFile(agentJar.toFile(), StandardCharsets.UTF_8)) {
            List<String> descriptors = zip.stream()
                    .map(ZipEntry::getName)
                    .filter(name -> name.contains("META-INF/services/" + acpSpi))
                    .toList();
            assertThat(descriptors).containsExactly(descriptorPath);
            ZipEntry descriptor = zip.getEntry(descriptorPath);
            if (descriptor == null) {
                throw new IllegalStateException("ACP service descriptor is missing: " + descriptorPath);
            }
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

    private static int compareUtf8(String left, String right) {
        byte[] leftBytes = left.getBytes(StandardCharsets.UTF_8);
        byte[] rightBytes = right.getBytes(StandardCharsets.UTF_8);
        int common = Math.min(leftBytes.length, rightBytes.length);
        for (int index = 0; index < common; index++) {
            int compared = Integer.compare(leftBytes[index] & 0xFF, rightBytes[index] & 0xFF);
            if (compared != 0) {
                return compared;
            }
        }
        return Integer.compare(leftBytes.length, rightBytes.length);
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
