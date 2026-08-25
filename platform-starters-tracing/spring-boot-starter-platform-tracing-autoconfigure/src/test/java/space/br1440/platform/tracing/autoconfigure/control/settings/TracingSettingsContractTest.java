package space.br1440.platform.tracing.autoconfigure.control.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import space.br1440.platform.tracing.autoconfigure.bootstrap.PlatformEnvironmentPosture;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapSettingsResolver;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingConfigurationAuthority;
import space.br1440.platform.tracing.autoconfigure.control.authority.TracingConfigurationAuthorityGuard;
import space.br1440.platform.tracing.autoconfigure.control.authority.TracingConfigurationGuardException;

class TracingSettingsContractTest {

    @Test
    void authorityHasOnlySupportedValues() {
        // given
        TracingConfigurationAuthority[] values = TracingConfigurationAuthority.values();

        // when
        // then
        assertThat(values).containsExactly(
                TracingConfigurationAuthority.HELM,
                TracingConfigurationAuthority.CONFIG_SERVER,
                TracingConfigurationAuthority.ACTUATOR);
    }

    @Test
    void postureHasOnlySupportedValues() {
        // given
        PlatformEnvironmentPosture[] values = PlatformEnvironmentPosture.values();

        // when
        // then
        assertThat(values).containsExactly(
                PlatformEnvironmentPosture.LOCAL,
                PlatformEnvironmentPosture.SHARED,
                PlatformEnvironmentPosture.PRODUCTION);
    }

    @Test
    void authorityAndPostureRemainOrthogonal() {
        // given
        var authorityNames = assertThat(TracingConfigurationAuthority.values())
                .extracting(Enum::name);
        var postureNames = assertThat(PlatformEnvironmentPosture.values())
                .extracting(Enum::name);

        // when
        // then
        authorityNames.doesNotContain("LOCAL", "SHARED", "PRODUCTION");
        postureNames.doesNotContain("HELM", "CONFIG_SERVER", "ACTUATOR");
    }

    @Test
    void actuatorIsRejectedForSharedPosture() {
        // given
        var guard = new TracingConfigurationAuthorityGuard(
                TracingConfigurationAuthority.ACTUATOR,
                PlatformEnvironmentPosture.SHARED,
                true);

        // when
        // then
        assertThatThrownBy(guard::validate)
                .isInstanceOf(TracingConfigurationGuardException.class);
    }

    @Test
    void actuatorIsRejectedForProductionPosture() {
        // given
        var guard = new TracingConfigurationAuthorityGuard(
                TracingConfigurationAuthority.ACTUATOR,
                PlatformEnvironmentPosture.PRODUCTION,
                true);

        // when
        // then
        assertThatThrownBy(guard::validate)
                .isInstanceOf(TracingConfigurationGuardException.class);
    }

    @Test
    void actuatorIsAllowedForLocalPosture() {
        // given
        var guard = new TracingConfigurationAuthorityGuard(
                TracingConfigurationAuthority.ACTUATOR,
                PlatformEnvironmentPosture.LOCAL,
                true);

        // when
        guard.validate();

        // then
        assertThat(guard.isActuatorMutationSurfaceAllowed()).isTrue();
    }

    @Test
    void missingAuthorityIsRejected() {
        // given
        var environment = new MockEnvironment().withProperty("platform.environment", "LOCAL");

        // when
        // then
        assertThatThrownBy(() -> TracingBootstrapSettingsResolver.resolve(environment))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void disabledTracingWithConfigServerIsRejected() {
        // given
        var environment = new MockEnvironment()
                .withProperty("platform.tracing.configuration.authority", "CONFIG_SERVER")
                .withProperty("platform.environment", "LOCAL")
                .withProperty("platform.tracing.enabled", "false");
        var guard = new TracingConfigurationAuthorityGuard(
                TracingBootstrapSettingsResolver.resolve(environment));

        // when
        // then
        assertThatThrownBy(guard::validate)
                .isInstanceOf(TracingConfigurationGuardException.class);
    }

    @Test
    void postureAliasIsRejected() {
        // given
        var environment = new MockEnvironment()
                .withProperty("platform.tracing.configuration.authority", "HELM")
                .withProperty("platform.environment", "dev");

        // when
        // then
        assertThatThrownBy(() -> TracingBootstrapSettingsResolver.resolve(environment))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void postureTypoIsRejected() {
        // given
        var environment = new MockEnvironment()
                .withProperty("platform.tracing.configuration.authority", "HELM")
                .withProperty("platform.environment", "LOACL");

        // when
        // then
        assertThatThrownBy(() -> TracingBootstrapSettingsResolver.resolve(environment))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void partialSnapshotIsRejected() {
        // given
        var builder = CompleteTracingSettings.builder()
                .samplingEnabled(true)
                .samplingRatio(0.5);

        // when
        // then
        assertThatThrownBy(builder::build)
                .isInstanceOf(TracingSettingsManifestException.class);
    }

    @Test
    void completeSnapshotIsBuilt() {
        // given
        var builder = CompleteTracingSettings.builder()
                .samplingEnabled(true)
                .samplingRatio(0.5)
                .samplingRouteRatios(Map.of())
                .validationEnabled(true)
                .validationStrict(false)
                .exporterEnabled(true)
                .traceControlHeadersEnabled(true)
                .diagnosticsLogLevel("INFO");

        // when
        CompleteTracingSettings settings = builder.build();

        // then
        assertThat(settings.samplingEnabled()).isTrue();
        assertThat(settings.samplingRatio()).isEqualTo(0.5);
        assertThat(settings.samplingRouteRatios()).isEmpty();
    }

    @Test
    void singleLeafMutationPreservesOtherSettings() {
        // given
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

        // when
        CompleteTracingSettings mutated = original.withExporterEnabled(false);

        // then
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
