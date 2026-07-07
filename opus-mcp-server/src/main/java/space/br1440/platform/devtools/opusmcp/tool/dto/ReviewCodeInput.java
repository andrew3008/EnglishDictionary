package space.br1440.platform.devtools.opusmcp.tool.dto;

public record ReviewCodeInput(
        String task,
        CodeLanguage language,
        String code,
        String context,
        String constraints,
        ReviewFocus reviewFocus,
        RiskLevel riskLevel,
        ReviewOutputFormat outputFormat) {
}
