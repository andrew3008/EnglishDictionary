package space.br1440.platform.devtools.opusmcp.audit;

import org.junit.jupiter.api.Test;
import space.br1440.platform.devtools.opusmcp.tool.ReviewTestsTool;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Documents the audit contract for {@code review_tests_with_opus}: the metadata-only
 * {@link AuditRecord} carries the tool name but, by construction, has no field able to hold
 * task/testCode/productionContext/testIntent/failureLogs/dependenciesContext/constraints/review/
 * findings/suggestedTestCases or the raw model output.
 */
class ReviewTestsAuditContractTest {

    @Test
    void reviewTestsAuditRecordIsMetadataOnlyAndTaggedWithToolName() {
        AuditRecord record = AuditRecord.builder()
                .requestId("rid-1")
                .timestamp("2026-01-01T00:00:00Z")
                .toolName(ReviewTestsTool.TOOL_NAME)
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
        assertThat(line).contains("tool=review_tests_with_opus");
        // Metadata only: no test code / review / secrets can appear.
        assertThat(line).doesNotContain("assertTrue");
        assertThat(line).doesNotContain("@Test");
        assertThat(line).doesNotContain("api_key");
        assertThat(line).doesNotContain("secret-key-value");
    }
}
