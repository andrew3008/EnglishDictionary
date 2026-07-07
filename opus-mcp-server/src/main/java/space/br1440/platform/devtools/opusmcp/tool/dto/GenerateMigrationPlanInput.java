package space.br1440.platform.devtools.opusmcp.tool.dto;

public record GenerateMigrationPlanInput(
        String task,
        CodeLanguage language,
        String currentState,
        String targetState,
        String migrationContext,
        String constraints,
        MigrationCompatibilityMode compatibilityMode,
        MigrationScope migrationScope,
        MigrationType migrationType,
        RiskLevel riskLevel,
        MigrationOutputFormat outputFormat) {
}
