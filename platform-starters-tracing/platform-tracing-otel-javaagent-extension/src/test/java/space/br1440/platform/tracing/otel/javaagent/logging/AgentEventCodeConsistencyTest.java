package space.br1440.platform.tracing.otel.javaagent.logging;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;

import static org.assertj.core.api.Assertions.assertThat;

class AgentEventCodeConsistencyTest {

    private static final Pattern EVENT_CODE_PATTERN = Pattern.compile("^[A-Z]{2,6}-[A-Z]+-[0-9]{3}$");

    @Test
    void every_code_is_unique_and_matches_the_schema_pattern() {
        // given
        List<String> codes = new ArrayList<>();

        // when
        for (AgentEventCode eventCode : AgentEventCode.values()) {
            codes.add(eventCode.code());
        }

        // then
        assertThat(codes).doesNotHaveDuplicates().isNotEmpty();
        assertThat(codes).allMatch(code -> EVENT_CODE_PATTERN.matcher(code).matches());
    }
}
