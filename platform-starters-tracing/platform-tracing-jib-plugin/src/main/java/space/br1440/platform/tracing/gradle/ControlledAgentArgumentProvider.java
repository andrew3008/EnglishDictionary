package space.br1440.platform.tracing.gradle;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.process.CommandLineArgumentProvider;

public abstract class ControlledAgentArgumentProvider implements CommandLineArgumentProvider {

    @InputFile
    @PathSensitive(PathSensitivity.ABSOLUTE)
    public abstract RegularFileProperty getAgentJar();

    @Input
    public abstract Property<String> getExpectedSha256();

    @Override
    public Iterable<String> asArguments() {
        return ManagedAgentJvmFlags.forAgentPath(
                getAgentJar().get().getAsFile().getAbsolutePath(),
                getExpectedSha256().get()
        );
    }
}
