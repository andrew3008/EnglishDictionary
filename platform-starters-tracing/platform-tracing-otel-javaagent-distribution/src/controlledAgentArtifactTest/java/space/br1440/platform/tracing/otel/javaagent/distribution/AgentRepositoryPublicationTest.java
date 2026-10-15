package space.br1440.platform.tracing.otel.javaagent.distribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class AgentRepositoryPublicationTest {

    private static final String GROUP_PATH = PomContract.GROUP_ID.replace('.', '/');
    private static final List<String> CHECKSUM_SIDECAR_SUFFIXES = List.of(".sha1", ".md5", ".sha256", ".sha512");

    @Test
    void test_repository_contains_only_agent_gav() throws IOException {
        // given
        Path repositoryRoot = AgentArchive.requiredDirectory("platformTracing.testRepository");
        String version = AgentArchive.requiredProperty("platformTracing.distributionVersion");
        ArtifactPaths paths = ArtifactPaths.resolve();
        RepositoryGav agentGav = RepositoryGav.resolve(repositoryRoot, version);

        // when
        Set<String> publishedGavs = listPublishedGavs(repositoryRoot);
        Set<String> agentFiles = listFileNames(agentGav.directory());

        // then
        assertThat(publishedGavs)
                .containsExactly("%s/%s/%s".formatted(GROUP_PATH, PomContract.ARTIFACT_ID, version));
        assertRepositoryLayout(agentFiles, agentGav);
        PomContract.assertMatches(agentGav.pomFile(), version);
        assertRepositoryByteInvariant(paths, agentGav);
    }

    @Test
    void repository_layout_contract_rejects_unexpected_classifier_artifact() {
        // given
        RepositoryGav gav = RepositoryGav.resolve(Path.of("."), "0.0.0");
        Set<String> files = new LinkedHashSet<>(Set.of(
                gav.jarFileName(),
                gav.pomFileName(),
                "%s-0.0.0-all.jar".formatted(PomContract.ARTIFACT_ID)));

        // expect
        assertThatThrownBy(() -> assertRepositoryLayout(files, gav))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("-all.jar");
    }

    private static void assertRepositoryByteInvariant(ArtifactPaths paths, RepositoryGav agentGav) throws IOException {
        byte[] localStandaloneExtensionBytes = Files.readAllBytes(paths.extension());
        byte[] embeddedExtensionBytes = AgentArchive.readNestedEntry(agentGav.jarFile(), AgentArchive.EXTENSION_ENTRY);
        Properties repoAgentIdentity = AgentArchive.loadProperties(
                AgentArchive.readNestedEntry(agentGav.jarFile(), AgentArchive.IDENTITY_ENTRY));

        String localStandaloneSha256 = AgentArchive.sha256Hex(localStandaloneExtensionBytes);
        String embeddedInRepoAgentSha256 = AgentArchive.sha256Hex(embeddedExtensionBytes);

        assertThat(embeddedInRepoAgentSha256)
                .as("embedded %s in repository agent vs standalone extension artifact: embedded=%s standalone=%s",
                        AgentArchive.EXTENSION_ENTRY,
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
        String repoAgentSha256 = AgentArchive.sha256Hex(repoAgentBytes);
        String localAgentSha256 = AgentArchive.sha256Hex(localControlledAgentBytes);
        assertThat(repoAgentSha256)
                .as("repository controlled agent artifact vs local controlled agent artifact: repository=%s local=%s",
                        repoAgentSha256,
                        localAgentSha256)
                .isEqualTo(localAgentSha256);
    }

    private static void assertRepositoryLayout(Set<String> actualFiles, RepositoryGav gav) {
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

    private static boolean isAllowedSidecar(String fileName, Set<String> expectedPrimary) {
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

    private static Set<String> listPublishedGavs(Path repositoryRoot) throws IOException {
        try (Stream<Path> entries = Files.walk(repositoryRoot)) {
            return entries.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".pom"))
                    .map(path -> repositoryRoot.relativize(path.getParent()).toString().replace('\\', '/'))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
    }

    private record RepositoryGav(Path directory, String artifactId, String version) {

        static RepositoryGav resolve(Path repositoryRoot, String version) {
            return new RepositoryGav(
                    repositoryRoot.resolve(GROUP_PATH).resolve(PomContract.ARTIFACT_ID).resolve(version),
                    PomContract.ARTIFACT_ID,
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
