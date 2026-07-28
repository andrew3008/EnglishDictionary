package space.br1440.platform.tracing.autoconfigure.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

class P4ArchitectureContractTest {

    @Test
    void controlEndpointDeclaresExactlyEightTypedWrites() {
        List<String> operations = Arrays.stream(TracingActuatorControlEndpoint.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PostMapping.class))
                .map(Method::getName)
                .sorted()
                .toList();

        assertThat(operations).containsExactlyInAnyOrder(
                "setSamplingEnabled",
                "setSamplingRatio",
                "replaceSamplingRouteRatios",
                "setValidationEnabled",
                "setValidationStrict",
                "setExporterEnabled",
                "setTraceControlHeadersEnabled",
                "setDiagnosticsLogLevel");
        assertThat(operations).noneMatch(name ->
                name.equals("setProperty")
                        || name.equals("apply")
                        || name.equals("patch")
                        || name.equals("execute"));
    }

    @Test
    void p4AdaptersAreNotRegisteredInSupportedAutoConfigurationBeforeP6()
            throws Exception {
        Path imports = Path.of(
                "src/main/resources/META-INF/spring/"
                        + "org.springframework.boot.autoconfigure.AutoConfiguration.imports");
        String content = Files.readString(imports);

        assertThat(content)
                .doesNotContain("TracingPolicyAdaptersAutoConfiguration")
                .doesNotContain("TracingActuatorControlEndpoint");
    }

    @Test
    void productionSourcesDoNotUseProfileHeuristicsOrDevPrefixedTypes() throws Exception {
        Path sources = Path.of("src/main/java");
        List<Path> javaSources;
        try (java.util.stream.Stream<Path> stream = Files.walk(sources)) {
            javaSources = stream.filter(path -> path.toString().endsWith(".java")).toList();
        }

        for (Path source : javaSources) {
            String text = Files.readString(source);
            assertThat(source.getFileName().toString()).doesNotStartWith("Dev");
            assertThat(text)
                    .doesNotContain("spring.profiles.active")
                    .doesNotContain("@Profile(\"dev\")")
                    .doesNotContain("@Profile(\"local\")");
        }
    }
}
