package space.br1440.platform.devtools.opusmcp.audit;

import org.junit.jupiter.api.Test;
import space.br1440.platform.devtools.opusmcp.tool.GenerateMigrationPlanTool;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Documents the audit contract for {@code generate_migration_plan_with_opus}: the metadata-only
 * {@link AuditRecord} carries the tool name but, by construction, has no field able to hold
 * task/currentState/targetState/migrationContext/constraints/migrationOverview/migrationSlices or the
 * raw model output.
 */
class GenerateMigrationPlanAuditContractTest {

    @Test
    void migrationPlanAuditRecordIsMetadataOnlyAndTaggedWithToolName() {
        AuditRecord record = AuditRecord.builder()
                .requestId("rid-1")
                .timestamp("2026-01-01T00:00:00Z")
                .toolName(GenerateMigrationPlanTool.TOOL_NAME)
                .model("claude-opus-4-8")
                .outputFormat("migration_slices")
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
        assertThat(line).contains("tool=generate_migration_plan_with_opus");
        // Metadata only: no state / slices / secrets can appear.
        assertThat(line).doesNotContain("jakarta");
        assertThat(line).doesNotContain("Spring Boot");
        assertThat(line).doesNotContain("api_key");
        assertThat(line).doesNotContain("secret-key-value");
    }
}
