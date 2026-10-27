package space.br1440.platform.tracing.gradle.javaagent.distribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ControlledAgentTestArgumentsTest {

    private static final String PROBE_PROPERTY = "platformTracing.otlpProbeApiClasspath";
    private static final String PROBE_ARGUMENT_PREFIX = "-D" + PROBE_PROPERTY;

    @Test
    void omits_probe_property_when_classpath_is_empty(@TempDir Path directory) throws IOException {
        // given
        ControlledAgentTestArguments arguments = requiredArguments(directory.resolve("project with spaces"));

        // when
        Iterable<String> commandLine = arguments.asArguments();

        // then
        assertThat(commandLine).noneMatch(argument -> argument.startsWith(PROBE_ARGUMENT_PREFIX));
    }

    @Test
    void passes_classpath_with_spaces_to_java_as_one_argument(@TempDir Path directory)
            throws IOException, InterruptedException, URISyntaxException {
        // given
        ControlledAgentTestArguments arguments = requiredArguments(directory.resolve("project with spaces"));
        Path first = Files.createFile(directory.resolve("first probe api.jar"));
        Path second = Files.createFile(directory.resolve("second probe api.jar"));
        arguments.getOtlpProbeClasspath().from(first.toFile(), second.toFile());
        String expected = first.toAbsolutePath() + File.pathSeparator + second.toAbsolutePath();
        List<String> providedArguments = new ArrayList<>();
        arguments.asArguments().forEach(providedArguments::add);

        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        if (!Files.isRegularFile(java)) {
            java = java.resolveSibling("java.exe");
        }

        Path testClasses = Path.of(PropertyProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path propertyFile = directory.resolve("child property.txt");
        Path outputFile = directory.resolve("child output.txt");
        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.addAll(providedArguments);
        command.add("-cp");
        command.add(testClasses.toString());
        command.add(PropertyProbe.class.getName());
        command.add(propertyFile.toString());

        // when
        Process child = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(outputFile.toFile())
                .start();
        try {
            boolean finished = child.waitFor(30, TimeUnit.SECONDS);

            // then
            assertThat(finished).as("Java child completed within the timeout").isTrue();
            assertThat(child.exitValue())
                    .as("Java child output: %s", Files.readString(outputFile, StandardCharsets.UTF_8))
                    .isZero();
            assertThat(Files.readString(propertyFile, StandardCharsets.UTF_8)).isEqualTo(expected);
            assertThat(providedArguments)
                    .filteredOn(argument -> argument.startsWith(PROBE_ARGUMENT_PREFIX))
                    .containsExactly(PROBE_ARGUMENT_PREFIX + "=" + expected);
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private static ControlledAgentTestArguments requiredArguments(Path directory) throws IOException {
        Files.createDirectories(directory);
        Project project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build();
        ControlledAgentTestArguments arguments = project.getObjects().newInstance(ControlledAgentTestArguments.class);
        arguments.getControlledAgentJar().set(Files.createFile(directory.resolve("controlled agent.jar")).toFile());
        arguments.getExtensionJar().set(Files.createFile(directory.resolve("packaged extension.jar")).toFile());
        arguments.getUpstreamAgentJar().set(Files.createFile(directory.resolve("upstream agent.jar")).toFile());
        arguments.getDistributionVersion().set("1.2.3");
        arguments.getExpectedOtelSdkVersion().set("1.66.0");
        arguments.getExpectedUpstreamCoordinate().set("io.opentelemetry.javaagent:synthetic-agent:1.0.0");
        return arguments;
    }

    public static final class PropertyProbe {

        public static void main(String[] arguments) throws IOException {
            Files.writeString(Path.of(arguments[0]), System.getProperty(PROBE_PROPERTY), StandardCharsets.UTF_8);
        }
    }
}
