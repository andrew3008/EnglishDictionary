package space.br1440.platform.tracing.otel.javaagent.control;

import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

import space.br1440.platform.tracing.otel.javaagent.control.settings.SettingsCommitOutcome;
import space.br1440.platform.tracing.otel.javaagent.control.settings.SettingsCommitResult;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateStatus;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class AcceptedPolicyMutationAccess {

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
                    current.validationLevel(),
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
                                                       SpanAttributeValidationLevel validationLevel) {
        Objects.requireNonNull(access, "access");

        return applyCandidate(
                access,
                current -> TracingSettings.of(
                        current.samplingEnabled(),
                        current.samplingRatio(),
                        current.samplingRouteRatios(),
                        validationLevel,
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
                        current.validationLevel(),
                        enabled,
                        current.traceControlHeadersEnabled(),
                        current.diagnosticsLogLevel()
                )
        );
    }

    public static boolean applyDiagnosticsLogLevel(TracingSettingsSource access, String rawLevel) {
        AgentLogLevel level;

        try {
            level = AgentLogLevel.valueOf(rawLevel);
        } catch (IllegalArgumentException | NullPointerException exception) {
            return false;
        }

        SettingsUpdateStatus status = applyCandidate(
                access,
                current -> TracingSettings.of(
                        current.samplingEnabled(),
                        current.samplingRatio(),
                        current.samplingRouteRatios(),
                        current.validationLevel(),
                        current.exporterEnabled(),
                        current.traceControlHeadersEnabled(),
                        level
                )
        );

        return status == SettingsUpdateStatus.APPLIED || status == SettingsUpdateStatus.UNCHANGED;
    }

    public static SettingsUpdateStatus applyTraceControlHeaders(TracingSettingsSource access,
                                                                boolean enabled) {
        return applyCandidate(
                access,
                current -> TracingSettings.of(
                        current.samplingEnabled(),
                        current.samplingRatio(),
                        current.samplingRouteRatios(),
                        current.validationLevel(),
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
        SettingsCommitResult result = access.store().commit(
                witnessed.base(),
                candidate,
                witnessed.sourceAuthority()
        );

        if (result.outcome() == SettingsCommitOutcome.APPLIED) {
            return SettingsUpdateStatus.APPLIED;
        }

        return SettingsUpdateStatus.STALE;
    }
}
