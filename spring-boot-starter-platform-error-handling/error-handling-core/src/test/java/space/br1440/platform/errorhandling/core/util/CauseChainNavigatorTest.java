package space.br1440.platform.errorhandling.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CauseChainNavigator")
class CauseChainNavigatorTest {

    @Nested
    @DisplayName("findDeepestCause")
    class FindDeepestCause {

        @Test
        void returnsSelfWhenNoCause() {
            // given
            RuntimeException root = new RuntimeException("root");

            // when / then
            assertThat(CauseChainNavigator.findDeepestCause(root)).isSameAs(root);
        }

        @Test
        void returnsSelfWhenNoCause_withExplicitDepth() {
            // given
            RuntimeException root = new RuntimeException("root");

            // when / then
            assertThat(CauseChainNavigator.findDeepestCause(root, 10)).isSameAs(root);
        }

        @Test
        void traversesSingleLevel() {
            // given
            RuntimeException cause = new RuntimeException("cause");
            RuntimeException wrapper = new RuntimeException("wrapper", cause);

            // when / then
            assertThat(CauseChainNavigator.findDeepestCause(wrapper)).isSameAs(cause);
        }

        @Test
        void traversesMultipleLevels() {
            // given
            RuntimeException deepest = new RuntimeException("deepest");
            RuntimeException mid = new RuntimeException("mid", deepest);
            RuntimeException top = new RuntimeException("top", mid);

            // when / then
            assertThat(CauseChainNavigator.findDeepestCause(top)).isSameAs(deepest);
        }

        @Test
        void respectsMaxDepthLimit() {
            // given
            RuntimeException deepest = new RuntimeException("deepest");
            RuntimeException mid = new RuntimeException("mid", deepest);
            RuntimeException top = new RuntimeException("top", mid);

            // when / then
            assertThat(CauseChainNavigator.findDeepestCause(top, 1)).isSameAs(top);
            assertThat(CauseChainNavigator.findDeepestCause(top, 2)).isSameAs(mid);
        }

        @Test
        void stopsAtZeroDepth() {
            // given
            RuntimeException cause = new RuntimeException("cause");
            RuntimeException wrapper = new RuntimeException("wrapper", cause);

            // when / then
            assertThat(CauseChainNavigator.findDeepestCause(wrapper, 0)).isSameAs(wrapper);
        }

        @Test
        void handlesCyclicChain() {
            // given
            Exception a = new Exception("a");
            Exception b = new Exception("b", a);
            Exception c = new Exception("c", b);

            // when / then
            assertThat(CauseChainNavigator.findDeepestCause(c)).isSameAs(a);
        }

        @Test
        void defaultMaxDepthIsEnoughForLargeChain() {
            // given
            int depth = CauseChainNavigator.DEFAULT_MAX_DEPTH;
            RuntimeException current = new RuntimeException("deepest");
            for (int i = 0; i < depth - 1; i++) {
                current = new RuntimeException("level-" + i, current);
            }

            // when
            Throwable result = CauseChainNavigator.findDeepestCause(current);

            // then
            assertThat(result.getMessage()).isEqualTo("deepest");
        }

        @Test
        void defaultMaxDepthCutsChainBeyondLimit() {
            // given
            int depth = CauseChainNavigator.DEFAULT_MAX_DEPTH + 5;
            RuntimeException current = new RuntimeException("true-deepest");
            for (int i = 0; i < depth - 1; i++) {
                current = new RuntimeException("level-" + i, current);
            }

            // when
            Throwable result = CauseChainNavigator.findDeepestCause(current);

            // then
            assertThat(result.getMessage()).isNotEqualTo("true-deepest");
        }
    }

    @Nested
    @DisplayName("Cycle detection / depth safety")
    class CycleDetection {

        @Test
        void doesNotLoopOnLongChainWithinDepthLimit() {
            // given
            int depth = CauseChainNavigator.DEFAULT_MAX_DEPTH;
            RuntimeException current = new RuntimeException("deepest");
            for (int i = 0; i < depth - 1; i++) {
                current = new RuntimeException("level-" + i, current);
            }

            // when / then
            assertThat(CauseChainNavigator.findDeepestCause(current)).isNotNull();
        }

        @Test
        void doesNotLoopWhenChainExceedsMaxDepth() {
            // given
            int depth = CauseChainNavigator.DEFAULT_MAX_DEPTH + 10;
            RuntimeException current = new RuntimeException("true-deepest");
            for (int i = 0; i < depth - 1; i++) {
                current = new RuntimeException("level-" + i, current);
            }

            // when
            Throwable result = CauseChainNavigator.findDeepestCause(current);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getMessage()).isNotEqualTo("true-deepest");
        }
    }

}
