package space.br1440.platform.tracing.otel.javaagent.distribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

class ControlledAgentArtifactTest {

    private static final String EXTENSION_ENTRY = "extensions/platform-tracing-otel-javaagent-extension.jar";
    private static final String IDENTITY_ENTRY = "META-INF/platform-tracing/javaagent-distribution.properties";
    private static final String MANIFEST_ENTRY = "META-INF/MANIFEST.MF";
    private static final String ACP_PIN_RESOURCE = "META-INF/platform-tracing/otel-javaagent-acp-pin.properties";
    private static final String SCHEMA_IDENTITY = "META-INF/platform-tracing/agent-logging-schema.properties";
    private static final String THIRD_PARTY_NOTICES_ENTRY = "META-INF/platform-tracing/THIRD-PARTY-NOTICES.txt";
    private static final String LOG_EVENT_SCHEMA_RESOURCE = "platform-log-event.schema.json";
    private static final String ACP_PIN_PROVIDERS_CLASS =
            "space/br1440/platform/tracing/otel/javaagent/bootstrap/verification/"
                    + "UpstreamCustomizerProviderAllowlist.class";

    private static final String EXTENSION_IMPLEMENTATION_TITLE = "platform-tracing-otel-javaagent-extension";
    private static final String OTEL_JAVAAGENT_COORDINATE = "io.opentelemetry.javaagent:opentelemetry-javaagent:2.30.0";
    private static final String EXPECTED_PIN_FORMAT_VERSION = "1";

    private static final LocalDateTime CANONICAL_TIMESTAMP = LocalDateTime.of(1980, 2, 1, 0, 0, 0);

    private static final String EXPECTED_GROUP_ID = "space.br1440.platform.starters.tracing";
    private static final String GROUP_PATH = "space/br1440/platform/starters/tracing";
    private static final String AGENT_ARTIFACT_ID = "platform-tracing-javaagent";
    private static final String EXTENSION_ARTIFACT_ID = "platform-tracing-otel-javaagent-extension";
    private static final String AGENT_POM_NAME = "Platform Tracing Controlled OpenTelemetry Java Agent";
    private static final String AGENT_POM_DESCRIPTION = "Controlled OpenTelemetry Java agent distribution with the platform tracing extension embedded";
    private static final String EXTENSION_POM_NAME = "Platform Tracing OTel Java agent extension";
    private static final String EXTENSION_POM_DESCRIPTION = "Standalone platform OpenTelemetry Java agent extension artifact";
    private static final List<String> CHECKSUM_SIDECAR_SUFFIXES = List.of(".sha1", ".md5", ".sha256", ".sha512");

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

    @Test
    void embedded_extension_matches_standalone_artifact_by_sha256() throws IOException {
        // given
        ArtifactPaths paths = ArtifactPaths.resolve();

        // when
        String standaloneExtensionSha256 = sha256Hex(Files.readAllBytes(paths.extension()));
        String embeddedExtensionSha256 = sha256Hex(readNestedEntry(paths.agent(), EXTENSION_ENTRY));

        // then
        assertThat(embeddedExtensionSha256)
                .as("embedded %s vs standalone extension artifact: embedded=%s standalone=%s",
                        EXTENSION_ENTRY,
                        embeddedExtensionSha256,
                        standaloneExtensionSha256)
                .isEqualTo(standaloneExtensionSha256);
    }

    @Test
    void test_repository_contains_matching_extension_and_agent_artifacts() throws IOException {
        // given
        Path repositoryRoot = requiredDirectory("platformTracing.testRepository");
        String version = requiredProperty("platformTracing.distributionVersion");
        ArtifactPaths paths = ArtifactPaths.resolve();

        RepositoryGav agentGav = RepositoryGav.resolve(repositoryRoot, AGENT_ARTIFACT_ID, version);
        RepositoryGav extensionGav = RepositoryGav.resolve(repositoryRoot, EXTENSION_ARTIFACT_ID, version);

        // when
        Set<String> agentFiles = listFileNames(agentGav.directory());
        Set<String> extensionFiles = listFileNames(extensionGav.directory());

        // then
        assertRepositoryLayout(agentFiles, agentGav);
        assertRepositoryLayout(extensionFiles, extensionGav);

        assertPomContract(agentGav.pomFile(), AGENT_ARTIFACT_ID, version, AGENT_POM_NAME, AGENT_POM_DESCRIPTION);
        assertPomContract(
                extensionGav.pomFile(), EXTENSION_ARTIFACT_ID, version, EXTENSION_POM_NAME, EXTENSION_POM_DESCRIPTION);

        assertRepositoryByteInvariant(paths, agentGav, extensionGav);
    }

    @Test
    void pom_contract_accepts_project_name_preceded_by_organization_name(@TempDir Path directory) throws IOException {
        // given
        Path pomFile = writePomFixture(directory, "organization-first.pom", """
                  <organization><name>Other Organization</name></organization>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  <name>%s</name>
                  <description>%s</description>
                """.formatted(
                EXPECTED_GROUP_ID, AGENT_ARTIFACT_ID, "0.0.0", AGENT_POM_NAME, AGENT_POM_DESCRIPTION));

        // expect
        assertPomContract(pomFile, AGENT_ARTIFACT_ID, "0.0.0", AGENT_POM_NAME, AGENT_POM_DESCRIPTION);
    }

