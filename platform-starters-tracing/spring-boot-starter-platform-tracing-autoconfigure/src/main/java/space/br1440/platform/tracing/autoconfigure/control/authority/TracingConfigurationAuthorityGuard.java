package space.br1440.platform.tracing.autoconfigure.control.authority;

import lombok.RequiredArgsConstructor;

import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapSettings;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingConfigurationAuthority;

@RequiredArgsConstructor
public final class TracingConfigurationAuthorityGuard {

    private final TracingConfigurationAuthority authority;
    private final boolean tracingEnabled;

    public TracingConfigurationAuthorityGuard(TracingBootstrapSettings settings) {
        this(settings.authority(), settings.tracingEnabled());
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
        }

        return this;
    }

    public TracingConfigurationAuthority authority() {
        return authority;
    }

    public boolean isTracingEnabled() {
        return tracingEnabled;
    }
}
