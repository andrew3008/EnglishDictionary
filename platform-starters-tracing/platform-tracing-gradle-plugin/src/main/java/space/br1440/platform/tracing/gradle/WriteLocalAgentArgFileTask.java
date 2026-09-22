package space.br1440.platform.tracing.gradle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

public abstract class WriteLocalAgentArgFileTask extends DefaultTask {

    @InputFile
    @PathSensitive(PathSensitivity.ABSOLUTE)
    public abstract RegularFileProperty getVerifiedAgentJar();

    @Input
    public abstract Property<String> getExpectedSha256();

    @OutputFile
    public abstract RegularFileProperty getArgsFile();

    @TaskAction
    public void write() {
        String agentPath = getVerifiedAgentJar().get().getAsFile().getAbsolutePath();
        List<String> flags = ManagedAgentJvmFlags.forAgentPath(agentPath, getExpectedSha256().get());
        Path target = getArgsFile().get().getAsFile().toPath();

        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, JavaArgFileFormat.render(flags), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new GradleException("Cannot write " + target, failure);
        }

        getLogger().lifecycle("Platform tracing: local agent argfile is ready at {}", target.toAbsolutePath());
    }
}