    @Test
    void pom_contract_rejects_organization_name_in_place_of_project_name(@TempDir Path directory) throws IOException {
        // given
        Path pomFile = writePomFixture(directory, "missing-project-name.pom", """
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  <description>%s</description>
                  <organization><name>%s</name></organization>
                """.formatted(
                EXPECTED_GROUP_ID, AGENT_ARTIFACT_ID, "0.0.0", AGENT_POM_DESCRIPTION, AGENT_POM_NAME));

        // expect
        assertThatThrownBy(() -> assertPomContract(
                pomFile, AGENT_ARTIFACT_ID, "0.0.0", AGENT_POM_NAME, AGENT_POM_DESCRIPTION))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("project/name");
    }

    @Test
    void pom_contract_rejects_duplicate_project_name(@TempDir Path directory) throws IOException {
        // given
        Path pomFile = writePomFixture(directory, "duplicate-project-name.pom", """
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  <name>%s</name>
                  <name>Second Declared Name</name>
                  <description>%s</description>
                """.formatted(
                EXPECTED_GROUP_ID, AGENT_ARTIFACT_ID, "0.0.0", AGENT_POM_NAME, AGENT_POM_DESCRIPTION));

        // expect
        assertThatThrownBy(() -> assertPomContract(
                pomFile, AGENT_ARTIFACT_ID, "0.0.0", AGENT_POM_NAME, AGENT_POM_DESCRIPTION))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate POM element: project/name");
    }

    @Test
    void pom_contract_rejects_dependencies_declared_inside_profile(@TempDir Path directory) throws IOException {
        // given
        Path pomFile = writePomFixture(directory, "profile-dependencies.pom", """
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  <name>%s</name>
                  <description>%s</description>
                  <profiles>
                    <profile>
                      <id>extra</id>
                      <dependencies>
                        <dependency>
                          <groupId>org.example</groupId>
                          <artifactId>example</artifactId>
                          <version>1.0.0</version>
                        </dependency>
                      </dependencies>
                    </profile>
                  </profiles>
                """.formatted(
                EXPECTED_GROUP_ID, AGENT_ARTIFACT_ID, "0.0.0", AGENT_POM_NAME, AGENT_POM_DESCRIPTION));

        // expect
        assertThatThrownBy(() -> assertPomContract(
                pomFile, AGENT_ARTIFACT_ID, "0.0.0", AGENT_POM_NAME, AGENT_POM_DESCRIPTION))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("dependencies");
    }

    @Test
    void repository_layout_contract_rejects_unexpected_classifier_artifact() {
        // given
        RepositoryGav gav = RepositoryGav.resolve(Path.of("."), EXTENSION_ARTIFACT_ID, "0.0.0");
        Set<String> files = new LinkedHashSet<>(Set.of(
                gav.jarFileName(),
                gav.pomFileName(),
                "%s-0.0.0-all.jar".formatted(EXTENSION_ARTIFACT_ID)));

        // expect
        assertThatThrownBy(() -> assertRepositoryLayout(files, gav))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("-all.jar");
    }

    private void assertRepositoryByteInvariant(ArtifactPaths paths, RepositoryGav agentGav, RepositoryGav extensionGav)
            throws IOException {
        byte[] repoExtensionBytes = Files.readAllBytes(extensionGav.jarFile());
        byte[] localStandaloneExtensionBytes = Files.readAllBytes(paths.extension());
        byte[] embeddedExtensionBytes = readNestedEntry(agentGav.jarFile(), EXTENSION_ENTRY);
        Properties repoAgentIdentity = loadProperties(readNestedEntry(agentGav.jarFile(), IDENTITY_ENTRY));

        String repoExtensionSha256 = sha256Hex(repoExtensionBytes);
        String localStandaloneSha256 = sha256Hex(localStandaloneExtensionBytes);
        String embeddedInRepoAgentSha256 = sha256Hex(embeddedExtensionBytes);

        assertThat(repoExtensionSha256)
                .as("repository extension artifact vs standalone extension artifact: repository=%s standalone=%s",
                        repoExtensionSha256,
                        localStandaloneSha256)
                .isEqualTo(localStandaloneSha256);

        assertThat(embeddedInRepoAgentSha256)
                .as("embedded %s in repository agent vs standalone extension artifact: embedded=%s standalone=%s",
                        EXTENSION_ENTRY,
                        embeddedInRepoAgentSha256,
                        localStandaloneSha256)
                .isEqualTo(localStandaloneSha256);

        assertThat(repoAgentIdentity.getProperty("extension.sha256"))
                .as("published repository agent identity extension.sha256 vs standalone extension artifact: identity=%s standalone=%s",
                        repoAgentIdentity.getProperty("extension.sha256"),
                        localStandaloneSha256)
                .isEqualTo(localStandaloneSha256);

        byte[] repoAgentBytes = Files.readAllBytes(agentGav.jarFile());
        byte[] localControlledAgentBytes = Files.readAllBytes(paths.agent());
        String repoAgentSha256 = sha256Hex(repoAgentBytes);
        String localAgentSha256 = sha256Hex(localControlledAgentBytes);
        assertThat(repoAgentSha256)
                .as("repository controlled agent artifact vs local controlled agent artifact: repository=%s local=%s",
                        repoAgentSha256,
                        localAgentSha256)
                .isEqualTo(localAgentSha256);
    }

