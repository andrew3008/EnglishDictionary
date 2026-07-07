package space.br1440.platform.devtools.opusmcp.testsupport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Test-only helper to locate project files (docs, scripts) regardless of the working directory the
 * test runner uses. Reading project files for validation is NOT MCP repository access — it is local
 * test fixture validation.
 */
public final class ProjectPaths {

    private ProjectPaths() {
    }

    /** Walks up from the current working directory until a directory containing {@code build.gradle}. */
    public static Path projectRoot() {
        Path current = Paths.get("").toAbsolutePath();
        Path candidate = current;
        for (int i = 0; i < 6 && candidate != null; i++) {
            if (Files.exists(candidate.resolve("build.gradle"))
                    && Files.exists(candidate.resolve("settings.gradle"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        return current;
    }

    public static Path resolve(String relative) {
        return projectRoot().resolve(relative);
    }
}
