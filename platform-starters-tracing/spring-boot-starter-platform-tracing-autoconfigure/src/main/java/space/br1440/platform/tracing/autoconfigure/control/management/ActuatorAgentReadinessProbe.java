package space.br1440.platform.tracing.autoconfigure.control.management;

import org.springframework.context.SmartLifecycle;

import space.br1440.platform.tracing.autoconfigure.control.TracingConfigurationGuardException;
import space.br1440.platform.tracing.autoconfigure.control.settings.AcceptedTracingSettingsReader;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsContractViolationException;

import lombok.extern.slf4j.Slf4j;

@Slf4j
final class ActuatorAgentReadinessProbe implements SmartLifecycle {

    private final AcceptedTracingSettingsReader reader;

    private volatile boolean running;

    ActuatorAgentReadinessProbe(AcceptedTracingSettingsReader reader) {
        this.reader = reader;
    }

    @Override
    public void start() {
        try {
            reader.current()
                    .orElseThrow(() -> new TracingConfigurationGuardException("""
                            [readiness probe] ACTUATOR control channel requires a complete accepted \
                            snapshot at startup. current() returned empty — Agent \
                            is unreachable or has not yet committed an accepted policy. \
                            Application tracing-configuration readiness is NOT achieved.""")
                    );
        } catch (TracingSettingsContractViolationException e) {
            throw new TracingSettingsContractViolationException("""
                    [readiness probe] FATAL: AcceptedPolicySnapshot is unavailable or \
                    malformed/incompatible. Startup is BLOCKED until a compatible Agent \
                    is attached. Original cause:\s""" + e.getMessage(), e);
        }

        running = true;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
