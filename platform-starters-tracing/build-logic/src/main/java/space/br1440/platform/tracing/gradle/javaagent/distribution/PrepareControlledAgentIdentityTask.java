package space.br1440.platform.tracing.gradle.javaagent.distribution;

import java.nio.file.Path;

import org.gradle.api.DefaultTask;
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
@DisableCachingByDefault(because = "The validation is lightweight and its output is local build metadata")
public abstract class PrepareControlledAgentIdentityTask extends DefaultTask {

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getUpstreamAgentJar();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getPackagedExtensionJar();

    @Input
    public abstract Property<String> getDistributionVersion();

    @Input
    public abstract Property<String> getUpstreamCoordinate();

    @OutputFile
    public abstract RegularFileProperty getIdentityFile();

    @TaskAction
    public void prepare() {
        Path upstreamAgentJar = getUpstreamAgentJar().get().getAsFile().toPath();
        Path packagedExtensionJar = getPackagedExtensionJar().get().getAsFile().toPath();
        String upstreamSha256 = JavaAgentDistribution.requireReviewedUpstreamSha256(
                upstreamAgentJar,
                packagedExtensionJar
        );
        JavaAgentDistribution.requireNoPlatformEntries(upstreamAgentJar);

        JavaAgentDistribution.createIdentity(
                        getDistributionVersion().get(),
                        getUpstreamCoordinate().get(),
                        upstreamSha256,
                        packagedExtensionJar
                )
                .writeTo(getIdentityFile().get().getAsFile().toPath());
    }
}
