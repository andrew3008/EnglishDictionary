package space.br1440.platform.devtools.opusmcp.audit;

import org.junit.jupiter.api.Test;
import space.br1440.platform.devtools.opusmcp.tool.RefactorPlanTool;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Documents the audit contract for {@code refactor_plan_with_opus}: the metadata-only
 * {@link AuditRecord} carries the tool name but, by construction, has no field able to hold
 * task/code/context/constraints/refactor-plan output.
 */
class RefactorPlanAuditContractTest {

    @Test
    void refactorPlanAuditRecordIsMetadataOnlyAndTaggedWithToolName() {
        AuditRecord record = AuditRecord.builder()
                .requestId("rid-1")
                .timestamp("2026-01-01T00:00:00Z")
                .toolName(RefactorPlanTool.TOOL_NAME)
                .model("claude-opus-4-8")
                .language("java")
                .outputFormat("refactor_plan")
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
        assertThat(line).contains("tool=refactor_plan_with_opus");
        // Metadata only: no code/context/constraints/refactor-plan content can appear.
        assertThat(line).doesNotContain("public static int add");
        assertThat(line).doesNotContain("api_key");
        assertThat(line).doesNotContain("secret-key-value");
    }
}
