package space.br1440.platform.tracing.otel.javaagent.jmx.readiness;

import java.util.Objects;

import space.br1440.platform.common.util.StringUtils;

public record ExtensionRuntimeEvidence(String agentDistributionSha256,
                                       String upstreamAgentVersion,
                                       String extensionVersion,
                                       String extensionDefiningLoader,
                                       String sdkDefiningLoader) {

    public ExtensionRuntimeEvidence {
        Objects.requireNonNull(agentDistributionSha256, "agentDistributionSha256");
        Objects.requireNonNull(upstreamAgentVersion, "upstreamAgentVersion");
        Objects.requireNonNull(extensionVersion, "extensionVersion");
        Objects.requireNonNull(extensionDefiningLoader, "extensionDefiningLoader");
        Objects.requireNonNull(sdkDefiningLoader, "sdkDefiningLoader");
    }

    public static ExtensionRuntimeEvidence unavailable(String extensionVersion) {
        return new ExtensionRuntimeEvidence(
                "",
                "",
                Objects.requireNonNull(extensionVersion, "extensionVersion"),
                "",
                ""
        );
    }

    public boolean complete() {
        return StringUtils.hasText(agentDistributionSha256)
                && StringUtils.hasText(upstreamAgentVersion)
                && StringUtils.hasText(extensionVersion)
                && StringUtils.hasText(extensionDefiningLoader)
                && StringUtils.hasText(sdkDefiningLoader);
    }
}
