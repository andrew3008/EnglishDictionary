package space.br1440.platform.devtools.opusmcp.docs;

import org.junit.jupiter.api.Test;
import space.br1440.platform.devtools.opusmcp.testsupport.ProjectPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scans human-facing docs and scripts for obvious leaked secrets. The only allowed appearance of a
 * private-key marker is the documented negative-smoke example, which must sit in a file that also
 * explains the refusal behavior.
 */
class DocsSecretHygieneTest {

    private static final String PRIVATE_KEY_MARKER = "-----BEGIN PRIVATE KEY-----";

    private static final List<Pattern> FORBIDDEN = List.of(
            Pattern.compile("sk-[A-Za-z0-9]{16,}"),
            Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b"),
            Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._\\-]{20,}"),
            Pattern.compile("(?i)x-api-key\"?\\s*:\\s*\"[A-Za-z0-9._\\-]{6,}\""),
            Pattern.compile("OPUS_API_KEY\\s*[:=]\\s*\"?[A-Za-z0-9._\\-]{12,}\"?"));

    private List<Path> docFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        Path root = ProjectPaths.projectRoot();
        files.add(root.resolve("README.md"));
        // Phase 6A: build/release files are also part of the secret-hygiene surface.
        for (String buildFile : List.of("build.gradle", "settings.gradle", "gradle.properties",
                "gradle.lockfile")) {
            Path p = root.resolve(buildFile);
            if (Files.exists(p)) {
                files.add(p);
            }
        }
        for (String dir : List.of("docs", "scripts")) {
            Path d = root.resolve(dir);
            if (Files.isDirectory(d)) {
                try (Stream<Path> s = Files.walk(d)) {
                    s.filter(Files::isRegularFile)
                            .filter(p -> {
                                String n = p.getFileName().toString();
                                return n.endsWith(".md") || n.endsWith(".json")
                                        || n.endsWith(".jsonc") || n.endsWith(".ps1");
                            })
                            .forEach(files::add);
                }
            }
        }
        return files;
    }

    @Test
    void docsAndScriptsContainNoObviousSecrets() throws IOException {
        for (Path file : docFiles()) {
            if (!Files.exists(file)) {
                continue;
            }
            String content = Files.readString(file);
            for (Pattern pattern : FORBIDDEN) {
                assertThat(pattern.matcher(content).find())
                        .as("secret-like pattern %s found in %s", pattern.pattern(), file)
                        .isFalse();
            }
        }
    }

    @Test
    void privateKeyMarkerOnlyAppearsAsDocumentedNegativeSmoke() throws IOException {
        for (Path file : docFiles()) {
            if (!Files.exists(file)) {
                continue;
            }
            String content = Files.readString(file);
            if (content.contains(PRIVATE_KEY_MARKER)) {
                boolean documentedAsNegative = content.contains("REFUSED_UNSAFE")
                        || content.toLowerCase().contains("negative")
                        || content.contains("-Context");
                assertThat(documentedAsNegative)
                        .as("private-key marker in %s must be a documented negative smoke", file)
                        .isTrue();
                // And it must never be followed by actual key material on the same line.
                assertThat(content)
                        .as("private-key marker in %s must not be followed by key bytes", file)
                        .doesNotContainPattern(PRIVATE_KEY_MARKER.replace("-", "\\-")
                                + "[A-Za-z0-9+/=]{20,}");
            }
        }
    }
}
