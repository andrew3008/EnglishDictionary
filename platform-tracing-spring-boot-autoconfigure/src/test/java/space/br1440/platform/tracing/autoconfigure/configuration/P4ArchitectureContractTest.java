package space.br1440.platform.tracing.autoconfigure.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

class P4ArchitectureContractTest {

    @Test
    void controlSurfaceHasEightStaticRoutesWithoutGenericDispatcher() throws Exception {
        assertThat(TracingActuatorControlController.class)
                .hasAnnotation(RestController.class)
                .hasAnnotation(RequestMapping.class);
        assertThat(Arrays.stream(TracingActuatorControlController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PostMapping.class)
                        || method.isAnnotationPresent(PutMapping.class)))
                .hasSize(8);

        String source = Files.readString(Path.of(
                "src/main/java/space/br1440/platform/tracing/autoconfigure/configuration/"
                        + "TracingActuatorControlController.java"));
        assertThat(source)
                .doesNotContain("RestControllerEndpoint")
                .doesNotContain("@WebEndpoint")
                .doesNotContain("setProperty(")
                .doesNotContain("@Selector")
                .doesNotContain("String operation")
                .doesNotContain("Object value")
                .doesNotContain("switch (operation)")
                .contains(
                        "@PostMapping(\"/sampling/enabled\")",
                        "@PostMapping(\"/sampling/ratio\")",
                        "@PutMapping(\"/sampling/route-ratios\")",
                        "@PostMapping(\"/validation/enabled\")",
                        "@PostMapping(\"/validation/strict\")",
                        "@PostMapping(\"/exporter/enabled\")",
                        "@PostMapping(\"/propagation/trace-control-headers\")",
                        "@PostMapping(\"/diagnostics/log-level\")");
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
                .doesNotContain("TracingActuatorControlController")
                .doesNotContain("TracingActuatorControlManagementContextConfiguration");

        Path managementImports = Path.of(
                "src/main/resources/META-INF/spring/"
                        + "org.springframework.boot.actuate.autoconfigure.web."
                        + "ManagementContextConfiguration.imports");
        assertThat(managementImports).doesNotExist();
    }

    @Test
    void readAndWriteCapabilitiesRemainStructurallySeparated() {
        assertThat(AcceptedTracingPolicyReader.class)
                .isAssignableFrom(PrivateControlAcceptedTracingPolicyReader.class);
        assertThat(TracingPolicySnapshotClient.class.isAssignableFrom(
                PrivateControlAcceptedTracingPolicyReader.class)).isFalse();
        assertThat(TracingPolicySnapshotClient.class)
                .isAssignableFrom(PrivateControlTracingPolicyClient.class);
        assertThat(AcceptedTracingPolicyReader.class.isAssignableFrom(
                PrivateControlTracingPolicyClient.class)).isFalse();
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
