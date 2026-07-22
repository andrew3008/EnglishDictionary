package space.br1440.platform.tracing.otel.extension.processor;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import space.br1440.platform.tracing.test.harness.ScrubbingDecisionCase;

import java.util.stream.Stream;

/**
 * Матрица characterization-сценариев scrubbing (PR-5B).
 */
class ScrubbingRuleMatrixCharacterizationTest {

    static Stream<ScrubbingDecisionCase> characterizedCases() {
        return ScrubbingCharacterizationSupport.scrubbingMatrix();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("characterizedCases")
    void matrix_case(ScrubbingDecisionCase c) {
        ScrubbingCharacterizationSupport.assertScrubbingCase(c);
    }
}
