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
import space.br1440.platform.tracing.otel.javaagent.logging.event.ValidationLogEvent;

public final class TracingSettingsSource {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(TracingSettingsSource.class);
    private static final TracingSettings DEFAULT_POLICY = createDefaultPolicy();

    private final TracingSettingsStore store;
    private final TracingSettingsUpdater updater;

    private TracingSettingsSource(TracingSettingsStore store) {
        this.store = Objects.requireNonNull(store, "store");
        this.updater = new TracingSettingsUpdater(store, AgentLoggingRuntime.shared()::applyLevel);
    }

    public static TracingSettings defaultPolicy() {
        return DEFAULT_POLICY;
    }

    private static TracingSettings createDefaultPolicy() {
        ControlSettingsExtensionConfig controlSettings = ControlSettingsExtensionConfig.defaults();
        return TracingSettings.of(
                ExtensionDefaults.DEFAULT_ENABLED,
                ExtensionDefaults.DEFAULT_SAMPLING_RATIO,
                Map.of(),
                ExtensionDefaults.DEFAULT_SPAN_ATTRIBUTE_VALIDATION_LEVEL,
                controlSettings.exporterEnabled(),
                controlSettings.traceControlHeadersEnabled(),
                controlSettings.diagnosticsLogLevel()
        );
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

    public TracingSettingsRevision current() {
        return store.current();
    }

    public TracingSettingsStore store() {
        return store;
    }

    public TracingSettingsUpdater updater() {
        return updater;
    }

    public void projectDiagnosticsLevel() {
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
            String routePrefix = entry.getKey();
            if (routePrefix == null) {
                throw invalidRouteRatio("<null>", "key must not be null");
            }

            if (parsed.containsKey(routePrefix)) {
                throw invalidRouteRatio(routePrefix, "key is duplicated");
            }

            String encodedRatio = entry.getValue();
            if (StringUtils.isNullOrBlank(encodedRatio)) {
                throw invalidRouteRatio(routePrefix, "value must not be blank");
            }

            double ratio;
            try {
                ratio = Double.parseDouble(encodedRatio.trim());
            } catch (NumberFormatException ignored) {
                throw invalidRouteRatio(routePrefix, "value must be a number");
            }

            parsed.put(routePrefix, ratio);
        }

        return parsed;
    }

    private static IllegalArgumentException invalidRouteRatio(String routePrefix, String reason) {
        return new IllegalArgumentException(
                "Invalid sampling route ratio for key '%s': %s".formatted(routePrefix, reason)
        );
    }
}
