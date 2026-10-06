package space.br1440.platform.tracing.gradle.javaagent;

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

public abstract class ControlledAgentTestArguments implements CommandLineArgumentProvider {

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getControlledAgentJar();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getExtensionJar();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getExtensionLibraryJar();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getUpstreamAgentJar();

    @Input
    public abstract Property<String> getDistributionVersion();

    @Input
    public abstract Property<String> getUpstreamCoordinate();

    @Internal
    public abstract DirectoryProperty getTestRepositoryRoot();

    @Override
    public Iterable<String> asArguments() {
        return List.of(
                "-DplatformTracing.controlledAgentJar=" + getControlledAgentJar().get().getAsFile().getAbsolutePath(),
                "-DplatformTracing.extensionJar=" + getExtensionJar().get().getAsFile().getAbsolutePath(),
                "-DplatformTracing.extensionLibraryJar=" + getExtensionLibraryJar().get().getAsFile().getAbsolutePath(),
                "-DplatformTracing.otelJavaAgentJar=" + getUpstreamAgentJar().get().getAsFile().getAbsolutePath(),
                "-DplatformTracing.distributionVersion=" + getDistributionVersion().get(),
                "-DplatformTracing.otelJavaAgentCoordinate=" + getUpstreamCoordinate().get(),
                "-DplatformTracing.testRepository=" + getTestRepositoryRoot().get().getAsFile().getAbsolutePath()
        );
    }
}
