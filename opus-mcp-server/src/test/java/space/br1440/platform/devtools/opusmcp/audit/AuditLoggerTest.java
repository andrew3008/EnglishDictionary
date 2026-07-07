package space.br1440.platform.devtools.opusmcp.audit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class AuditLoggerTest {

    private AuditRecord record(String status) {
        return AuditRecord.builder()
                .requestId("req-1")
                .timestamp("2026-06-28T00:00:00Z")
                .toolName("generate_code_with_opus")
                .model("claude-opus-4-8")
                .language("java")
                .outputFormat("code_block")
                .riskLevel("low")
                .status(status)
                .latencyMs(12)
                .inputCharCount(42)
                .estimatedInputTokens(10)
                .estimatedOutputTokens(8)
                .budgetDecision("allowed")
                .rateLimitDecision("allowed")
                .httpStatusCategory("2xx")
                .build();
    }

    @Test
    void renderedRecordContainsMetadataOnly() {
        String rendered = record("OK").toLogString();
        assertThat(rendered)
                .contains("status=OK")
                .contains("model=claude-opus-4-8")
                .contains("requestId=req-1")
                .contains("language=java")
                .contains("budgetDecision=allowed")
                .contains("rateLimitDecision=allowed")
                .contains("providerRequestId=-")
                .contains("envelopeKind=-")
                .contains("diagnosticCategory=-")
                .contains("providerCallAttempted=false");
    }

    @Test
    void renderedRecordHasNoTaskContextOrResultPayload() {
        String rendered = record("OK").toLogString();
        // Metadata field names only; no raw payload fields are present.
        assertThat(rendered)
                .doesNotContain("task=")
                .doesNotContain("context=")
                .doesNotContain("constraints=")
                .doesNotContain("result=")
                .doesNotContain("apiKey");
    }

    @Test
    void loggingDoesNotThrowForDefaultAndContentFlag() {
        assertThatCode(() -> new AuditLogger(false).log(record("OK"))).doesNotThrowAnyException();
        assertThatCode(() -> new AuditLogger(true).log(record("REFUSED_UNSAFE")))
                .doesNotThrowAnyException();
        assertThatCode(() -> new AuditLogger().log(null)).doesNotThrowAnyException();
    }
}
