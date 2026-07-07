package space.br1440.platform.devtools.opusmcp.audit;

import org.junit.jupiter.api.Test;
import space.br1440.platform.devtools.opusmcp.tool.ReviewCodeTool;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Documents the audit contract for {@code review_code_with_opus}: the metadata-only {@link AuditRecord}
 * carries the tool name but, by construction, has no field able to hold task/code/context/constraints/
 * review output. This complements {@link AuditLoggerTest} and the tool-level guard tests.
 */
class ReviewAuditContractTest {

    @Test
    void reviewAuditRecordIsMetadataOnlyAndTaggedWithToolName() {
        AuditRecord record = AuditRecord.builder()
                .requestId("rid-1")
                .timestamp("2026-01-01T00:00:00Z")
                .toolName(ReviewCodeTool.TOOL_NAME)
                .model("claude-opus-4-8")
                .language("java")
                .outputFormat("structured_review")
                .riskLevel("high")
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
        assertThat(line).contains("tool=review_code_with_opus");
        // Metadata only: no code/context/constraints/review-output content can appear.
        assertThat(line).doesNotContain("private static int add");
        assertThat(line).doesNotContain("api_key");
        assertThat(line).doesNotContain("secret-key-value");
    }
}
