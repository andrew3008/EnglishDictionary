package space.br1440.platform.tracing.autoconfigure.metrics;

import space.br1440.platform.tracing.api.attributes.PlatformSamplingReasons;

enum SamplerDecisionKey {

    SAMPLED_FORCE_HEADER("RECORD_AND_SAMPLE", PlatformSamplingReasons.FORCE_HEADER),
    SAMPLED_QA_TRACE("RECORD_AND_SAMPLE", PlatformSamplingReasons.QA_TRACE),
    SAMPLED_PARENT_SAMPLED("RECORD_AND_SAMPLE", PlatformSamplingReasons.PARENT_SAMPLED),
    SAMPLED_ROUTE_RATIO("RECORD_AND_SAMPLE", PlatformSamplingReasons.ROUTE_RATIO),
    SAMPLED_GLOBAL_RATIO("RECORD_AND_SAMPLE", PlatformSamplingReasons.GLOBAL_RATIO),
    DROPPED_KILL_SWITCH("DROP", PlatformSamplingReasons.KILL_SWITCH),
    DROPPED_DROP_PATH("DROP", PlatformSamplingReasons.DROP_PATH),
    DROPPED_PARENT_DROP("DROP", PlatformSamplingReasons.PARENT_DROP),
    DROPPED_ROUTE_RATIO_DROP("DROP", PlatformSamplingReasons.ROUTE_RATIO_DROP),
    DROPPED_GLOBAL_RATIO_DROP("DROP", PlatformSamplingReasons.GLOBAL_RATIO_DROP),
    DROPPED_FALLBACK_DROP("DROP", PlatformSamplingReasons.FALLBACK_DROP);

    private final String decision;
    private final String reason;

    SamplerDecisionKey(String decision, String reason) {
        this.decision = decision;
        this.reason = reason;
    }

    String decision() {
        return decision;
    }

    String reason() {
        return reason;
    }

    String wireKey() {
        return decision + ":" + reason;
    }
}
