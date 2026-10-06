package space.br1440.platform.tracing.gradle.javaagent;

import java.nio.file.Path;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

@DisableCachingByDefault(because = "Publishing requires an atomic replacement in the destination directory")
public abstract class PublishJavaAgentTask extends DefaultTask {

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getStagedJar();

    @OutputFile
    public abstract RegularFileProperty getPublishedJar();

    @TaskAction
    public void publish() {
        Path stagedJarPath = getStagedJar().get().getAsFile().toPath();
        Path publishedJarPath = getPublishedJar().get().getAsFile().toPath();
        DistributionFiles.publishAtomically(stagedJarPath, publishedJarPath);
    }
}
