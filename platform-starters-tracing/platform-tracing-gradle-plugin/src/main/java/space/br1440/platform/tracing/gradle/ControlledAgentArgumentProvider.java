package space.br1440.platform.tracing.gradle;

import java.util.List;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.process.CommandLineArgumentProvider;

public abstract class ControlledAgentArgumentProvider implements CommandLineArgumentProvider {

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getAgentJar();

    @Override
    public Iterable<String> asArguments() {
        return List.of("-javaagent:" + getAgentJar().get().getAsFile().getAbsolutePath());
    }
}
