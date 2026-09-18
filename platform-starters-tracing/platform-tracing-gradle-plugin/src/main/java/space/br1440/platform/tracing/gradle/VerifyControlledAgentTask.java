package space.br1440.platform.tracing.gradle;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

public abstract class VerifyControlledAgentTask extends DefaultTask {

    private static final int BUFFER_SIZE = 64 * 1024;

    @InputFile
    @PathSensitive(PathSensitivity.ABSOLUTE)
    public abstract RegularFileProperty getAgentJar();

    @Input
    public abstract Property<String> getAgentVersion();

    @Input
    public abstract Property<String> getExpectedSha256();

    @OutputFile
    public abstract RegularFileProperty getArgsFile();

    @TaskAction
    public void verify() {
        File agentJar = getAgentJar().get().getAsFile();
        String actual = sha256Hex(agentJar);
        String expected = getExpectedSha256().get();
        if (!expected.equals(actual)) {
            throw new GradleException("""
                    Controlled Java agent %s does not match the SHA-256 pinned by this plugin release.
                      expected: %s
                      actual:   %s
                      file:     %s
                    Resolve the agent from the approved repository or use the plugin release \
                    that owns this agent version."""
                    .formatted(
                            getAgentVersion().get(),
                            expected,
                            actual,
                            agentJar.getAbsolutePath()
                    )
            );
        }

        writeArgsFile(agentJar, actual);
    }

    private void writeArgsFile(File agentJar, String verifiedSha256) {
        Path target = getArgsFile().get().getAsFile().toPath();
        List<String> flags = ManagedAgentJvmFlags.forAgentPath(agentJar.getAbsolutePath(), verifiedSha256);
        String content = JavaArgFileFormat.render(flags);

        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content);
        } catch (IOException failure) {
            throw new GradleException("Cannot write " + target, failure);
        }

        getLogger().lifecycle("Platform tracing: local agent argfile is ready at {}", target.toAbsolutePath());
    }

    private static String sha256Hex(File file) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new GradleException("SHA-256 is not available in this JVM", failure);
        }

        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream stream = Files.newInputStream(file.toPath())) {
            int read = stream.read(buffer);
            while (read != -1) {
                digest.update(buffer, 0, read);
                read = stream.read(buffer);
            }
        } catch (IOException failure) {
            throw new GradleException("""
                    Cannot read %s"""
                    .formatted(file.getAbsolutePath()),
                    failure
            );
        }

        byte[] bytes = digest.digest();
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >> 4) & 0xF, 16));
            result.append(Character.forDigit(value & 0xF, 16));
        }
        return result.toString();
    }
}
