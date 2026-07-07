package space.br1440.platform.devtools.opusmcp.tool.dto;

public record AnalyzeBuildFailureInput(
        String task,
        String failureLog,
        String relevantCode,
        String buildContext,
        String constraints,
        FailureType failureType,
        CodeLanguage language,
        RiskLevel riskLevel,
        BuildFailureOutputFormat outputFormat) {
}
