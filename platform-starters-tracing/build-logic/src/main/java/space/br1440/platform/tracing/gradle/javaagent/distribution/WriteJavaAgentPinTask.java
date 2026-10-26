package space.br1440.platform.tracing.gradle.javaagent.distribution;

import java.nio.file.Path;
import java.util.regex.Pattern;

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
import org.gradle.work.DisableCachingByDefault;

@SuppressWarnings("unused")
@DisableCachingByDefault(because = "The task writes a small local integrity pin")
public abstract class WriteJavaAgentPinTask extends DefaultTask {

    private static final Pattern PIN_VERSION = Pattern.compile("[0-9A-Za-z][0-9A-Za-z._+-]*");

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getAgentJar();

    @Input
    public abstract Property<String> getAgentVersion();

    @OutputFile
    public abstract RegularFileProperty getPinFile();

    @TaskAction
    public void write() {
        String agentVersion = getAgentVersion().get();
        if (!PIN_VERSION.matcher(agentVersion).matches()) {
            String message = "agent.version must match " + PIN_VERSION.pattern() + ", got: " + agentVersion;
            throw new GradleException(message);
        }

        Path agentJar = getAgentJar().get().getAsFile().toPath();
        String content = "agent.sha256=%s\nagent.version=%s\n"
                .formatted(DistributionFiles.sha256Hex(agentJar), agentVersion);

        Path pinFilePath = getPinFile().get().getAsFile().toPath();
        DistributionFiles.writeUtf8(pinFilePath, content,"controlled agent pin");
    }
}
