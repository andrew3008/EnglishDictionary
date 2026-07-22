package space.br1440.platform.tracing.otel.javaagent.sampler;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import space.br1440.platform.tracing.test.harness.SamplingDecisionCase;

import java.util.stream.Stream;

/**
 * Semantic parity: delegated {@link CompositeSampler} matches PR-5A characterization matrix.
 */
class CompositeSamplerCharacterizationTest {

    static Stream<SamplingDecisionCase> characterizedCases() {
        return SamplingCharacterizationSupport.decisionMatrix();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("characterizedCases")
    void matrix_case(SamplingDecisionCase c) {
        SamplingCharacterizationSupport.assertCharacterizedCase(c);
    }
}
