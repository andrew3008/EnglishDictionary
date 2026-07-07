package space.br1440.platform.devtools.opusmcp.docs;

import org.junit.jupiter.api.Test;
import space.br1440.platform.devtools.opusmcp.testsupport.ProjectPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Static (OS-independent) validation of the generation smoke script: ensures the documented help and
 * safety affordances exist without executing PowerShell (which is brittle in CI).
 */
class SmokeScriptHelpTest {

    private String script() throws IOException {
        Path path = ProjectPaths.resolve("scripts/smoke-generate-code.ps1");
        assertThat(Files.exists(path)).as("smoke script must exist").isTrue();
        return Files.readString(path);
    }

    @Test
    void supportsHelpSwitch() throws IOException {
        String content = script();
        assertThat(content).contains("[switch]$Help");
        assertThat(content).contains("if ($Help)");
    }

    @Test
    void helpExplainsNoRepositoryContextIsSent() throws IOException {
        assertThat(script().toLowerCase()).contains("no repository context is sent");
    }

    @Test
    void supportsNegativeSmokeContexts() throws IOException {
        String content = script();
        assertThat(content).contains("-Context");
        assertThat(content).contains("REFUSED_UNSAFE");
    }

    @Test
    void retainsUtf8ConsoleSetup() throws IOException {
        String content = script();
        assertThat(content).contains("UTF8Encoding");
        assertThat(content).contains("chcp 65001");
    }

    @Test
    void helpDoesNotPrintApiKeyValue() throws IOException {
        assertThat(script()).doesNotContain("OPUS_API_KEY=\"sk-");
    }
}
