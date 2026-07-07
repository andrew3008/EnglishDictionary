package space.br1440.platform.devtools.opusmcp.audit;

import org.junit.jupiter.api.Test;
import space.br1440.platform.devtools.opusmcp.tool.ReviewGradleBuildTool;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Documents the audit contract for {@code review_gradle_build_with_opus}: the metadata-only
 * {@link AuditRecord} carries the tool name but, by construction, has no field able to hold
 * task/buildFilesContext/settingsContext/versionCatalogContext/gradlePropertiesContext/
 * buildLogicContext/dependencyContext/buildFailureLogs/constraints/review/findings/suggestedChanges
 * or the raw model output.
 */
class ReviewGradleBuildAuditContractTest {

    @Test
    void reviewGradleBuildAuditRecordIsMetadataOnlyAndTaggedWithToolName() {
        AuditRecord record = AuditRecord.builder()
                .requestId("rid-1")
                .timestamp("2026-01-01T00:00:00Z")
                .toolName(ReviewGradleBuildTool.TOOL_NAME)
                .model("claude-opus-4-8")
                .outputFormat("structured_review")
                .riskLevel("medium")
                .status("OK")
                .latencyMs(5)
                .inputCharCount(42)
                .estimatedInputTokens(11)
                .estimatedOutputTokens(7)
                .estimatedCost(0d)
                .budgetDecision("allowed")
                .rateLimitDecision("allowed")
                .httpStatusCategory("2xx")
                .build();

        String line = record.toLogString();
        assertThat(line).contains("tool=review_gradle_build_with_opus");
        // Metadata only: no build snippets / review / secrets can appear.
        assertThat(line).doesNotContain("java-library");
        assertThat(line).doesNotContain("implementation");
        assertThat(line).doesNotContain("password");
        assertThat(line).doesNotContain("secret-key-value");
    }
}
