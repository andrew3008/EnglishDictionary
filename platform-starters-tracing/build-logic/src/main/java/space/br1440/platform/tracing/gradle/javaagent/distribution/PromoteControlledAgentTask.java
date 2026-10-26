package space.br1440.platform.tracing.gradle.javaagent.distribution;

import java.nio.file.Path;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

@SuppressWarnings("unused")
@DisableCachingByDefault(because = "Publishing requires an atomic replacement in the destination directory")
public abstract class PromoteControlledAgentTask extends DefaultTask {

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getStagedJar();

    @OutputFile
    public abstract RegularFileProperty getPublishedJar();

    @TaskAction
    public void promote() {
        Path stagedJar = getStagedJar().get().getAsFile().toPath();
        Path publishedJar = getPublishedJar().get().getAsFile().toPath();
        DistributionFiles.publishAtomically(stagedJar, publishedJar);
    }
}
