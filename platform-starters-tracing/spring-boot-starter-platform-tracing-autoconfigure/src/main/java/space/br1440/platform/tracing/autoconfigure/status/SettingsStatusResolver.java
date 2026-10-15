package space.br1440.platform.tracing.autoconfigure.status;

import static java.util.Objects.requireNonNull;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceException;
import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceGuard;
import space.br1440.platform.tracing.autoconfigure.control.TracingConfigurationGuardException;
import space.br1440.platform.tracing.autoconfigure.control.settings.AcceptedSettings;
import space.br1440.platform.tracing.autoconfigure.status.TracingStatusSnapshot.SettingsStatus;

import lombok.extern.slf4j.Slf4j;

@Slf4j
final class SettingsStatusResolver {

    static final String NO_ACCEPTED_SETTINGS = "NO_ACCEPTED_SETTINGS";

    private final AcceptedSettingsStatusReader reader;
    private final AgentReadinessCoherenceGuard coherenceGuard;
    private final AtomicReference<String> lastFailureKind = new AtomicReference<>();

    SettingsStatusResolver(AcceptedSettingsStatusReader reader, AgentReadinessCoherenceGuard coherenceGuard) {
        this.reader = requireNonNull(reader, "reader");
        this.coherenceGuard = requireNonNull(coherenceGuard, "coherenceGuard");
    }

    Resolution resolve() {
        Optional<AcceptedSettings> accepted;
        try {
            accepted = reader.current();
        } catch (AgentReadinessCoherenceException coherenceFailure) {
            throw coherenceFailure;
        } catch (TracingConfigurationGuardException guardFailure) {
            return Resolution.agentUnavailable(coherenceGuard.terminalFailureCode());
        } catch (RuntimeException readFailure) {
            return unreadable(readFailure.getClass().getName());
        }

        return accepted.map(this::available).orElseGet(() -> unreadable(NO_ACCEPTED_SETTINGS));
    }

    private Resolution available(AcceptedSettings settings) {
        String previous = lastFailureKind.getAndSet(null);
        if (previous != null) {
            log.info("Accepted tracing settings are readable again; previousFailureKind={}", previous);
        }

        return Resolution.available(settings);
    }

    private Resolution unreadable(String failureKind) {
        String previous = lastFailureKind.getAndSet(failureKind);
        if (failureKind.equals(previous)) {
            log.debug("Accepted tracing settings remain unreadable; failureKind={}", failureKind);
        } else {
            log.warn("Accepted tracing settings are unreadable; failureKind={}", failureKind);
        }

        return Resolution.unreadable();
    }

    record Resolution(SettingsStatus status, String agentFailureCode) {

        static Resolution available(AcceptedSettings settings) {
            return new Resolution(
                    SettingsStatus.available(settings.revision(), settings.policy(), settings.authority().name()),
                    null
                );
        }

        static Resolution tracingDisabled() {
            return new Resolution(SettingsStatus.absent(SettingsAbsenceReason.TRACING_DISABLED), null);
        }

        static Resolution agentUnavailable(String failureCode) {
            return new Resolution(SettingsStatus.absent(SettingsAbsenceReason.AGENT_UNAVAILABLE), failureCode);
        }

        static Resolution unreadable() {
            return new Resolution(SettingsStatus.absent(SettingsAbsenceReason.SETTINGS_UNREADABLE), null);
        }
    }
}
