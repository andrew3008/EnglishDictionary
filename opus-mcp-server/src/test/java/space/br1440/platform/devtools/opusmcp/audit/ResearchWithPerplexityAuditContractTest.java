package space.br1440.platform.devtools.opusmcp.audit;

import org.junit.jupiter.api.Test;
import space.br1440.platform.devtools.opusmcp.tool.ResearchWithPerplexityTool;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Documents the audit contract for {@code research_with_perplexity}: the metadata-only
 * {@link AuditRecord} carries the tool name, status, request id, model, token estimates, and the
 * provider diagnostic category, but by construction has no field able to hold
 * task/researchQuestion/context/constraints/answer/sources/raw provider response.
 */
class ResearchWithPerplexityAuditContractTest {

    @Test
    void researchAuditRecordIsMetadataOnlyAndTaggedWithToolName() {
        AuditRecord record = AuditRecord.builder()
                .requestId("rid-1")
                .timestamp("2026-01-01T00:00:00Z")
                .toolName(ResearchWithPerplexityTool.TOOL_NAME)
                .model("sonar-deep-research")
                .outputFormat("report")
                .riskLevel("low")
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
        assertThat(line).contains("tool=research_with_perplexity");
        assertThat(line).contains("status=OK");
        assertThat(line).contains("model=sonar-deep-research");
        // Metadata only: no question/context/constraints/answer/sources content can appear.
        assertThat(line).doesNotContain("What is");
        assertThat(line).doesNotContain("https://");
        assertThat(line).doesNotContain("PERPLEXITY_API_KEY");
        assertThat(line).doesNotContain("secret-key-value");
    }
}
