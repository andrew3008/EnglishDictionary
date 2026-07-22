package space.br1440.platform.tracing.otel.extension.configuration;

import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.List;
import java.util.Map;

import static space.br1440.platform.tracing.otel.extension.configuration.ExtensionDefaults.*;
import static space.br1440.platform.tracing.otel.extension.configuration.ExtensionPropertyNames.*;

@Getter
@Accessors(fluent = true)
public final class SamplingExtensionConfig {

    private final boolean enabled;
    private final double ratio;
    private final String forceRecordHeader;
    private final String qaHeader;
    private final List<String> forceRecordValues;
    private final List<String> dropPaths;
    private final Map<String, String> routeRatios;

    SamplingExtensionConfig(ExtensionConfigReader reader) {
        this.enabled = reader.booleanValue(SAMPLING_ENABLED, DEFAULT_ENABLED);
        this.ratio = reader.doubleValue(SAMPLING_RATIO, DEFAULT_SAMPLING_RATIO);
        this.forceRecordHeader = reader.stringValue(SAMPLING_FORCE_RECORD_HEADER, DEFAULT_FORCE_HEADER);
        this.qaHeader = reader.stringValue(SAMPLING_QA_HEADER, DEFAULT_QA_HEADER);
        this.forceRecordValues = reader.listValue(SAMPLING_FORCE_RECORD_VALUES, DEFAULT_FORCE_VALUES);
        this.dropPaths = reader.listValue(SAMPLING_DROP_PATHS, DEFAULT_DROP_PATHS);
        this.routeRatios = reader.mapValue(SAMPLING_ROUTE_RATIOS, Map.of());
    }
}
