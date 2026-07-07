package space.br1440.platform.devtools.opusmcp.audit;

import org.junit.jupiter.api.Test;
import space.br1440.platform.devtools.opusmcp.tool.DesignClassHierarchyTool;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Documents the audit contract for {@code design_class_hierarchy_with_opus}: the metadata-only
 * {@link AuditRecord} carries the tool name but, by construction, has no field able to hold
 * task/domainContext/existingTypes/packageContext/constraints/designOverview/proposedTypes/
 * relationships or model output.
 */
class DesignClassHierarchyAuditContractTest {

    @Test
    void designClassHierarchyAuditRecordIsMetadataOnlyAndTaggedWithToolName() {
        AuditRecord record = AuditRecord.builder()
                .requestId("rid-1")
                .timestamp("2026-01-01T00:00:00Z")
                .toolName(DesignClassHierarchyTool.TOOL_NAME)
                .model("claude-opus-4-8")
                .language("java")
                .outputFormat("design_proposal")
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
        assertThat(line).contains("tool=design_class_hierarchy_with_opus");
        // Metadata only: no domain/type/context/design content can appear.
        assertThat(line).doesNotContain("PaymentGateway");
        assertThat(line).doesNotContain("api_key");
        assertThat(line).doesNotContain("secret-key-value");
    }
}
