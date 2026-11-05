package space.br1440.platform.tracing.otel.javaagent.scrubbing.rules;

import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.ScrubbingValueTransformer;

import lombok.experimental.UtilityClass;

@UtilityClass
final class ValueScanPolicy {

    static final int MAX_ARRAY_ELEMENTS = ScrubbingValueTransformer.MAX_ARRAY_ELEMENTS;
    static final int MAX_INPUT_CHARS = ScrubbingValueTransformer.MAX_INPUT_CHARS;
    static final String INPUT_LIMIT_REASON = ScrubbingValueTransformer.INPUT_LIMIT_REASON;
    static final ScrubbingDecision INPUT_LIMIT_MASK = ScrubbingDecision.mask(INPUT_LIMIT_REASON);

}
