package space.br1440.platform.tracing.otel.javaagent.scrubbing.rules;

import java.util.regex.Pattern;

import space.br1440.platform.tracing.api.spi.ScrubbingDecision;

/**
 * Правило обнаружения email-адресов в строковых атрибутах.
 */
final class EmailRule extends AbstractValueScanRule {

    private static final String LOCAL_PART_CHAR_CLASS = "[A-Za-z0-9!#$%&'*+/=?^_`{|}~.-]";

    private static final Pattern PATTERN = Pattern.compile(
            "(?<!" + LOCAL_PART_CHAR_CLASS + ")" + LOCAL_PART_CHAR_CLASS + "++"
                    + "(?<!://\\*{3})@(?:[A-Za-z0-9-]{1,63}+\\.(?=[A-Za-z0-9-]))++"
                    + "(?:[A-Za-z]{2,63}+|[xX][nN]--[A-Za-z0-9-]{1,59}+)"
                    + "(?![A-Za-z0-9-])"
    );

    EmailRule() {
        super(BuiltInSpanAttributeScrubbingRules.EMAIL, ScrubbingDecision.hash("email"));
    }

    @Override
    protected boolean matches(String text) {
        return text.indexOf('@') >= 0 && PATTERN.matcher(text).find();
    }
}
