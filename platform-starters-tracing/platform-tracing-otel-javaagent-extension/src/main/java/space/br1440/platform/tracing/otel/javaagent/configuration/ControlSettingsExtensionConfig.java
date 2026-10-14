package space.br1440.platform.tracing.otel.javaagent.configuration;

import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_DIAGNOSTICS_LOG_LEVEL;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_EXPORTER_ENABLED;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_TRACE_CONTROL_HEADERS_ENABLED;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.DIAGNOSTICS_LOG_LEVEL;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.EXPORTER_ENABLED;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.TRACE_CONTROL_HEADERS_ENABLED;

import java.util.List;
import java.util.Locale;

import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;

import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
public final class ControlSettingsExtensionConfig {

    private final boolean exporterEnabled;
    private final boolean traceControlHeadersEnabled;
    private final AgentLogLevel diagnosticsLogLevel;

    ControlSettingsExtensionConfig(ExtensionConfigReader reader) {
        this(
                reader.booleanValue(EXPORTER_ENABLED, DEFAULT_EXPORTER_ENABLED),
                reader.booleanValue(TRACE_CONTROL_HEADERS_ENABLED, DEFAULT_TRACE_CONTROL_HEADERS_ENABLED),
                parseDiagnosticsLogLevel(
                        reader.stringValue(
                                DIAGNOSTICS_LOG_LEVEL,
                                DEFAULT_DIAGNOSTICS_LOG_LEVEL.name()
                        )
                )
        );
    }

    private ControlSettingsExtensionConfig(boolean exporterEnabled,
                                           boolean traceControlHeadersEnabled,
                                           AgentLogLevel diagnosticsLogLevel) {
        this.exporterEnabled = exporterEnabled;
        this.traceControlHeadersEnabled = traceControlHeadersEnabled;
        this.diagnosticsLogLevel = diagnosticsLogLevel;
    }

    public static ControlSettingsExtensionConfig defaults() {
        return new ControlSettingsExtensionConfig(
                DEFAULT_EXPORTER_ENABLED,
                DEFAULT_TRACE_CONTROL_HEADERS_ENABLED,
                DEFAULT_DIAGNOSTICS_LOG_LEVEL
        );
    }

    private static AgentLogLevel parseDiagnosticsLogLevel(String rawValue) {
        String normalizedValue = rawValue.trim().toUpperCase(Locale.ROOT);
        try {
            return AgentLogLevel.valueOf(normalizedValue);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Invalid property '%s'='%s'. Accepted values: %s"
                            .formatted(DIAGNOSTICS_LOG_LEVEL, rawValue, List.of(AgentLogLevel.values())),
                    exception
            );
        }
    }
}
