package space.br1440.platform.tracing.autoconfigure.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.support.SdkMode;

class TracingConfigurationAuthorityGuardTest {

    @Test
    void exposesExactTechnologicalAuthorities() {
        assertThat(TracingConfigurationAuthority.values())
                .containsExactly(
                        TracingConfigurationAuthority.HELM,
                        TracingConfigurationAuthority.CONFIG_SERVER,
                        TracingConfigurationAuthority.ACTUATOR);
    }

    @Test
    void permitsActuatorOnlyForExplicitLocalPosture() {
        TracingConfigurationAuthorityGuard.AuthorityPostureDecision decision =
                TracingConfigurationAuthorityGuard.validate(
                SdkMode.AGENT,
                TracingConfigurationAuthority.ACTUATOR,
                "LOCAL",
                false);

        assertThat(decision.actuatorWriteSurfaceAllowed()).isTrue();
        assertThat(decision.platformEnvironment()).isEqualTo(PlatformEnvironment.LOCAL);
    }

    @Test
    void rejectsActuatorForSharedAndProductionPostures() {
        assertThatThrownBy(() -> TracingConfigurationAuthorityGuard.validate(
                SdkMode.AGENT,
                TracingConfigurationAuthority.ACTUATOR,
                "SHARED",
                false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("platform.environment=LOCAL");
        assertThatThrownBy(() -> TracingConfigurationAuthorityGuard.validate(
                SdkMode.AGENT,
                TracingConfigurationAuthority.ACTUATOR,
                "PRODUCTION",
                false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("platform.environment=LOCAL");
    }

    @Test
    void disabledModeAcceptsOnlyHelmAndNeverDiscoversWriter() {
        TracingConfigurationAuthorityGuard.AuthorityPostureDecision decision =
                TracingConfigurationAuthorityGuard.validate(
                SdkMode.DISABLED,
                TracingConfigurationAuthority.HELM,
                null,
                false);

        assertThat(decision.actuatorWriteSurfaceAllowed()).isFalse();
        assertThat(decision.configServerDeliveryRequired()).isFalse();
        assertThatThrownBy(() -> TracingConfigurationAuthorityGuard.validate(
                SdkMode.DISABLED,
                TracingConfigurationAuthority.CONFIG_SERVER,
                "SHARED",
                false))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TracingConfigurationAuthorityGuard.validate(
                SdkMode.DISABLED,
                TracingConfigurationAuthority.ACTUATOR,
                "LOCAL",
                false))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unknownPlatformEnvironmentFailsFast() {
        assertThatThrownBy(() -> PlatformEnvironment.from("PRODCUTION"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported platform.environment");
        assertThatThrownBy(() -> PlatformEnvironment.from("dev"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(PlatformEnvironment.from(null)).isEqualTo(PlatformEnvironment.UNKNOWN);
    }
}
