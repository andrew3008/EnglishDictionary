package space.br1440.platform.tracing.gradle.javaagent;

import java.nio.file.Path;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

@DisableCachingByDefault(because = "The validation is lightweight and its output is local build metadata")
public abstract class ValidateJavaAgentDistributionTask extends DefaultTask {

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getUpstreamAgentJar();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getExtensionLibraryJar();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getPackagedExtensionJar();

    @Input
    public abstract Property<String> getDistributionVersion();

    @Input
    public abstract Property<String> getUpstreamCoordinate();

    @Input
    public abstract Property<String> getExtensionPath();

    @Input
    public abstract SetProperty<String> getReservedEntries();

    @OutputFile
    public abstract RegularFileProperty getIdentityFile();

    @TaskAction
    public void validate() {
        Path upstreamAgentJar = getUpstreamAgentJar().get().getAsFile().toPath();
        Path packagedExtensionJar = getPackagedExtensionJar().get().getAsFile().toPath();
        String upstreamSha256 = JavaAgentDistribution.requireReviewedUpstreamSha256(
                upstreamAgentJar,
                getExtensionLibraryJar().get().getAsFile().toPath()
        );
        JavaAgentDistribution.requireNoPlatformEntries(upstreamAgentJar, getReservedEntries().get());

        JavaAgentDistribution.createIdentity(
                        getDistributionVersion().get(),
                        getUpstreamCoordinate().get(),
                        upstreamSha256,
                        getExtensionPath().get(),
                        packagedExtensionJar
                )
                .writeTo(getIdentityFile().get().getAsFile().toPath());
    }
}
