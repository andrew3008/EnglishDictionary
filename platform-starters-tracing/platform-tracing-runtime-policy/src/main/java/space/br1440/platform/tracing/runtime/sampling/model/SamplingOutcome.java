package space.br1440.platform.tracing.runtime.sampling.model;

import space.br1440.platform.tracing.api.attributes.PlatformSamplingReasons;

public enum SamplingOutcome {
    KILL_SWITCH(false, PlatformSamplingReasons.KILL_SWITCH, "kill_switch"),
    HARD_DROP(false, PlatformSamplingReasons.DROP_PATH, "hard_drop"),
    FORCE_HEADER(true, PlatformSamplingReasons.FORCE_HEADER, "force_header"),
    QA_TRACE(true, PlatformSamplingReasons.QA_TRACE, "qa_trace"),
    PARENT_SAMPLED(true, PlatformSamplingReasons.PARENT_SAMPLED, "parent_decision"),
    PARENT_DROP(false, PlatformSamplingReasons.PARENT_DROP, "parent_decision"),
    ROUTE_SAMPLE(true, PlatformSamplingReasons.ROUTE_RATIO, "route_ratio"),
    ROUTE_DROP(false, PlatformSamplingReasons.ROUTE_RATIO_DROP, "route_ratio"),
    DEFAULT_SAMPLE(true, PlatformSamplingReasons.GLOBAL_RATIO, "default_ratio"),
    DEFAULT_DROP(false, PlatformSamplingReasons.GLOBAL_RATIO_DROP, "default_ratio");

    private final boolean sampled;
    private final String reasonCode;
    private final String winningRule;

    SamplingOutcome(boolean sampled, String reasonCode, String winningRule) {
        this.sampled = sampled;
        this.reasonCode = reasonCode;
        this.winningRule = winningRule;
    }

    public boolean sampled() {
        return sampled;
    }

    public String reasonCode() {
        return reasonCode;
    }

    public String winningRule() {
        return winningRule;
    }
}
