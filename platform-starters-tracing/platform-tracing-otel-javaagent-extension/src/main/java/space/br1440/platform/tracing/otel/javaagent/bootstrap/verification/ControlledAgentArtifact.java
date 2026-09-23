package space.br1440.platform.tracing.otel.javaagent.bootstrap.verification;

import java.nio.file.Path;
import java.util.Map;

record ControlledAgentArtifact(Path path, Map<String, String> identity) {

    ControlledAgentArtifact {
        identity = Map.copyOf(identity);
    }
}
