package space.br1440.platform.tracing.gradle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

public abstract class GenerateAgentDockerBuildArgsTask extends DefaultTask {

    private static final String BUILD_ARG_NAME = "PLATFORM_AGENT_JAR";

    @Input
    public abstract Property<String> getAgentFileName();

    @OutputFile
    public abstract RegularFileProperty getBuildArgsFile();

    @TaskAction
    public void generate() {
        Path target = getBuildArgsFile().get().getAsFile().toPath();
        String content = BUILD_ARG_NAME + "=" + getAgentFileName().get() + "\n";

        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content);
        } catch (IOException failure) {
            throw new GradleException("Cannot write " + target, failure);
        }
    }
}
