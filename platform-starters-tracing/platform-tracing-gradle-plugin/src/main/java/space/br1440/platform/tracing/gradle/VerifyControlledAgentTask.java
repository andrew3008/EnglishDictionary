package space.br1440.platform.tracing.gradle;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

import javax.inject.Inject;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

public abstract class VerifyControlledAgentTask extends DefaultTask {

    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getControlledAgent();

    @Input
    public abstract Property<String> getAgentVersion();

    @Input
    public abstract Property<String> getExpectedSha256();

    @Input
    public abstract Property<String> getAgentFileName();

    @OutputDirectory
    public abstract DirectoryProperty getAgentDirectory();

    @Inject
    protected abstract FileSystemOperations getFileSystemOperations();

    @TaskAction
    public void verify() {
        File source = requireSingleArtifact();
        String actual = sha256Of(source.toPath());
        String expected = getExpectedSha256().get();
        if (!expected.equals(actual)) {
            throw new GradleException("""
                    Controlled Java agent %s does not match the SHA-256 pinned by this plugin release.
                      expected: %s
                      actual:   %s
                      file:     %s
                    Resolve the agent from the approved repository or use the plugin release \
                    that owns this agent version."""
                    .formatted(getAgentVersion().get(), expected, actual, source.getAbsolutePath())
            );
        }

        String fileName = getAgentFileName().get();
        getFileSystemOperations().sync(spec -> {
            spec.from(source);
            spec.into(getAgentDirectory());
            spec.rename(sourceName -> fileName);
        });
    }

    private File requireSingleArtifact() {
        Set<File> files = getControlledAgent().getFiles();
        if (files.size() != 1) {
            throw new GradleException("""
                    Controlled Java agent configuration must resolve to exactly one artifact, got %d: %s. \
                    The agent is a single JAR pinned by the plugin release; do not add dependencies to \
                    the controlledAgent configuration."""
                    .formatted(files.size(), files)
            );
        }

        return files.iterator().next();
    }

    private static String sha256Of(Path file) {
        try {
            return Sha256Digest.hexOf(file);
        } catch (IOException failure) {
            throw new GradleException("Cannot read " + file.toAbsolutePath(), failure);
        }
    }
}
