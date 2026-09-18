package space.br1440.platform.tracing.otel.javaagent.control;

import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

import space.br1440.platform.tracing.otel.javaagent.control.settings.SettingsCommitOutcome;
import space.br1440.platform.tracing.otel.javaagent.control.settings.SettingsCommitResult;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateStatus;

public final class AcceptedPolicyMutationAccess {

    private AcceptedPolicyMutationAccess() {
    }

    public static SettingsUpdateStatus applySampling(TracingSettingsSource access,
                                                     boolean enabled,
                                                     double ratio,
                                                     Map<String, Double> routeRatios) {
        Objects.requireNonNull(access, "access");

        TracingSettingsRevision witnessed = access.store().current();
        TracingSettings current = witnessed.policy();
        if (enabled == current.samplingEnabled()
                && Double.compare(ratio, current.samplingRatio()) == 0
                && Objects.equals(routeRatios, current.samplingRouteRatios())) {
            return SettingsUpdateStatus.UNCHANGED;
        }

        TracingSettings candidate;
        try {
            candidate = TracingSettings.of(
                    enabled,
                    ratio,
                    routeRatios,
                    current.validationEnabled(),
                    current.validationStrict(),
                    current.exporterEnabled(),
                    current.traceControlHeadersEnabled(),
                    current.diagnosticsLogLevel()
            );
        } catch (IllegalArgumentException | NullPointerException exception) {
            return SettingsUpdateStatus.REJECTED;
        }

        return commit(access, witnessed, candidate);
    }

    public static SettingsUpdateStatus applyValidation(TracingSettingsSource access,
                                                       boolean enabled,
                                                       boolean strict) {
        Objects.requireNonNull(access, "access");

        return applyCandidate(
                access,
                current -> TracingSettings.of(
                        current.samplingEnabled(),
                        current.samplingRatio(),
                        current.samplingRouteRatios(),
                        enabled,
                        strict,
                        current.exporterEnabled(),
                        current.traceControlHeadersEnabled(),
                        current.diagnosticsLogLevel()
                )
        );
    }

    public static SettingsUpdateStatus applyExporter(TracingSettingsSource access, boolean enabled) {
        return applyCandidate(
                access,
                current -> TracingSettings.of(
                        current.samplingEnabled(),
                        current.samplingRatio(),
                        current.samplingRouteRatios(),
                        current.validationEnabled(),
                        current.validationStrict(),
                        enabled,
                        current.traceControlHeadersEnabled(),
                        current.diagnosticsLogLevel()
                )
        );
    }

    public static SettingsUpdateStatus applyTraceControlHeaders(TracingSettingsSource access,
                                                                boolean enabled) {
        return applyCandidate(
                access,
                current -> TracingSettings.of(
                        current.samplingEnabled(),
                        current.samplingRatio(),
                        current.samplingRouteRatios(),
                        current.validationEnabled(),
                        current.validationStrict(),
                        current.exporterEnabled(),
                        enabled,
                        current.diagnosticsLogLevel()
                )
        );
    }

    private static SettingsUpdateStatus applyCandidate(TracingSettingsSource access,
                                                       UnaryOperator<TracingSettings> mutator) {
        Objects.requireNonNull(access, "access");

        TracingSettingsRevision witnessed = access.store().current();
        TracingSettings candidate;
        try {
            candidate = mutator.apply(witnessed.policy());
        } catch (IllegalArgumentException | NullPointerException exception) {
            return SettingsUpdateStatus.REJECTED;
        }

        if (candidate.equals(witnessed.policy())) {
            return SettingsUpdateStatus.UNCHANGED;
        }

        return commit(access, witnessed, candidate);
    }

    private static SettingsUpdateStatus commit(TracingSettingsSource access,
                                               TracingSettingsRevision witnessed,
                                               TracingSettings candidate) {
        SettingsCommitResult result = access.store().commit(witnessed.base(), candidate);
        if (result.outcome() == SettingsCommitOutcome.APPLIED) {
            return SettingsUpdateStatus.APPLIED;
        }

        return SettingsUpdateStatus.STALE;
    }
}
