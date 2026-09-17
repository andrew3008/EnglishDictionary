package space.br1440.platform.tracing.otel.javaagent.control;

import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import space.br1440.platform.common.util.MapUtils;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.configuration.SamplingExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.LastSettingsUpdate;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsSourceAuthority;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateAttempt;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateResult;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateStatus;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsUpdateTrigger;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingLogLevel;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsStore;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.TracingSettingsUpdater;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentEventCodes;
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

    public static TracingSettingsSource fromStartup(ExtensionConfig config) {
        Objects.requireNonNull(config, "config");

        SpanAttributeValidationLevel level = config.validation().requestedLevel();
        if (level.isDisabled()) {
            log.warn(AgentEventCodes.VALID_POLICY, "Validation DISABLED");
        }

        boolean validationEnabled = !level.isDisabled() && config.validation().enabled();
        boolean validationStrict = !level.isDisabled() && level.isError();

        return create(config.sampling(), validationEnabled, validationStrict);
    }

    public static TracingSettingsSource fromSampling(SamplingExtensionConfig sampling) {
        return create(sampling, true, false);
    }

    public static TracingSettingsSource isolatedDefaults() {
        return isolatedValidation(true, false);
    }

    public static TracingSettingsSource isolatedValidation(boolean enabled,
                                                           boolean strict) {
        TracingSettings settings = TracingSettings.of(
                true,
                1.0d,
                Map.of(),
                enabled,
                strict,
                true,
                true,
                TracingLogLevel.INFO
        );

        return new TracingSettingsSource(new TracingSettingsStore(UUID.randomUUID().toString(), settings));
    }

    public static TracingSettingsSource isolatedSampling(boolean enabled,
                                                         Map<String, Double> routeRatios,
                                                         double defaultRatio) {
        TracingSettings settings = TracingSettings.of(
                enabled,
                defaultRatio,
                routeRatios,
                false,
                false,
                true,
                true,
                TracingLogLevel.INFO
        );

        return new TracingSettingsSource(new TracingSettingsStore(UUID.randomUUID().toString(), settings));
    }

    public TracingSettingsRevision current() {
        return store.current();
    }

    public boolean applyLogLevel(String value) {
        TracingLogLevel level;
        try {
            level = TracingLogLevel.valueOf(
                    Objects.requireNonNull(value, "value").trim().toUpperCase(Locale.ROOT)
            );
        } catch (IllegalArgumentException | NullPointerException exception) {
            return false;
        }

        TracingSettingsRevision witnessed = store.current();
        TracingSettings current = witnessed.policy();
        TracingSettings candidate = TracingSettings.of(
                current.samplingEnabled(),
                current.samplingRatio(),
                current.samplingRouteRatios(),
                current.validationEnabled(),
                current.validationStrict(),
                current.exporterEnabled(),
                current.traceControlHeadersEnabled(),
                level
        );

        SettingsUpdateAttempt attempt = new SettingsUpdateAttempt(
                UUID.randomUUID().toString(),
                Instant.now(),
                SettingsSourceAuthority.DEV_ACTUATOR,
                SettingsUpdateTrigger.DEVELOPER_COMMAND,
                "diagnostics.log-level"
        );

        SettingsUpdateResult result = updater.apply(attempt, witnessed.base(), candidate);
        return result.outcome() == SettingsUpdateStatus.APPLIED
                || result.outcome() == SettingsUpdateStatus.UNCHANGED;
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
                                                boolean validationEnabled,
                                                boolean validationStrict) {
        Map<String, Double> routeRatios = parseRouteRatios(sampling.routeRatios());
        TracingSettings settings = TracingSettings.of(
                sampling.enabled(),
                sampling.ratio(),
                routeRatios,
                validationEnabled,
                validationStrict,
                true,
                true,
                TracingLogLevel.INFO
        );

        return new TracingSettingsSource(new TracingSettingsStore(UUID.randomUUID().toString(), settings));
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
