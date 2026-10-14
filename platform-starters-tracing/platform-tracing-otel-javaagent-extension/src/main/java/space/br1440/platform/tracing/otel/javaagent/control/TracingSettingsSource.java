package space.br1440.platform.tracing.otel.javaagent.control;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import space.br1440.platform.common.util.MapUtils;
import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.otel.javaagent.configuration.ControlSettingsExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults;
import space.br1440.platform.tracing.otel.javaagent.configuration.SamplingExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsStore;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.TracingSettingsUpdater;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLoggingRuntime;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.event.SamplingLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.event.ValidationLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;

public final class TracingSettingsSource {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(TracingSettingsSource.class);
    private static final String ROUTE_RATIO_NOT_A_NUMBER = "not-a-number";

    private final TracingSettingsStore store;
    private final TracingSettingsUpdater updater;

    private TracingSettingsSource(TracingSettingsStore store) {
        this.store = Objects.requireNonNull(store, "store");
        this.updater = store.current().agentInstanceId().isUnbound()
                ? null
                : new TracingSettingsUpdater(store, AgentLoggingRuntime.shared()::applyLevel);
    }

    public static TracingSettingsSource fromStartup(ExtensionConfig config,
                                                    AgentInstanceId agentInstanceId) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(agentInstanceId, "agentInstanceId");

        SpanAttributeValidationLevel level = config.validation().requestedLevel();
        if (level.isDisabled()) {
            log.log(ValidationLogEvent.VALIDATION_DISABLED);
        }

        return create(
                config.sampling(),
                config.controlSettings(),
                level,
                agentInstanceId);
    }

    public static TracingSettingsSource isolatedSampling(SamplingExtensionConfig sampling) {
        return create(
                sampling,
                ControlSettingsExtensionConfig.defaults(),
                ExtensionDefaults.DEFAULT_SPAN_ATTRIBUTE_VALIDATION_LEVEL,
                AgentInstanceId.unbound());
    }

    public static TracingSettingsSource isolatedDefaults() {
        return isolatedValidation(ExtensionDefaults.DEFAULT_SPAN_ATTRIBUTE_VALIDATION_LEVEL);
    }

    public static TracingSettingsSource isolatedValidation(SpanAttributeValidationLevel validationLevel) {
        return createIsolated(
                ExtensionDefaults.DEFAULT_ENABLED,
                ExtensionDefaults.DEFAULT_SAMPLING_RATIO,
                parseRouteRatios(ExtensionDefaults.DEFAULT_SAMPLING_ROUTE_RATIOS),
                validationLevel);
    }

    public static TracingSettingsSource isolatedSampling(boolean enabled,
                                                         Map<String, Double> routeRatios,
                                                         double defaultRatio) {
        return createIsolated(
                enabled,
                defaultRatio,
                routeRatios,
                SpanAttributeValidationLevel.DISABLED);
    }

    private static TracingSettingsSource createIsolated(boolean samplingEnabled,
                                                        double samplingRatio,
                                                        Map<String, Double> routeRatios,
                                                        SpanAttributeValidationLevel validationLevel) {
        ControlSettingsExtensionConfig controlSettings = ControlSettingsExtensionConfig.defaults();
        TracingSettings settings = TracingSettings.of(
                samplingEnabled,
                samplingRatio,
                routeRatios,
                validationLevel,
                controlSettings.exporterEnabled(),
                controlSettings.traceControlHeadersEnabled(),
                controlSettings.diagnosticsLogLevel()
        );

        TracingSettingsStore store = new TracingSettingsStore(AgentInstanceId.unbound(), settings);
        return new TracingSettingsSource(store);
    }

    public TracingSettingsRevision current() {
        return store.current();
    }

    public TracingSettingsStore store() {
        return store;
    }

    public TracingSettingsUpdater updater() {
        if (updater == null) {
            throw new IllegalStateException("Cannot apply tracing settings to an unbound store");
        }
        return updater;
    }

    public void projectDiagnosticsLevel() {
        if (updater == null) {
            AgentLoggingRuntime.shared().applyLevel(current().policy().diagnosticsLogLevel());
            return;
        }
        updater.projectDiagnosticsLevel();
    }

    private static TracingSettingsSource create(SamplingExtensionConfig sampling,
                                                ControlSettingsExtensionConfig controlSettings,
                                                SpanAttributeValidationLevel validationLevel,
                                                AgentInstanceId agentInstanceId) {
        Map<String, Double> routeRatios = parseRouteRatios(sampling.routeRatios());
        TracingSettings settings = TracingSettings.of(
                sampling.enabled(),
                sampling.ratio(),
                routeRatios,
                validationLevel,
                controlSettings.exporterEnabled(),
                controlSettings.traceControlHeadersEnabled(),
                controlSettings.diagnosticsLogLevel()
        );

        TracingSettingsStore store = new TracingSettingsStore(agentInstanceId, settings);
        return new TracingSettingsSource(store);
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
                if (StringUtils.hasText(entry.getKey())) {
                    log.log(SamplingLogEvent.INVALID_ROUTE_RATIO, fields -> {
                        fields.putString(AgentLogField.NAME, entry.getKey());
                        fields.putString(AgentLogField.REASON, ROUTE_RATIO_NOT_A_NUMBER);
                    });
                }
            }
        }

        return parsed;
    }
}
