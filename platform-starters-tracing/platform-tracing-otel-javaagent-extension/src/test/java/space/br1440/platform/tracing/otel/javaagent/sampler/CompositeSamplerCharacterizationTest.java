package space.br1440.platform.tracing.otel.javaagent.sampler;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import space.br1440.platform.tracing.test.harness.SamplingDecisionCase;

class CompositeSamplerCharacterizationTest {

    static Stream<SamplingDecisionCase> characterizedCases() {
        return SamplingCharacterizationSupport.decisionMatrix();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("characterizedCases")
    void matrix_case(SamplingDecisionCase c) {
        // given/when/then
        SamplingCharacterizationSupport.assertCharacterizedCase(c);
    }
}
