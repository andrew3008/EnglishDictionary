package space.br1440.platform.tracing.autoconfigure.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.support.SdkMode;

class TracingConfigurationAuthorityGuardTest {

    private final TracingConfigurationAuthorityGuard guard =
            new TracingConfigurationAuthorityGuard();

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
        TracingConfigurationAuthorityGuard.AuthorityPostureDecision decision = guard.validate(
                SdkMode.AGENT,
                TracingConfigurationAuthority.ACTUATOR,
                "LOCAL",
                true,
                false);

        assertThat(decision.actuatorWriteSurfaceAllowed()).isTrue();
        assertThat(decision.platformEnvironment()).isEqualTo(PlatformEnvironment.LOCAL);
    }

    @Test
    void rejectsActuatorForSharedAndProductionPostures() {
        assertThatThrownBy(() -> guard.validate(
                SdkMode.AGENT,
                TracingConfigurationAuthority.ACTUATOR,
                "SHARED",
                true,
                false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("platform.environment=LOCAL");
        assertThatThrownBy(() -> guard.validate(
                SdkMode.AGENT,
                TracingConfigurationAuthority.ACTUATOR,
                "PRODUCTION",
                true,
                false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("platform.environment=LOCAL");
    }

    @Test
    void disabledModeAcceptsOnlyHelmAndNeverDiscoversWriter() {
        TracingConfigurationAuthorityGuard.AuthorityPostureDecision decision = guard.validate(
                SdkMode.DISABLED,
                TracingConfigurationAuthority.HELM,
                null,
                false,
                false);

        assertThat(decision.actuatorWriteSurfaceAllowed()).isFalse();
        assertThat(decision.configServerDeliveryRequired()).isFalse();
        assertThatThrownBy(() -> guard.validate(
                SdkMode.DISABLED,
                TracingConfigurationAuthority.CONFIG_SERVER,
                "SHARED",
                false,
                false))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> guard.validate(
                SdkMode.DISABLED,
                TracingConfigurationAuthority.ACTUATOR,
                "LOCAL",
                true,
                false))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void springProfileNamesDoNotAffectTrustedPosture() {
        assertThat(PlatformEnvironment.from("dev")).isEqualTo(PlatformEnvironment.UNKNOWN);
        assertThat(PlatformEnvironment.from("arbitrary-profile"))
                .isEqualTo(PlatformEnvironment.UNKNOWN);
    }
}
