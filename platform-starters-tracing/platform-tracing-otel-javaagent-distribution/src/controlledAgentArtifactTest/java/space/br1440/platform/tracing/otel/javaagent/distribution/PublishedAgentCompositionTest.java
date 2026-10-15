package space.br1440.platform.tracing.otel.javaagent.distribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class PublishedAgentCompositionTest {

    private static final String THIRD_PARTY_NOTICES_ENTRY = "META-INF/platform-tracing/THIRD-PARTY-NOTICES.txt";
    private static final String OTEL_JAVAAGENT_COORDINATE = "io.opentelemetry.javaagent:opentelemetry-javaagent:2.30.0";

    @Test
    void published_agent_is_otel_payload_plus_one_extension_and_identity() throws IOException {
        // given
        ArtifactPaths paths = ArtifactPaths.resolve();

        // when
        Map<String, byte[]> otelEntries = AgentArchive.readEntries(paths.otel());
        Map<String, byte[]> published = AgentArchive.readEntries(paths.agent());

        // then
        assertOnlyDirectoriesAreExtraEntries(published, otelEntries);
        assertOtelPayloadIsUnmodified(published, otelEntries);
        assertThat(published.get(AgentArchive.EXTENSION_ENTRY)).isEqualTo(Files.readAllBytes(paths.extension()));
        assertThat(published.get(AgentArchive.IDENTITY_ENTRY)).isEqualTo(expectedIdentity(paths));
        assertThat(published).doesNotContainKey(THIRD_PARTY_NOTICES_ENTRY);
    }

    @Test
    void embedded_extension_matches_standalone_artifact_by_sha256() throws IOException {
        // given
        ArtifactPaths paths = ArtifactPaths.resolve();

        // when
        String standaloneExtensionSha256 = AgentArchive.sha256Hex(Files.readAllBytes(paths.extension()));
        String embeddedExtensionSha256 = AgentArchive.sha256Hex(
                AgentArchive.readNestedEntry(paths.agent(), AgentArchive.EXTENSION_ENTRY));

        // then
        assertThat(embeddedExtensionSha256)
                .as("embedded %s vs standalone extension artifact: embedded=%s standalone=%s",
                        AgentArchive.EXTENSION_ENTRY,
                        embeddedExtensionSha256,
                        standaloneExtensionSha256)
                .isEqualTo(standaloneExtensionSha256);
    }

    private static void assertOnlyDirectoriesAreExtraEntries(Map<String, byte[]> published,
                                                             Map<String, byte[]> otelEntries) {
        Set<String> extra = new LinkedHashSet<>(published.keySet());
        extra.removeAll(otelEntries.keySet());
        extra.remove(AgentArchive.EXTENSION_ENTRY);
        extra.remove(AgentArchive.IDENTITY_ENTRY);

        assertThat(extra).allMatch(name -> name.endsWith("/"));
    }

    private static void assertOtelPayloadIsUnmodified(Map<String, byte[]> published,
                                                      Map<String, byte[]> otelEntries) {
        otelEntries.forEach((name, payload) ->
                assertThat(published.get(name)).as(name).isEqualTo(payload));
    }

    private static byte[] expectedIdentity(ArtifactPaths paths) throws IOException {
        String version = AgentArchive.requiredProperty("platformTracing.distributionVersion");
        String extensionSha256 = AgentArchive.sha256Hex(Files.readAllBytes(paths.extension()));
        String otelSha256 = AgentArchive.sha256Hex(Files.readAllBytes(paths.otel()));

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
                version,
                AgentArchive.EXTENSION_ENTRY,
                extensionSha256,
                OTEL_JAVAAGENT_COORDINATE,
                otelSha256
        );

        return content.getBytes(StandardCharsets.UTF_8);
    }
}
