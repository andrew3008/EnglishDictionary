package space.br1440.platform.tracing.autoconfigure.sampling;

import java.util.ArrayList;
import java.util.List;

/**
 * Конвертация списка имён built-in правил scrubbing в JMX-массив {@code ruleNames[]}.
 * <p>
 * Порядок элементов — порядок {@link java.util.List} (дефолт {@code ArrayList} в
 * {@link space.br1440.platform.tracing.autoconfigure.TracingProperties.Scrubbing#builtInRules}).
 * Unknown names не фильтруются на Spring-стороне (Option A): agent {@code BuiltInSensitiveDataRules.resolve}
 * пропускает неизвестные имена — startup/JMX parity (PR-7B).
 */
final class ScrubbingRuleNamesWire {

    record WireArray(String[] ruleNames) {
    }

    private ScrubbingRuleNamesWire() {
    }

    static WireArray fromList(List<String> ruleNames) {
        if (ruleNames == null || ruleNames.isEmpty()) {
            return new WireArray(new String[0]);
        }
        return new WireArray(ruleNames.toArray(new String[0]));
    }
}
