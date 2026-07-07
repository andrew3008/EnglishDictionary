package space.br1440.platform.devtools.opusmcp.audit;

import org.junit.jupiter.api.Test;
import space.br1440.platform.devtools.opusmcp.tool.ReviewMdxDocTool;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Documents the audit contract for {@code review_mdx_doc_with_opus}: the metadata-only
 * {@link AuditRecord} carries the tool name but, by construction, has no field able to hold
 * task/mdxContent/docSubject/libraryContext/styleGuideContext/mdxComponentsContext/constraints/
 * review/findings/suggestedEdits or the raw model output.
 */
class ReviewMdxDocAuditContractTest {

    @Test
    void reviewMdxDocAuditRecordIsMetadataOnlyAndTaggedWithToolName() {
        AuditRecord record = AuditRecord.builder()
                .requestId("rid-1")
                .timestamp("2026-01-01T00:00:00Z")
                .toolName(ReviewMdxDocTool.TOOL_NAME)
                .model("claude-opus-4-8")
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
        assertThat(line).contains("tool=review_mdx_doc_with_opus");
        // Metadata only: no doc content / findings / suggested edits / secrets can appear.
        assertThat(line).doesNotContain("tracing.enabled");
        assertThat(line).doesNotContain("@theme/Tabs");
        assertThat(line).doesNotContain("api_key");
        assertThat(line).doesNotContain("secret-key-value");
    }
}
