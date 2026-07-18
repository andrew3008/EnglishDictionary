package space.br1440.platform.tracing.api.control.protocol;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class TracingControlProtocolKeys {

    public static final String CONTRACT_VERSION = "contractVersion";
    public static final String OPERATION = "operation";
    public static final String SOURCE = "source";

    public static final String SAMPLING_RATIO = "sampling.ratio";
    public static final String SAMPLING_ROUTE_RATIOS = "sampling.routeRatios";
    public static final String SAMPLING_KILL_SWITCH_ENABLED = "sampling.killSwitch.enabled";
    public static final String SAMPLING_QA_TRACE_ENABLED = "sampling.qaTrace.enabled";
    public static final String SAMPLING_FORCE_HEADER_ENABLED = "sampling.forceHeader.enabled";
    public static final String SAMPLING_FORCE_HEADER_VALUES = "sampling.forceHeader.values";
    public static final String SAMPLING_DROP_PATH_PREFIXES = "sampling.dropPathPrefixes";

    public static final String SCRUBBING_ENABLED = "scrubbing.enabled";
    public static final String SCRUBBING_MODE = "scrubbing.mode";
    public static final String SCRUBBING_RULE_NAMES = "scrubbing.ruleNames";

    public static final String VALIDATION_ENABLED = "validation.enabled";
    public static final String VALIDATION_MODE = "validation.mode";
    public static final String VALIDATION_STRICT = "validation.strict";

    public static final String ENRICHING_ENABLED = "enriching.enabled";

    public static final String EXPORT_ENABLED = "export.enabled";
    public static final String PROPAGATION_ENABLED = "propagation.enabled";

    public static final String DIAGNOSTICS_REQUEST_ID = "diagnostics.requestId";
    public static final String DIAGNOSTICS_TIMESTAMP = "diagnostics.timestamp";

}
