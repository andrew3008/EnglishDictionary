package space.br1440.platform.devtools.opusmcp.docs;

import org.junit.jupiter.api.Test;
import space.br1440.platform.devtools.opusmcp.testsupport.ProjectPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Static (OS-independent) validation of the provider-health smoke script: ensures it offers help,
 * supports multi-model probing, sends only the synthetic prompt, prints safe headers + diagnostic
 * categories, and never prints secrets. Does not execute PowerShell.
 */
class ProviderHealthScriptTest {

    private String script() throws IOException {
        Path path = ProjectPaths.resolve("scripts/smoke-provider-health.ps1");
        assertThat(Files.exists(path)).as("provider-health smoke script must exist").isTrue();
        return Files.readString(path);
    }

    @Test
    void supportsHelpSwitch() throws IOException {
        String content = script();
        assertThat(content).contains("[switch]$Help");
        assertThat(content).contains("if ($Help)");
    }

    @Test
    void supportsMultiModelArgument() throws IOException {
        String content = script();
        assertThat(content).contains("[string]$Models");
        assertThat(content).contains("claude-opus-4-8");
        assertThat(content).contains("OPUS_MODEL");
    }

    @Test
    void sendsOnlySyntheticPrompt() throws IOException {
        String content = script();
        assertThat(content).contains("Reply with exactly: OK");
        assertThat(content.toLowerCase()).contains("no repository context");
    }

    @Test
    void printsSafeHeadersAndCategory() throws IOException {
        String content = script();
        assertThat(content).contains("diagnosticCategory");
        assertThat(content).contains("PROVIDER_DOWN");
        assertThat(content).contains("CF-RAY");
        assertThat(content).contains("Retry-After");
        assertThat(content).contains("statusDescription");
        assertThat(content).contains("errorBodyPreview");
    }

    @Test
    void neverPrintsApiKeyOrAuthorizationHeader() throws IOException {
        String content = script();
        // The key is read from env and redacted; it must never be echoed in output.
        assertThat(content).doesNotContain("Write-Output \"x-api-key=");
        assertThat(content).doesNotContain("Write-Output \"Authorization=");
        assertThat(content).doesNotContain("OPUS_API_KEY=\"sk-");
        // Masking affordance present.
        assertThat(content).contains("[REDACTED]");
    }

    @Test
    void truncatesBodyPreview() throws IOException {
        String content = script();
        assertThat(content).contains("...[truncated]");
        assertThat(content).contains("<empty>");
    }
}
