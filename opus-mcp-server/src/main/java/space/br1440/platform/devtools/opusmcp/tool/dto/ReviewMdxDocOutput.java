package space.br1440.platform.devtools.opusmcp.tool.dto;

import space.br1440.platform.devtools.opusmcp.model.OpusClientException;

import java.util.List;

/**
 * Output of {@code review_mdx_doc_with_opus}. Reuses {@link GenerateCodeStatus} for the status enum.
 * Independent of the other tool output records so existing tool contracts are never affected.
 * {@code review} is preserved as a verbatim multi-line text block.
 */
public record ReviewMdxDocOutput(
        GenerateCodeStatus status,
        String summary,
        String verdict,
        String review,
        List<MdxFinding> findings,
        List<String> missingSections,
        List<String> incorrectOrUnverifiedClaims,
        List<String> mdxIssues,
        List<String> styleIssues,
        List<String> exampleIssues,
        List<String> suggestedEdits,
        List<String> validationChecklist,
        List<String> risks,
        List<String> safetyNotes,
        List<String> assumptions,
        boolean truncated,
        int inputTokenEstimate,
        int outputTokenEstimate,
        String model,
        String requestId) {

    public static ReviewMdxDocOutput ofStatus(
            GenerateCodeStatus status,
            String summary,
            String requestId,
            String model) {
        return new ReviewMdxDocOutput(
                status,
                summary,
                ProviderFailureSemantics.verdictForStatus(
                        status, MdxReviewVerdict.NEEDS_MORE_CONTEXT.wireValue()),
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                false,
                0,
                0,
                model == null ? "" : model,
                requestId);
    }

    public static ReviewMdxDocOutput ofProviderFailure(
            GenerateCodeStatus status,
            String summary,
            OpusClientException.Reason reason,
            String requestId,
            String model) {
        return new ReviewMdxDocOutput(
                status,
                summary,
                "",
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                ProviderFailureSemantics.risks("MDX doc review", reason),
                ProviderFailureSemantics.safetyNotes(),
                List.of(),
                false,
                0,
                0,
                model == null ? "" : model,
                requestId);
    }
}
