package space.br1440.platform.devtools.opusmcp.tool.dto;

public record GenerateTestsInput(
        String task,
        CodeLanguage language,
        String code,
        String context,
        String constraints,
        GenerateTestFramework testFramework,
        GenerateTestType testType,
        CoverageFocus coverageFocus,
        RiskLevel riskLevel,
        TestOutputFormat outputFormat) {
}
