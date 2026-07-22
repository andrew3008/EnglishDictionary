package space.br1440.platform.tracing.otel.sampling.policy;

import lombok.experimental.UtilityClass;
import space.br1440.platform.tracing.api.attributes.PlatformSamplingReasons;

@UtilityClass
final class SamplingPolicyRuleNames {

    static final String KILL_SWITCH = PlatformSamplingReasons.KILL_SWITCH;
    static final String HARD_DROP = "hard_drop";
    static final String FORCE_HEADER = PlatformSamplingReasons.FORCE_HEADER;
    static final String QA_TRACE = PlatformSamplingReasons.QA_TRACE;
    static final String PARENT_DECISION = "parent_decision";
    static final String ROUTE_RATIO = PlatformSamplingReasons.ROUTE_RATIO;
    static final String DEFAULT_RATIO = "default_ratio";

}
