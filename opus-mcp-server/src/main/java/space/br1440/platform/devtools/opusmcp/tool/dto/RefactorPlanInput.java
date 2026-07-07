package space.br1440.platform.devtools.opusmcp.tool.dto;

public record RefactorPlanInput(
        String task,
        CodeLanguage language,
        String code,
        String context,
        String constraints,
        RefactorGoal refactorGoal,
        RefactorScope scope,
        CompatibilityMode compatibilityMode,
        RiskLevel riskLevel,
        RefactorOutputFormat outputFormat) {
}
