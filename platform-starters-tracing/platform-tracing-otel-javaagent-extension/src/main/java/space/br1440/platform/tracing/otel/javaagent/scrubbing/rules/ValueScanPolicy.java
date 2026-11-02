package space.br1440.platform.tracing.otel.javaagent.scrubbing.rules;

import space.br1440.platform.tracing.api.spi.ScrubbingAction;
import space.br1440.platform.tracing.api.spi.ScrubbingDecision;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class ValueScanPolicy {

    static final int MAX_ARRAY_ELEMENTS = 32;
    static final int MAX_INPUT_CHARS = 4096;
    static final String INPUT_LIMIT_REASON = "input-limit";
    static final ScrubbingDecision INPUT_LIMIT_MASK = ScrubbingDecision.mask(INPUT_LIMIT_REASON);

    public static boolean isInputLimitMask(ScrubbingDecision decision) {
        return decision.action() == ScrubbingAction.MASK && INPUT_LIMIT_REASON.equals(decision.reason());
    }
}
