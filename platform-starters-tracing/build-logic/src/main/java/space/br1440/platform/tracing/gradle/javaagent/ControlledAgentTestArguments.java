package space.br1440.platform.tracing.gradle.javaagent;

import java.util.ArrayList;
import java.util.List;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.process.CommandLineArgumentProvider;

@SuppressWarnings("unused")
public abstract class ControlledAgentTestArguments implements CommandLineArgumentProvider {

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getControlledAgentJar();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getExtensionJar();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getUpstreamAgentJar();

    @Input
    public abstract Property<String> getDistributionVersion();

    @Input
    public abstract Property<String> getExpectedOtelSdkVersion();

    @Internal
    public abstract DirectoryProperty getTestRepositoryRoot();

    @Override
    public Iterable<String> asArguments() {
        List<String> arguments = new ArrayList<>();

        arguments.add("-DplatformTracing.controlledAgentJar=" + getControlledAgentJar().get().getAsFile().getAbsolutePath());
        arguments.add("-DplatformTracing.extensionJar=" + getExtensionJar().get().getAsFile().getAbsolutePath());
        arguments.add("-DplatformTracing.otelJavaAgentJar=" + getUpstreamAgentJar().get().getAsFile().getAbsolutePath());
        arguments.add("-DplatformTracing.distributionVersion=" + getDistributionVersion().get());
        arguments.add("-DplatformTracing.expectedOtelSdkVersion=" + getExpectedOtelSdkVersion().get());

        if (getTestRepositoryRoot().isPresent()) {
            arguments.add("-DplatformTracing.testRepository=" + getTestRepositoryRoot().get().getAsFile().getAbsolutePath());
        }

        return List.copyOf(arguments);
    }
}
