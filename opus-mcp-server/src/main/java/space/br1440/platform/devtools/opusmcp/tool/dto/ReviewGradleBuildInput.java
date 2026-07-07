package space.br1440.platform.devtools.opusmcp.tool.dto;

public record ReviewGradleBuildInput(
        String task,
        String buildFilesContext,
        String settingsContext,
        String versionCatalogContext,
        String gradlePropertiesContext,
        String buildLogicContext,
        String dependencyContext,
        String buildFailureLogs,
        String constraints,
        GradleProjectType projectType,
        GradleDsl gradleDsl,
        GradleReviewFocus reviewFocus,
        RiskLevel riskLevel,
        GradleReviewOutputFormat outputFormat) {
}
