package space.br1440.platform.tracing.otel.javaagent.logging;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentEventCodesConsistencyTest {

    private static final Pattern EVENT_CODE_PATTERN = Pattern.compile("^[A-Z]{2,6}-[A-Z]+-[0-9]{3}$");

    @Test
    void production_constants_match_known_codes_and_schema_pattern() throws IllegalAccessException {
        // given
        List<String> constants = new ArrayList<>();
        for (Field field : AgentEventCodes.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers)
                    && Modifier.isFinal(modifiers)
                    && field.getType() == String.class) {
                constants.add((String) field.get(null));
            }
        }

        // when
        Set<String> known = AgentEventCodes.knownCodes();

        // then
        assertThat(constants).doesNotHaveDuplicates();
        assertThat(Set.copyOf(constants)).isEqualTo(known);
        assertThat(known).allMatch(code -> EVENT_CODE_PATTERN.matcher(code).matches());
        assertThat(AgentEventCodes.isKnown(AgentEventCodes.ENCODE_FALLBACK)).isTrue();
        assertThat(AgentEventCodes.isKnown("FAKE-UNKNOWN-999")).isFalse();
    }
}
