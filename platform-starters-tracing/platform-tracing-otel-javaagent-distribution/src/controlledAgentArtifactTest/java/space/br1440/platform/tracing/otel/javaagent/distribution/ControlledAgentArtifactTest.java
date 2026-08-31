package space.br1440.platform.tracing.otel.javaagent.distribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;

class ControlledAgentArtifactTest {

    private static final String EXTENSION_ENTRY = "extensions/platform-tracing-otel-javaagent-extension.jar";
    private static final String IDENTITY_ENTRY = "META-INF/platform-tracing/javaagent-distribution.properties";
    private static final String MANIFEST_ENTRY = "META-INF/MANIFEST.MF";
    private static final String ACP_PIN_RESOURCE = "META-INF/platform-tracing/otel-javaagent-acp-pin.properties";
    private static final String SCHEMA_IDENTITY = "META-INF/platform-tracing/agent-logging-schema.properties";
    private static final String THIRD_PARTY_NOTICES_ENTRY = "META-INF/platform-tracing/THIRD-PARTY-NOTICES.txt";
    private static final String LOG_EVENT_SCHEMA_RESOURCE = "platform-log-event.schema.json";
    private static final String ACP_PIN_PROVIDERS_CLASS =
            "space/br1440/platform/tracing/otel/javaagent/bootstrap/"
                    + "AllowedUpstreamAcpProviders.class";

    private static final String EXTENSION_IMPLEMENTATION_TITLE = "platform-tracing-otel-javaagent-extension";
    private static final String OTEL_JAVAAGENT_COORDINATE = "io.opentelemetry.javaagent:opentelemetry-javaagent:2.30.0";
    private static final String EXPECTED_PIN_FORMAT_VERSION = "1";

    private static final LocalDateTime CANONICAL_TIMESTAMP =
            LocalDateTime.of(1980, 2, 1, 0, 0, 0);

    private static final List<String> FORBIDDEN_EXTENSION_PREFIXES = List.of(
            "org/springframework/",
            "org/slf4j/",
            "ch/qos/logback/",
            "io/opentelemetry/api/",
            "io/opentelemetry/context/",
            "io/opentelemetry/common/",
            "space/br1440/platform/logging/",
            "space/br1440/platform/tracing/otel/runtime/",
            "space/br1440/platform/tracing/autoconfigure/"
    );

    @Test
    void published_agent_is_otel_payload_plus_one_extension_and_identity() throws IOException {
        // given
        ArtifactPaths paths = ArtifactPaths.resolve();

        // when
        Map<String, byte[]> otelEntries = readEntries(paths.otel());
        Map<String, byte[]> published = readEntries(paths.agent());

        // then
        assertOnlyDirectoriesAreExtraEntries(published, otelEntries);
        assertOtelPayloadIsUnmodified(published, otelEntries);
        assertThat(published.get(EXTENSION_ENTRY)).isEqualTo(Files.readAllBytes(paths.extension()));
        assertThat(published.get(IDENTITY_ENTRY)).isEqualTo(expectedIdentity(paths));
        assertThat(published).doesNotContainKey(THIRD_PARTY_NOTICES_ENTRY);
    }

    private void assertOnlyDirectoriesAreExtraEntries(Map<String, byte[]> published,
                                                      Map<String, byte[]> otelEntries) {
        Set<String> extra = new LinkedHashSet<>(published.keySet());
        extra.removeAll(otelEntries.keySet());
        extra.remove(EXTENSION_ENTRY);
        extra.remove(IDENTITY_ENTRY);

        assertThat(extra).allMatch(name -> name.endsWith("/"));
    }

    private void assertOtelPayloadIsUnmodified(Map<String, byte[]> published,
                                               Map<String, byte[]> otelEntries) {
        otelEntries.forEach((name, payload) ->
                assertThat(published.get(name)).as(name).isEqualTo(payload));
    }

    @Test
    void packaged_extension_stays_on_the_allowlisted_surface() throws IOException {
        // given
        Path extension = requiredPath("platformTracing.extensionJar");
        Map<String, byte[]> entries = readEntries(extension);
        String version = requiredProperty("platformTracing.distributionVersion");
        Manifest manifest = new Manifest(new ByteArrayInputStream(entries.get(MANIFEST_ENTRY)));

        // when
        Properties schema = loadProperties(entries.get(SCHEMA_IDENTITY));

        // then
        assertExtensionSurfaceIsAllowlisted(entries.keySet());
        assertExtensionManifestMatches(manifest, version, schema);
    }

    private void assertExtensionSurfaceIsAllowlisted(Set<String> entryNames) {
        assertThat(entryNames)
                .noneMatch(name -> FORBIDDEN_EXTENSION_PREFIXES.stream().anyMatch(name::startsWith))
                .contains(ACP_PIN_PROVIDERS_CLASS, ACP_PIN_RESOURCE, SCHEMA_IDENTITY)
                .doesNotContain(LOG_EVENT_SCHEMA_RESOURCE);
    }

