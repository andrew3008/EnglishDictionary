package space.br1440.platform.tracing.otel.extension.sampler;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import space.br1440.platform.tracing.test.harness.SamplingDecisionCase;

import java.util.stream.Stream;

/**
 * Матрица characterization-сценариев для текущего {@link CompositeSampler} (PR-5A).
 */
class SamplingDecisionMatrixCharacterizationTest {

    static Stream<SamplingDecisionCase> characterizedCases() {
        return SamplingCharacterizationSupport.decisionMatrix();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("characterizedCases")
    void matrix_case(SamplingDecisionCase c) {
        SamplingCharacterizationSupport.assertCharacterizedCase(c);
    }
}
