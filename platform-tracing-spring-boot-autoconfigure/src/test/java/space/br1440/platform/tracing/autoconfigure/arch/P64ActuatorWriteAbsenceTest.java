package space.br1440.platform.tracing.autoconfigure.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.actuator.TracingActuatorEndpoint;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6.4 / P6.10 fragment: legacy production Actuator write surface is physically absent.
 */
class P64ActuatorWriteAbsenceTest {

    @Test
    @DisplayName("Legacy TracingActuatorEndpoint has zero @WriteOperation methods")
    void legacyEndpoint_hasNoWriteOperations() {
        long writeOps = Arrays.stream(TracingActuatorEndpoint.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(WriteOperation.class))
                .count();
        assertThat(writeOps).isZero();
        assertThat(Arrays.stream(TracingActuatorEndpoint.class.getDeclaredMethods())
                .map(Method::getName))
                .doesNotContain("updateTracing", "assertMutationAllowed");
    }

    @Test
    @DisplayName("TracingProperties no longer binds actuator.mutation-enabled")
    void tracingProperties_hasNoActuatorMutationBinding() throws Exception {
        assertThat(Arrays.stream(TracingProperties.class.getDeclaredClasses())
                .map(Class::getSimpleName))
                .doesNotContain("Actuator");
        assertThat(Arrays.stream(TracingProperties.class.getDeclaredFields())
                .map(Field::getName))
                .doesNotContain("actuator");

        Path propertiesSource = Path.of("src/main/java/space/br1440/platform/tracing/autoconfigure/TracingProperties.java");
        String source = Files.readString(propertiesSource);
        assertThat(source).doesNotContain("mutation-enabled").doesNotContain("mutationEnabled");
    }

    @Test
    @DisplayName("Production main sources contain no mutation-enabled binding")
    void productionMain_hasNoMutationEnabledLiteral() throws Exception {
        Path main = Path.of("src/main");
        try (Stream<Path> paths = Files.walk(main)) {
            long hits = paths
                    .filter(path -> path.toString().endsWith(".java")
                            || path.toString().endsWith(".imports")
                            || path.toString().endsWith(".properties")
                            || path.toString().endsWith(".json"))
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        try {
                            return Files.readString(path).contains("mutation-enabled")
                                    || Files.readString(path).contains("mutationEnabled");
                        } catch (Exception exception) {
                            throw new IllegalStateException(path.toString(), exception);
                        }
                    })
                    .count();
            assertThat(hits).isZero();
        }
    }
}
