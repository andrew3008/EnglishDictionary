package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import java.nio.file.Path;
import java.util.Properties;

record ControlledAgentArtifact(Path path, Properties identity) {
}
