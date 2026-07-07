package space.br1440.platform.devtools.opusmcp.tool.dto;

public record ExplainDiffInput(
        String task,
        CodeLanguage language,
        String diff,
        String context,
        String constraints,
        DiffFormat diffFormat,
        DiffAnalysisFocus analysisFocus,
        RiskLevel riskLevel,
        DiffOutputFormat outputFormat) {
}
