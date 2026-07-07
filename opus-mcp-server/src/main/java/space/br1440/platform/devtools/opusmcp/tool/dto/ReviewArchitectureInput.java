package space.br1440.platform.devtools.opusmcp.tool.dto;

public record ReviewArchitectureInput(
        String task,
        String architectureProposal,
        String context,
        String constraints,
        ArchitectureReviewFocus reviewFocus,
        ArchitectureReviewScope architectureScope,
        ArchitectureReviewStyle architectureStyle,
        ArchitectureCompatibilityMode compatibilityMode,
        RiskLevel riskLevel,
        ArchitectureOutputFormat outputFormat) {
}
