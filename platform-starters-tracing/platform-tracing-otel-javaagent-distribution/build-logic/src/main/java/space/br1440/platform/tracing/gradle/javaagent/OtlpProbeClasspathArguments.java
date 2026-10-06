package space.br1440.platform.tracing.gradle.javaagent;

import java.util.List;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.tasks.Classpath;
import org.gradle.process.CommandLineArgumentProvider;

public abstract class OtlpProbeClasspathArguments implements CommandLineArgumentProvider {

    @Classpath
    public abstract ConfigurableFileCollection getClasspath();

    @Override
    public Iterable<String> asArguments() {
        return List.of("-DplatformTracing.otlpProbeApiClasspath=" + getClasspath().getAsPath());
    }
}
