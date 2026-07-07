package space.br1440.platform.devtools.opusmcp.tool.dto;

public record GenerateCodeInput(
        String task,
        CodeLanguage language,
        String context,
        String constraints,
        OutputFormat outputFormat,
        RiskLevel riskLevel) {
}
