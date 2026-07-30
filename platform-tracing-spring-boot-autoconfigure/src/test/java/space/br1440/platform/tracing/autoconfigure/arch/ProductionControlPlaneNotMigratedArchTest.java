package space.br1440.platform.tracing.autoconfigure.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.autoconfigure.actuator.TracingActuatorEndpoint;
import space.br1440.platform.tracing.autoconfigure.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.test.arch.ArchitectureFitnessArchRules;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-4 FF-09a: production control plane uses PlatformTracingJmxClient, not the deleted SamplingControlClient.
 */
@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.autoconfigure",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class ProductionControlPlaneNotMigratedArchTest {

    @ArchTest
    static final ArchRule productionNoWireValidator =
            ArchitectureFitnessArchRules.PRODUCTION_AUTOCONFIGURE_NO_WIRE_VALIDATOR;

    @ArchTest
    static final ArchRule no_deleted_SamplingControlClient =
            noClasses()
                    .should().haveSimpleName("SamplingControlClient")
                    .because("SamplingControlClient удалён; Spring-side JMX-клиент — PlatformTracingJmxClient");

    @Test
    @DisplayName("PlatformTracingJmxClient exposes read-only domain awareness methods")
    void jmxClientReadOnlyMethodsPresent() {
        assertThat(hasPublicMethod(PlatformTracingJmxClient.class, "allMBeansAvailable")).isTrue();
        assertThat(hasPublicMethod(PlatformTracingJmxClient.class, "getMBeansStatus")).isTrue();
        assertThat(hasPublicMethod(PlatformTracingJmxClient.class, "getCurrentRatio")).isTrue();
        assertThat(hasPublicMethod(PlatformTracingJmxClient.class, "isSamplerEnabled")).isTrue();
    }

    @Test
    @DisplayName("PlatformTracingJmxClient no longer exposes JMX mutation methods")
    void jmxClientMutationMethodsAbsent() {
        assertThat(hasPublicMethod(PlatformTracingJmxClient.class, "setRatio", double.class)).isFalse();
        assertThat(hasPublicMethod(PlatformTracingJmxClient.class, "updateSamplingPolicy",
                space.br1440.platform.tracing.autoconfigure.sampling.SamplingRuntimeConfig.class))
                .isFalse();
        assertThat(hasPublicMethod(PlatformTracingJmxClient.class, "setSamplerEnabled", boolean.class)).isFalse();
    }

    @Test
    @DisplayName("TracingActuatorEndpoint remains a production entry point")
    void productionEntryPointsExist() {
        assertThat(TracingActuatorEndpoint.class).isNotNull();
    }

    @Test
    @DisplayName("RuntimeConfigApplier class no longer exists in production")
    void runtimeConfigApplierDeleted() {
        assertThat(productionClassPresent("space.br1440.platform.tracing.autoconfigure.RuntimeConfigApplier"))
                .as("RuntimeConfigApplier should have been deleted from production")
                .isFalse();
    }

    @Test
    @DisplayName("TracingRefreshScopeAutoConfiguration class no longer exists in production")
    void tracingRefreshScopeAutoConfigurationDeleted() {
        assertThat(productionClassPresent(
                "space.br1440.platform.tracing.autoconfigure.TracingRefreshScopeAutoConfiguration"))
                .as("TracingRefreshScopeAutoConfiguration should have been deleted from production")
                .isFalse();
    }

    @Test
    @DisplayName("SamplingControlClient class no longer exists in production")
    void samplingControlClientDeleted() {
        assertThat(productionClassPresent(
                "space.br1440.platform.tracing.autoconfigure.sampling.SamplingControlClient"))
                .as("SamplingControlClient should have been deleted from production")
                .isFalse();
    }

    private static boolean productionClassPresent(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static boolean hasPublicMethod(Class<?> type, String name, Class<?>... paramTypes) {
        return Arrays.stream(type.getMethods())
                .anyMatch(m -> m.getName().equals(name) && Arrays.equals(m.getParameterTypes(), paramTypes));
    }
}
