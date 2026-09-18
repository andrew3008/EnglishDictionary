package space.br1440.platform.tracing.otel.javaagent.processor;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import space.br1440.platform.tracing.test.harness.ScrubbingDecisionCase;

class ScrubbingRuleMatrixCharacterizationTest {

    static Stream<ScrubbingDecisionCase> characterizedCases() {
        return ScrubbingCharacterizationSupport.scrubbingMatrix();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("characterizedCases")
    void matrix_case(ScrubbingDecisionCase c) {
        // given/when/then
        ScrubbingCharacterizationSupport.assertScrubbingCase(c);
    }
}
