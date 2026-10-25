package space.br1440.platform.tracing.otel.javaagent.configuration;

import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_DROP_PATHS;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_ENABLED;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_FORCE_VALUES;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_SAMPLING_RATIO;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_SAMPLING_ROUTE_RATIOS;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.SAMPLING_DROP_PATHS;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.SAMPLING_ENABLED;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.SAMPLING_FORCE_RECORD_VALUES;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.SAMPLING_RATIO;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.SAMPLING_ROUTE_RATIOS;

import java.util.List;
import java.util.Map;

import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
public final class SamplingExtensionConfig {

    private final boolean enabled;
    private final double ratio;
    private final List<String> forceRecordValues;
    private final List<String> dropPaths;
    private final Map<String, String> routeRatios;

    SamplingExtensionConfig(ExtensionConfigReader reader) {
        this.enabled = reader.booleanValue(SAMPLING_ENABLED, DEFAULT_ENABLED);
        this.ratio = reader.doubleValue(SAMPLING_RATIO, DEFAULT_SAMPLING_RATIO);
        this.forceRecordValues = reader.listValue(SAMPLING_FORCE_RECORD_VALUES, DEFAULT_FORCE_VALUES);
        this.dropPaths = reader.listValue(SAMPLING_DROP_PATHS, DEFAULT_DROP_PATHS);
        this.routeRatios = reader.mapValue(SAMPLING_ROUTE_RATIOS, DEFAULT_SAMPLING_ROUTE_RATIOS);
    }
}
