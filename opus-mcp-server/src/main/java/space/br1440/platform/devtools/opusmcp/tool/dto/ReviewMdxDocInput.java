package space.br1440.platform.devtools.opusmcp.tool.dto;

public record ReviewMdxDocInput(
        String task,
        String mdxContent,
        String docSubject,
        DocTargetAudience targetAudience,
        String libraryContext,
        String styleGuideContext,
        String mdxComponentsContext,
        String constraints,
        MdxReviewFocus reviewFocus,
        DocType docType,
        RiskLevel riskLevel,
        MdxReviewOutputFormat outputFormat) {
}
