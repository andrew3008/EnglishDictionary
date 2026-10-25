package space.br1440.platform.tracing.otel.javaagent.configuration;

import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_DIAGNOSTICS_LOG_LEVEL;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_EXPORTER_ENABLED;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_TRACE_CONTROL_HEADERS_ENABLED;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.DIAGNOSTICS_LOG_LEVEL;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.EXPORTER_ENABLED;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.TRACE_CONTROL_HEADERS_ENABLED;

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
                reader.enumValue(
                        DIAGNOSTICS_LOG_LEVEL,
                        AgentLogLevel.class,
                        DEFAULT_DIAGNOSTICS_LOG_LEVEL
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
}
