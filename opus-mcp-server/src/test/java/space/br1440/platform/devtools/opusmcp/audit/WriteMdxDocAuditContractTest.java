package space.br1440.platform.devtools.opusmcp.audit;

import org.junit.jupiter.api.Test;
import space.br1440.platform.devtools.opusmcp.tool.WriteMdxDocTool;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Documents the audit contract for {@code write_mdx_doc_with_opus}: the metadata-only
 * {@link AuditRecord} carries the tool name but, by construction, has no field able to hold
 * task/docSubject/libraryContext/publicApi/configurationProperties/usageExamples/docStyleContext/
 * mdxComponentsContext/assetGuidelines/constraints/frontMatter/imports/mdxContent or model output.
 */
class WriteMdxDocAuditContractTest {

    @Test
    void writeMdxDocAuditRecordIsMetadataOnlyAndTaggedWithToolName() {
        AuditRecord record = AuditRecord.builder()
                .requestId("rid-1")
                .timestamp("2026-01-01T00:00:00Z")
                .toolName(WriteMdxDocTool.TOOL_NAME)
                .model("claude-opus-4-8")
                .outputFormat("mdx_page")
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
        assertThat(line).contains("tool=write_mdx_doc_with_opus");
        // Metadata only: no doc content / front matter / imports / secrets can appear.
        assertThat(line).doesNotContain("tracing.enabled");
        assertThat(line).doesNotContain("@theme/Tabs");
        assertThat(line).doesNotContain("api_key");
        assertThat(line).doesNotContain("secret-key-value");
    }
}
