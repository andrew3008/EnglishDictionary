package space.br1440.platform.devtools.opusmcp.tool.dto;

public record ReviewTestsInput(
        String task,
        TestReviewLanguage language,
        String testCode,
        String productionContext,
        String testIntent,
        String failureLogs,
        String dependenciesContext,
        String constraints,
        TestFramework testFramework,
        TestType testType,
        TestReviewFocus reviewFocus,
        RiskLevel riskLevel,
        TestReviewOutputFormat outputFormat) {
}
