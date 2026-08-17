package space.br1440.platform.tracing.otel.javaagent.logging;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentEventCodesTest {

    private static final Pattern CENTRAL_PATTERN = Pattern.compile("^[A-Z]{2,6}-[A-Z]+-[0-9]{3}$");

    @Test
    void every_declared_code_matches_central_pattern() throws IllegalAccessException {
        // given
        int declared = 0;

        // when
        for (Field field : AgentEventCodes.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {
                continue;
            }
            String code = (String) field.get(null);
            declared++;

            // then
            assertThat(code).matches(CENTRAL_PATTERN);
        }
        assertThat(declared).isGreaterThan(0);
    }
}
