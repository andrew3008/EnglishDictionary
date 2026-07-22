package space.br1440.platform.tracing.core.sampling.model;

import space.br1440.platform.tracing.api.attributes.PlatformSamplingReasons;

public enum SamplingPolicyReason {

    KILL_SWITCH(PlatformSamplingReasons.KILL_SWITCH),
    HARD_DROP(PlatformSamplingReasons.DROP_PATH),
    FORCE_HEADER(PlatformSamplingReasons.FORCE_HEADER),
    QA_TRACE(PlatformSamplingReasons.QA_TRACE),
    PARENT_DECISION(PlatformSamplingReasons.PARENT_SAMPLED),
    PARENT_DROP(PlatformSamplingReasons.PARENT_DROP),
    ROUTE_RATIO(PlatformSamplingReasons.ROUTE_RATIO),
    ROUTE_RATIO_DROP(PlatformSamplingReasons.ROUTE_RATIO_DROP),
    DEFAULT_RATIO(PlatformSamplingReasons.GLOBAL_RATIO),
    DEFAULT_RATIO_DROP(PlatformSamplingReasons.GLOBAL_RATIO_DROP),
    NO_MATCH(null);

    private final String reasonCode;

    SamplingPolicyReason(String reasonCode) {
        this.reasonCode = reasonCode;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
