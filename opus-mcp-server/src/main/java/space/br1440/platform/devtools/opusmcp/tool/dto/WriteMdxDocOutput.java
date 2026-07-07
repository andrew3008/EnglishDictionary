package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.List;

/**
 * Output of {@code write_mdx_doc_with_opus}. Reuses {@link GenerateCodeStatus} for the status enum.
 * Independent of the other tool output records so existing tool contracts are never affected.
 * {@code frontMatter} and {@code mdxContent} are preserved as verbatim multi-line text blocks.
 */
public record WriteMdxDocOutput(
        GenerateCodeStatus status,
        String summary,
        String frontMatter,
        List<String> imports,
        String mdxContent,
        List<String> outline,
        List<String> examples,
        List<String> admonitions,
        List<String> assetsNeeded,
        List<String> linksToAdd,
        List<String> claimsToVerify,
        List<String> validationChecklist,
        List<String> risks,
        List<String> safetyNotes,
        List<String> assumptions,
        boolean truncated,
        int inputTokenEstimate,
        int outputTokenEstimate,
        String model,
        String requestId) {

    public static WriteMdxDocOutput ofStatus(
            GenerateCodeStatus status,
            String summary,
            String requestId,
            String model) {
        return new WriteMdxDocOutput(
                status,
                summary,
                "",
                List.of(),
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
                false,
                0,
                0,
                model == null ? "" : model,
                requestId);
    }
}
