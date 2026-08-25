package space.br1440.platform.tracing.autoconfigure.control.authority;

import lombok.RequiredArgsConstructor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import space.br1440.platform.tracing.autoconfigure.bootstrap.PlatformEnvironmentPosture;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapSettings;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingConfigurationAuthority;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public final class TracingConfigurationAuthorityGuard {

    private final TracingConfigurationAuthority authority;
    private final PlatformEnvironmentPosture posture;
    private final boolean tracingEnabled;

    public TracingConfigurationAuthorityGuard(TracingBootstrapSettings settings) {
        this(settings.authority(), settings.posture(), settings.tracingEnabled());
    }

    public TracingConfigurationAuthorityGuard validate() {
        if (!tracingEnabled) {
            if (authority == TracingConfigurationAuthority.CONFIG_SERVER) {
                throw new TracingConfigurationGuardException(
                        "Tracing disabled is incompatible with CONFIG_SERVER authority.");
            }

            if (authority == TracingConfigurationAuthority.ACTUATOR) {
                throw new TracingConfigurationGuardException(
                        "Tracing disabled is incompatible with ACTUATOR authority.");
            }

            return this;
        }
        if (authority == TracingConfigurationAuthority.ACTUATOR) {
            if (posture == PlatformEnvironmentPosture.SHARED) {
                throw new TracingConfigurationGuardException(
                        "ACTUATOR authority is forbidden in SHARED posture. Use CONFIG_SERVER or HELM.");
            }

            if (posture == PlatformEnvironmentPosture.PRODUCTION) {
                throw new TracingConfigurationGuardException(
                        "ACTUATOR authority is forbidden in PRODUCTION posture. Use CONFIG_SERVER or HELM.");
            }
        }

        return this;
    }

    public boolean isActuatorMutationSurfaceAllowed() {
        return tracingEnabled
                && authority == TracingConfigurationAuthority.ACTUATOR
                && posture == PlatformEnvironmentPosture.LOCAL;
    }

    public TracingConfigurationAuthority authority() {
        return authority;
    }

    public PlatformEnvironmentPosture posture() {
        return posture;
    }

    public boolean isTracingEnabled() {
        return tracingEnabled;
    }
}
