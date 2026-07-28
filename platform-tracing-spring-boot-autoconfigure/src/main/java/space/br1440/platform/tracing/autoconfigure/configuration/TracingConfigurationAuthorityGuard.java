package space.br1440.platform.tracing.autoconfigure.configuration;

import java.util.Objects;

import space.br1440.platform.tracing.autoconfigure.support.SdkMode;

final class TracingConfigurationAuthorityGuard {

    AuthorityPostureDecision validate(
            SdkMode mode,
            TracingConfigurationAuthority authority,
            String platformEnvironment,
            boolean actuatorControlEnabled,
            boolean legacyActuatorMutationEnabled) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(authority, "authority");
        PlatformEnvironment environment = PlatformEnvironment.from(platformEnvironment);

        if (mode == SdkMode.DISABLED) {
            if (authority != TracingConfigurationAuthority.HELM) {
                throw invalid(mode, authority, "DISABLED допускает только HELM authority");
            }
            if (actuatorControlEnabled) {
                throw invalid(mode, authority, "actuator.control-enabled требует ACTUATOR authority");
            }
            return new AuthorityPostureDecision(mode, authority, environment, false, false);
        }

        if (mode != SdkMode.AGENT) {
            throw invalid(mode, authority, "поддерживаются только AGENT и DISABLED");
        }
        if (authority == TracingConfigurationAuthority.ACTUATOR) {
            if (environment != PlatformEnvironment.LOCAL) {
                throw invalid(mode, authority, "ACTUATOR разрешён только при platform.environment=LOCAL");
            }
            if (!actuatorControlEnabled) {
                throw invalid(mode, authority, "ACTUATOR требует actuator.control-enabled=true");
            }
            if (legacyActuatorMutationEnabled) {
                throw invalid(mode, authority, "legacy Actuator mutation и ACTUATOR несовместимы");
            }
            return new AuthorityPostureDecision(mode, authority, environment, false, true);
        }
        if (actuatorControlEnabled) {
            throw invalid(mode, authority, "actuator.control-enabled требует ACTUATOR authority");
        }
        return new AuthorityPostureDecision(
                mode,
                authority,
                environment,
                authority == TracingConfigurationAuthority.CONFIG_SERVER,
                false);
    }

    private static IllegalStateException invalid(
            SdkMode mode,
            TracingConfigurationAuthority authority,
            String reason) {
        return new IllegalStateException(
                "Unsupported tracing configuration posture: mode="
                        + mode
                        + ", authority="
                        + authority
                        + "; "
                        + reason);
    }

    record AuthorityPostureDecision(
            SdkMode mode,
            TracingConfigurationAuthority authority,
            PlatformEnvironment platformEnvironment,
            boolean configServerDeliveryRequired,
            boolean actuatorWriteSurfaceAllowed) {
    }
}
