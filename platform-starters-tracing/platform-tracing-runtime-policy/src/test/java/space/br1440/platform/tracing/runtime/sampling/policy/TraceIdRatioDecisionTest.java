package space.br1440.platform.tracing.runtime.sampling.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Фиксированные векторы защищают алгоритм; ratio=1 намеренно сильнее граничного поведения upstream. */
class TraceIdRatioDecisionTest {

    private static final String UPPER = "0000000000000001";

    @ParameterizedTest
    @CsvSource({
            "0000000000000001,0,false",
            "8000000000000000,-0.0,false",
            "7fffffffffffffff,1,true",
            "8000000000000000,0.5,true",
            "ffffffffffffffff,0.5,true",
            "0000000000000001,0.5,true",
            "7fffffffffffffff,0.5,false",
            "3fffffffffffffff,0.5,true",
            "4000000000000000,0.5,false",
            "4000000000000001,0.5,false"
    })
    void matchesGoldenVectors(String lowerBits, double ratio, boolean expected) {
        // given
        String id = UPPER + lowerBits;

        // when
        boolean sampled = TraceIdRatioDecision.shouldSample(id, ratio);

        // then
        assertThat(sampled).isEqualTo(expected);
    }

    @Test
    void boundaryRatiosDoNotRequireTraceIdAndShortIdsDropAtFractionalRatio() {
        // given
        String shortId = "0000000000000000000000000000001";

        // when
        boolean full = TraceIdRatioDecision.shouldSample(null, 1);
        boolean zero = TraceIdRatioDecision.shouldSample(null, 0);
        boolean missing = TraceIdRatioDecision.shouldSample(null, 0.5);
        boolean shortResult = TraceIdRatioDecision.shouldSample(shortId, 0.5);

        // then
        assertThat(full).isTrue();
        assertThat(zero).isFalse();
        assertThat(missing).isFalse();
        assertThat(shortResult).isFalse();
    }

    @Test
    void selectionIsDeterministicAndMonotonicForValidIds() {
        // given
        Random random = new Random(1440);
        for (int i = 0; i < 1000; i++) {
            String id = UPPER + String.format("%016x", random.nextLong());
            boolean selected = false;
            for (double ratio : new double[] {0, 0.01, 0.1, 0.5, 0.9, 1}) {
                // when
                boolean actual = TraceIdRatioDecision.shouldSample(id, ratio);

                // then
                assertThat(actual).isEqualTo(TraceIdRatioDecision.shouldSample(id, ratio));
                if (selected) {
                    assertThat(actual).isTrue();
                }
                selected = actual;
            }
        }
    }
}
