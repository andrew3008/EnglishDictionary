package space.br1440.platform.tracing.otel.javaagent.distribution;

import java.nio.file.Path;

record ArtifactPaths(Path agent, Path otel, Path extension) {

    static ArtifactPaths resolve() {
        return new ArtifactPaths(
                AgentArchive.requiredPath("platformTracing.controlledAgentJar"),
                AgentArchive.requiredPath("platformTracing.otelJavaAgentJar"),
                AgentArchive.requiredPath("platformTracing.extensionJar")
        );
    }
}
