package space.br1440.platform.tracing.autoconfigure.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;

import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.control.management.TracingControlEndpoint;
import space.br1440.platform.tracing.autoconfigure.status.TracingStatusEndpoint;

/**
 * P6.4 / P6.10 fragment: legacy production Actuator write surface is physically absent.
 *
 * <p>Локальная запись выполняется отдельным {@link TracingControlEndpoint}; status endpoint
 * остаётся read-only. Физическое отсутствие write endpoint вне ACTUATOR + LOCAL проверяется
 * в {@code TracingControlEndpointAutoConfigurationTest}.
 */
class P64ActuatorWriteAbsenceTest {

    @Test
    @DisplayName("TracingStatusEndpoint remains read-only")
    void statusEndpointHasNoWriteOperations() {
        assertNoWriteOperations(TracingStatusEndpoint.class);
        assertThat(Arrays.stream(TracingStatusEndpoint.class.getDeclaredMethods())
                .map(Method::getName))
                .doesNotContain("updateTracing", "assertMutationAllowed");
    }

    @Test
    @DisplayName("TracingControlEndpoint declares exactly one @WriteOperation")
    void controlEndpointHasSingleWriteOperation() {
        assertThat(Arrays.stream(TracingControlEndpoint.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(WriteOperation.class)))
                .hasSize(1);
    }

    @Test
    @DisplayName("Legacy tracing and tracingPolicy endpoints are removed")
    void legacyReadEndpointsAreRemoved() {
        assertThat(classPresent(
                "space.br1440.platform.tracing.autoconfigure.actuator.LegacyTracingDiagnosticsEndpoint"))
                .isFalse();
        assertThat(classPresent(
                "space.br1440.platform.tracing.autoconfigure.configuration.actuator.AcceptedPolicyActuatorEndpoint"))
                .isFalse();
    }

    private static boolean classPresent(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException expected) {
            return false;
        }
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

    private static void assertNoWriteOperations(Class<?> endpointClass) {
        long writeOps = Arrays.stream(endpointClass.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(WriteOperation.class))
                .count();
        assertThat(writeOps)
                .as("%s must not declare @WriteOperation", endpointClass.getName())
                .isZero();
    }
}
