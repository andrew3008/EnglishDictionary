package space.br1440.platform.devtools.opusmcp.tool.dto;

public record WriteMdxDocInput(
        String task,
        String docSubject,
        DocTargetAudience targetAudience,
        String libraryContext,
        String publicApi,
        String configurationProperties,
        String usageExamples,
        String docStyleContext,
        String mdxComponentsContext,
        String assetGuidelines,
        String constraints,
        DocType docType,
        MdxOutputFormat outputFormat,
        RiskLevel riskLevel) {
}