    private void assertRepositoryLayout(Set<String> actualFiles, RepositoryGav gav) {
        Set<String> expectedPrimary = Set.of(gav.jarFileName(), gav.pomFileName());
        Set<String> unexpected = new LinkedHashSet<>();
        for (String actual : actualFiles) {
            if (!expectedPrimary.contains(actual) && !isAllowedSidecar(actual, expectedPrimary)) {
                unexpected.add(actual);
            }
        }

        assertThat(unexpected)
                .as("unexpected files in GAV %s:%s", gav.artifactId(), gav.version())
                .isEmpty();
        assertThat(actualFiles)
                .as("primary artifacts in GAV %s:%s", gav.artifactId(), gav.version())
                .containsAll(expectedPrimary);
    }

    private boolean isAllowedSidecar(String fileName, Set<String> expectedPrimary) {
        if (fileName.startsWith("maven-metadata")) {
            return true;
        }

        for (String suffix : CHECKSUM_SIDECAR_SUFFIXES) {
            if (fileName.endsWith(suffix)) {
                String stem = fileName.substring(0, fileName.length() - suffix.length());
                return expectedPrimary.contains(stem) || stem.startsWith("maven-metadata");
            }
        }

        return false;
    }

    private void assertPomContract(Path pomFile,
                                   String expectedArtifactId,
                                   String expectedVersion,
                                   String expectedName,
                                   String expectedDescription) throws IOException {
        Element root = parseXml(pomFile).getDocumentElement();

        assertThat(requiredElementText(root, "groupId")).as(pomFile.toString()).isEqualTo(EXPECTED_GROUP_ID);
        assertThat(requiredElementText(root, "artifactId")).as(pomFile.toString()).isEqualTo(expectedArtifactId);
        assertThat(requiredElementText(root, "version")).as(pomFile.toString()).isEqualTo(expectedVersion);
        assertThat(optionalElementText(root, "packaging")).as(pomFile.toString()).isIn(null, "jar");
        assertThat(requiredElementText(root, "name")).as(pomFile.toString()).isEqualTo(expectedName);
        assertThat(requiredElementText(root, "description")).as(pomFile.toString()).isEqualTo(expectedDescription);

        assertThat(root.getElementsByTagName("dependencies").getLength())
                .as("%s: dependencies anywhere in the document", pomFile)
                .isZero();
        assertThat(root.getElementsByTagName("dependencyManagement").getLength())
                .as("%s: dependencyManagement anywhere in the document", pomFile)
                .isZero();
    }

    private static Path writePomFixture(Path directory, String fileName, String projectFields) throws IOException {
        Path pomFile = directory.resolve(fileName);
        Files.writeString(pomFile, """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                %s</project>
                """.formatted(projectFields), StandardCharsets.UTF_8);

        return pomFile;
    }

    private static Document parseXml(Path file) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(file.toFile());
        } catch (ParserConfigurationException | SAXException exception) {
            throw new IllegalStateException("Failed to parse POM: %s".formatted(file), exception);
        }
    }

    private static String requiredElementText(Element parent, String tagName) {
        String value = optionalElementText(parent, tagName);
        if (value == null) {
            throw new IllegalStateException("Required POM element is missing: project/%s".formatted(tagName));
        }

        return value;
    }

    private static String optionalElementText(Element parent, String tagName) {
        Element found = null;
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && tagName.equals(element.getTagName())) {
                if (found != null) {
                    throw new IllegalStateException("Duplicate POM element: project/%s".formatted(tagName));
                }

                found = element;
            }
        }

        return (found == null) ? null : found.getTextContent().trim();
    }

    private static Set<String> listFileNames(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            throw new IllegalStateException("Required repository GAV directory is missing: %s".formatted(directory));
        }

        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
    }

    private static Path requiredDirectory(String name) {
        Path path = Path.of(requiredProperty(name));
        if (!Files.isDirectory(path)) {
            throw new IllegalStateException("Required test directory is missing: %s".formatted(path));
        }

        return path;
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

    private record RepositoryGav(Path directory, String artifactId, String version) {
        static RepositoryGav resolve(Path repositoryRoot, String artifactId, String version) {
            return new RepositoryGav(
                    repositoryRoot.resolve(GROUP_PATH).resolve(artifactId).resolve(version),
                    artifactId,
                    version
            );
        }

        String jarFileName() {
            return "%s-%s.jar".formatted(artifactId, version);
        }

        String pomFileName() {
            return "%s-%s.pom".formatted(artifactId, version);
        }

        Path jarFile() {
            return directory.resolve(jarFileName());
        }

        Path pomFile() {
            return directory.resolve(pomFileName());
        }
    }
}