    private void assertExtensionManifestMatches(Manifest manifest, String version, Properties schema) {
        assertThat(manifest.getMainAttributes().getValue("Implementation-Title"))
                .isEqualTo(EXTENSION_IMPLEMENTATION_TITLE);

        assertThat(manifest.getMainAttributes().getValue("Implementation-Version"))
                .isEqualTo(version);

        assertThat(manifest.getMainAttributes().getValue("Platform-Log-Schema-SHA256"))
                .isEqualTo(schema.getProperty("schema.sha256"));
    }

    @Test
    void pin_matches_the_resolved_otel_java_agent() throws IOException {
        // given
        Path otel = requiredPath("platformTracing.otelJavaAgentJar");

        // when
        Properties pin = loadProperties(
                readNestedEntry(
                        requiredPath("platformTracing.extensionJar"),
                        ACP_PIN_RESOURCE
                )
        );

        // then
        assertThat(pin.getProperty("format.version")).isEqualTo(EXPECTED_PIN_FORMAT_VERSION);
        assertThat(pin.getProperty("otel.javaagent.sha256"))
                .isEqualTo(sha256Hex(Files.readAllBytes(otel)));
    }

    @Test
    void archive_uses_the_gradle_reproducible_profile() throws IOException {
        Path agent = requiredPath("platformTracing.controlledAgentJar");
        try (ZipFile zip = new ZipFile(agent.toFile(), StandardCharsets.UTF_8)) {
            assertThat(zip.getComment()).isNull();

            for (ZipEntry entry : zipEntries(zip)) {
                assertReproducibleEntry(entry);
            }
        }
    }

    private void assertReproducibleEntry(ZipEntry entry) {
        assertThat(entry.getTimeLocal()).as(entry.getName()).isEqualTo(CANONICAL_TIMESTAMP);
        assertThat(entry.getComment()).as(entry.getName()).isNull();
        assertThat(entry.getExtra()).as(entry.getName()).isNull();

        if (!entry.isDirectory()) {
            assertThat(entry.getMethod()).as(entry.getName()).isEqualTo(ZipEntry.DEFLATED);
        }
    }

    private static List<ZipEntry> zipEntries(ZipFile zip) {
        List<ZipEntry> result = new ArrayList<>();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            result.add(entries.nextElement());
        }

        return result;
    }

    private static byte[] expectedIdentity(ArtifactPaths paths) throws IOException {
        String version = requiredProperty("platformTracing.distributionVersion");
        String extensionSha256 = sha256Hex(Files.readAllBytes(paths.extension()));
        String otelSha256 = sha256Hex(Files.readAllBytes(paths.otel()));

        String content = """
                distribution.artifact=platform-tracing-javaagent
                distribution.group=space.br1440.platform.starters.tracing
                distribution.version=%s
                extension.path=%s
                extension.sha256=%s
                format.version=1
                minimum.java=21
                upstream.coordinate=%s
                upstream.sha256=%s
                """.formatted(
                        version, EXTENSION_ENTRY, extensionSha256,
                        OTEL_JAVAAGENT_COORDINATE, otelSha256
                );

        return content.getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, byte[]> readEntries(Path archive) throws IOException {
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            for (ZipEntry entry : zipEntries(zip)) {
                try (InputStream input = zip.getInputStream(entry)) {
                    result.put(entry.getName(), input.readAllBytes());
                }
            }
        }

        return result;
    }

    @SuppressWarnings("SameParameterValue")
    private static byte[] readNestedEntry(Path archive, String name) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(
                Files.newInputStream(archive), StandardCharsets.UTF_8)
        ) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (name.equals(entry.getName())) {
                    return zip.readAllBytes();
                }
            }
        }

        throw new IllegalStateException("Required ZIP entry is missing: %s".formatted(name));
    }

    private static Properties loadProperties(byte[] bytes) throws IOException {
        Properties properties = new Properties();
        properties.load(new ByteArrayInputStream(bytes));
        return properties;
    }

    private static Path requiredPath(String name) {
        Path path = Path.of(requiredProperty(name));
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Required test file is missing: %s".formatted(path));
        }

        return path;
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Required test system property is missing: %s".formatted(name)
            );
        }

        return value;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append("%02x".formatted(value & 0xFF));
            }

            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private record ArtifactPaths(Path agent, Path otel, Path extension) {
        static ArtifactPaths resolve() {
            return new ArtifactPaths(
                    requiredPath("platformTracing.controlledAgentJar"),
                    requiredPath("platformTracing.otelJavaAgentJar"),
                    requiredPath("platformTracing.extensionJar")
            );
        }
    }
}
