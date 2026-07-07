package space.br1440.platform.devtools.opusmcp.audit;

import org.junit.jupiter.api.Test;
import space.br1440.platform.devtools.opusmcp.tool.AnalyzeBuildFailureTool;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Documents the audit contract for {@code analyze_build_failure_with_opus}: the metadata-only
 * {@link AuditRecord} carries the tool name but, by construction, has no field able to hold
 * task/failureLog/relevantCode/buildContext/constraints/minimalPatchSuggestion or model output.
 */
class AnalyzeBuildFailureAuditContractTest {

    @Test
    void analyzeBuildFailureAuditRecordIsMetadataOnlyAndTaggedWithToolName() {
        AuditRecord record = AuditRecord.builder()
                .requestId("rid-1")
                .timestamp("2026-01-01T00:00:00Z")
                .toolName(AnalyzeBuildFailureTool.TOOL_NAME)
                .model("claude-opus-4-8")
                .language("java")
                .outputFormat("root_cause_analysis")
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
        assertThat(line).contains("tool=analyze_build_failure_with_opus");
        // Metadata only: no log/code/context/patch content can appear.
        assertThat(line).doesNotContain("cannot find symbol");
        assertThat(line).doesNotContain("api_key");
        assertThat(line).doesNotContain("secret-key-value");
    }
}
