package space.br1440.platform.tracing.gradle;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
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

    @TaskAction
    public void verify() {
        File source = requireSingleArtifact();
        Path directory = getAgentDirectory().get().getAsFile().toPath();
        String fileName = getAgentFileName().get();

        try {
            Files.createDirectories(directory);
            deleteOwnedDirectory(directory);
            Path temporary = Files.createTempFile(directory, fileName + "-", ".tmp");

            String actual = copyAndHash(source.toPath(), temporary);
            String expected = getExpectedSha256().get();
            if (!expected.equals(actual)) {
                Files.deleteIfExists(temporary);
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

            Files.move(temporary, directory.resolve(fileName), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            throw new GradleException("Cannot stage " + source.getAbsolutePath(), failure);
        }
    }

    private static void deleteOwnedDirectory(Path directory) throws IOException {
        try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
            for (Path child : children) {
                Files.deleteIfExists(child);
            }
        }
    }

    private static String copyAndHash(Path source, Path target) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IOException("SHA-256 is unavailable", failure);
        }

        try (InputStream input = new DigestInputStream(Files.newInputStream(source), digest);
             OutputStream output = Files.newOutputStream(target)) {
            input.transferTo(output);
        }

        return HexFormat.of().formatHex(digest.digest());
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
}
