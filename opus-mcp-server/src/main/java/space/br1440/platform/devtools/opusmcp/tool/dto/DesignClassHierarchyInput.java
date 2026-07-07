package space.br1440.platform.devtools.opusmcp.tool.dto;

public record DesignClassHierarchyInput(
        String task,
        CodeLanguage language,
        String domainContext,
        String existingTypes,
        String packageContext,
        String constraints,
        DesignGoal designGoal,
        DesignScope scope,
        ArchitectureStyle architectureStyle,
        RiskLevel riskLevel,
        DesignOutputFormat outputFormat) {
}
