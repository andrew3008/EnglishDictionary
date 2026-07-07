package space.br1440.platform.devtools.opusmcp.audit;

import org.junit.jupiter.api.Test;
import space.br1440.platform.devtools.opusmcp.tool.GenerateTestsTool;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Documents the audit contract for {@code generate_tests_with_opus}: the metadata-only
 * {@link AuditRecord} carries the tool name but, by construction, has no field able to hold
 * task/code/context/constraints/generated-test output.
 */
class GenerateTestsAuditContractTest {

    @Test
    void generateTestsAuditRecordIsMetadataOnlyAndTaggedWithToolName() {
        AuditRecord record = AuditRecord.builder()
                .requestId("rid-1")
                .timestamp("2026-01-01T00:00:00Z")
                .toolName(GenerateTestsTool.TOOL_NAME)
                .model("claude-opus-4-8")
                .language("java")
                .outputFormat("structured_tests")
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
                .providerRequestId("req-provider-1")
                .envelopeKind("openai_chat_string")
                .diagnosticCategory("none")
                .providerCallAttempted(true)
                .build();

        String line = record.toLogString();
        assertThat(line).contains("tool=generate_tests_with_opus");
        assertThat(line)
                .contains("providerRequestId=req-provider-1")
                .contains("envelopeKind=openai_chat_string")
                .contains("diagnosticCategory=none")
                .contains("providerCallAttempted=true");
        // Metadata only: no code/context/constraints/generated-test content can appear.
        assertThat(line).doesNotContain("public static int add");
        assertThat(line).doesNotContain("api_key");
        assertThat(line).doesNotContain("secret-key-value");
    }
}
