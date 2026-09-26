package space.br1440.platform.tracing.otel.javaagent.control;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import space.br1440.platform.common.util.MapUtils;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.configuration.SamplingExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsStore;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.LastSettingsUpdate;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.TracingSettingsUpdater;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogLevel;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;

public final class TracingSettingsSource {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(TracingSettingsSource.class);

    private final TracingSettingsStore store;
    private final LastSettingsUpdate diagnostics;
    private final TracingSettingsUpdater updater;

    private TracingSettingsSource(TracingSettingsStore store) {
        this.store = Objects.requireNonNull(store, "store");
        this.diagnostics = new LastSettingsUpdate();
        this.updater = new TracingSettingsUpdater(store, diagnostics);
    }

    public static TracingSettingsSource fromStartup(ExtensionConfig config,
                                                    AgentInstanceId agentInstanceId) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(agentInstanceId, "agentInstanceId");

        SpanAttributeValidationLevel level = config.validation().requestedLevel();
        if (level.isDisabled()) {
            log.warn(AgentEventCode.VALID_POLICY, "Validation DISABLED");
        }

        return create(config.sampling(), level, agentInstanceId);
    }

    public static TracingSettingsSource isolatedSampling(SamplingExtensionConfig sampling) {
        return create(sampling, SpanAttributeValidationLevel.WARNING, AgentInstanceId.unbound());
    }

    public static TracingSettingsSource isolatedDefaults() {
        return isolatedValidation(SpanAttributeValidationLevel.WARNING);
    }

    public static TracingSettingsSource isolatedValidation(SpanAttributeValidationLevel validationLevel) {
        TracingSettings settings = TracingSettings.of(
                true,
                1.0d,
                Map.of(),
                validationLevel,
                true,
                true,
                AgentLogLevel.INFO
        );

        return new TracingSettingsSource(new TracingSettingsStore(AgentInstanceId.unbound(), settings));
    }

    public static TracingSettingsSource isolatedSampling(boolean enabled,
                                                         Map<String, Double> routeRatios,
                                                         double defaultRatio) {
        TracingSettings settings = TracingSettings.of(
                enabled,
                defaultRatio,
                routeRatios,
                SpanAttributeValidationLevel.DISABLED,
                true,
                true,
                AgentLogLevel.INFO
        );

        return new TracingSettingsSource(new TracingSettingsStore(AgentInstanceId.unbound(), settings));
    }

    public TracingSettingsRevision current() {
        return store.current();
    }

    public TracingSettingsStore store() {
        return store;
    }

    public TracingSettingsUpdater updater() {
        return updater;
    }

    public LastSettingsUpdate diagnostics() {
        return diagnostics;
    }

    private static TracingSettingsSource create(SamplingExtensionConfig sampling,
                                                SpanAttributeValidationLevel validationLevel,
                                                AgentInstanceId agentInstanceId) {
        Map<String, Double> routeRatios = parseRouteRatios(sampling.routeRatios());
        TracingSettings settings = TracingSettings.of(
                sampling.enabled(),
                sampling.ratio(),
                routeRatios,
                validationLevel,
                true,
                true,
                AgentLogLevel.INFO
        );

        return new TracingSettingsSource(new TracingSettingsStore(agentInstanceId, settings));
    }

    private static Map<String, Double> parseRouteRatios(Map<String, String> source) {
        if (MapUtils.isNullOrEmpty(source)) {
            return Map.of();
        }

        Map<String, Double> parsed = new HashMap<>(source.size());
        for (Map.Entry<String, String> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }

            try {
                parsed.put(entry.getKey(), Double.parseDouble(entry.getValue().trim()));
            } catch (NumberFormatException ignored) {
            }
        }

        return parsed;
    }
}
