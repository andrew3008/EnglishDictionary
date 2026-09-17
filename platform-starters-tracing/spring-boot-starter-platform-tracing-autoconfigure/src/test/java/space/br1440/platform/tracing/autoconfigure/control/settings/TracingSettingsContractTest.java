package space.br1440.platform.tracing.autoconfigure.control.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapSettingsResolver;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingConfigurationAuthority;
import space.br1440.platform.tracing.autoconfigure.control.authority.TracingConfigurationAuthorityGuard;
import space.br1440.platform.tracing.autoconfigure.control.authority.TracingConfigurationGuardException;

class TracingSettingsContractTest {

    @Test
    void authorityHasOnlySupportedValues() {
        TracingConfigurationAuthority[] values = TracingConfigurationAuthority.values();

        assertThat(values).containsExactly(
                TracingConfigurationAuthority.HELM,
                TracingConfigurationAuthority.CONFIG_SERVER,
                TracingConfigurationAuthority.ACTUATOR);
    }

    @Test
    void actuatorIsAllowedWithoutEnvironmentGate() {
        var guard = new TracingConfigurationAuthorityGuard(
                TracingConfigurationAuthority.ACTUATOR,
                true);

        guard.validate();

        assertThat(guard.authority()).isEqualTo(TracingConfigurationAuthority.ACTUATOR);
        assertThat(guard.isTracingEnabled()).isTrue();
    }

    @Test
    void missingAuthorityIsRejected() {
        var environment = new MockEnvironment();

        assertThatThrownBy(() -> TracingBootstrapSettingsResolver.resolve(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing platform.tracing.configuration.authority");
    }

    @Test
    void emptyAuthorityIsRejected() {
        var environment = new MockEnvironment()
                .withProperty("platform.tracing.configuration.authority", "  ");

        assertThatThrownBy(() -> TracingBootstrapSettingsResolver.resolve(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing platform.tracing.configuration.authority");
    }

    @Test
    void unknownAuthorityIsRejected() {
        var environment = new MockEnvironment()
                .withProperty("platform.tracing.configuration.authority", "DEV_ACTUATOR");

        assertThatThrownBy(() -> TracingBootstrapSettingsResolver.resolve(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid platform.tracing.configuration.authority");
    }

    @Test
    void platformEnvironmentDoesNotSelectAuthority() {
        var environment = new MockEnvironment()
                ;

        assertThatThrownBy(() -> TracingBootstrapSettingsResolver.resolve(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing platform.tracing.configuration.authority");
    }

    @Test
    void deploymentEnvironmentNameDoesNotSelectAuthority() {
        var environment = new MockEnvironment()
                .withProperty("deployment.environment.name", "production");

        assertThatThrownBy(() -> TracingBootstrapSettingsResolver.resolve(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing platform.tracing.configuration.authority");
    }

    @Test
    void disabledTracingWithConfigServerIsRejected() {
        var environment = new MockEnvironment()
                .withProperty("platform.tracing.configuration.authority", "CONFIG_SERVER")
                .withProperty("platform.tracing.enabled", "false");
        var guard = new TracingConfigurationAuthorityGuard(
                TracingBootstrapSettingsResolver.resolve(environment));

        assertThatThrownBy(guard::validate)
                .isInstanceOf(TracingConfigurationGuardException.class);
    }

    @Test
    void partialSnapshotIsRejected() {
        var builder = CompleteTracingSettings.builder()
                .samplingEnabled(true)
                .samplingRatio(0.5);

        assertThatThrownBy(builder::build)
                .isInstanceOf(TracingSettingsManifestException.class);
    }

    @Test
    void completeSnapshotIsBuilt() {
        var builder = CompleteTracingSettings.builder()
                .samplingEnabled(true)
                .samplingRatio(0.5)
                .samplingRouteRatios(Map.of())
                .validationEnabled(true)
                .validationStrict(false)
                .exporterEnabled(true)
                .traceControlHeadersEnabled(true)
                .diagnosticsLogLevel("INFO");

        CompleteTracingSettings settings = builder.build();

        assertThat(settings.samplingEnabled()).isTrue();
        assertThat(settings.samplingRatio()).isEqualTo(0.5);
        assertThat(settings.samplingRouteRatios()).isEmpty();
    }

    @Test
    void singleLeafMutationPreservesOtherSettings() {
        CompleteTracingSettings original = CompleteTracingSettings.builder()
                .samplingEnabled(true)
                .samplingRatio(1.0)
                .samplingRouteRatios(Map.of("checkout", 0.5, "search", 0.3))
                .validationEnabled(true)
                .validationStrict(false)
                .exporterEnabled(true)
                .traceControlHeadersEnabled(true)
                .diagnosticsLogLevel("INFO")
                .build();

        CompleteTracingSettings mutated = original.withExporterEnabled(false);

        assertThat(mutated.exporterEnabled()).isFalse();
        assertThat(mutated.samplingRouteRatios())
                .containsExactlyInAnyOrderEntriesOf(Map.of("checkout", 0.5, "search", 0.3));
        assertThat(mutated.samplingEnabled()).isTrue();
        assertThat(mutated.validationEnabled()).isTrue();
        assertThat(mutated.validationStrict()).isFalse();
        assertThat(mutated.traceControlHeadersEnabled()).isTrue();
        assertThat(mutated.diagnosticsLogLevel()).isEqualTo("INFO");
    }
}
