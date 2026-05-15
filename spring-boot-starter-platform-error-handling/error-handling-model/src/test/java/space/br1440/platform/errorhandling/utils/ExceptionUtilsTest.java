package space.br1440.platform.errorhandling.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("ExceptionUtils")
class ExceptionUtilsTest {

    @Nested
    @DisplayName("getStackTrace")
    class GetStackTrace {

        @Test
        void fullStackTrace_containsExceptionMessage() {
            // given
            var ex = new RuntimeException("boom");

            // when
            String trace = ExceptionUtils.getStackTrace(ex);

            // then
            assertThat(trace).contains("boom");
            assertThat(trace).contains("RuntimeException");
        }

        @Test
        void truncatedStackTrace_endsWithTruncationMarker() {
            // given
            var ex = new RuntimeException("deep");

            // when
            String trace = ExceptionUtils.getStackTrace(ex, 3);

            // then
            assertThat(trace).contains("[truncated,");
        }

        @Test
        void maxLines_greaterThanActual_returnsFullTrace() {
            // given
            var ex = new RuntimeException("short");

            // when
            String full = ExceptionUtils.getStackTrace(ex);
            String limited = ExceptionUtils.getStackTrace(ex, 10000);

            // then
            assertThat(limited).isEqualTo(full);
        }
    }

    @Nested
    @DisplayName("getRootCause")
    class GetRootCause {

        @Test
        void singleException_returnsItself() {
            // given
            var ex = new RuntimeException("alone");

            // when
            Throwable root = ExceptionUtils.getRootCause(ex);

            // then
            assertThat(root).isSameAs(ex);
        }

        @Test
        void chainedCauses_returnsDeepest() {
            // given
            var root = new IllegalArgumentException("root");
            var mid = new RuntimeException("mid", root);
            var top = new RuntimeException("top", mid);

            // when
            Throwable result = ExceptionUtils.getRootCause(top);

            // then
            assertThat(result).isSameAs(root);
        }

        @Test
        void maxDepth_stopsEarly() {
            // given
            var root = new IllegalArgumentException("root");
            var mid = new RuntimeException("mid", root);
            var top = new RuntimeException("top", mid);

            // when
            Throwable result = ExceptionUtils.getRootCause(top, 1);

            // then
            assertThat(result).isSameAs(mid);
        }

        @Test
        void cyclicCauseChain_doesNotHang() {
            // given
            var b = new CyclicException("b");
            b.cyclicCause = new CyclicException("a", b);

            // when / then
            assertThatCode(() -> ExceptionUtils.getRootCause(b))
                    .doesNotThrowAnyException();
        }
    }

    /**
     * Throwable-подкласс для эмуляции циклической цепочки причин без рефлексии.
     * {@code getCause()} возвращает {@code cyclicCause}, если оно задано.
     */
    private static class CyclicException extends RuntimeException {
        Throwable cyclicCause;

        CyclicException(String message) {
            super(message);
        }

        CyclicException(String message, Throwable cause) {
            super(message, cause);
        }

        @Override
        public synchronized Throwable getCause() {
            return cyclicCause != null ? cyclicCause : super.getCause();
        }
    }

    @Nested
    @DisplayName("getRootCauseMessage")
    class GetRootCauseMessage {

        @Test
        void returnsRootMessage() {
            // given
            var root = new IllegalArgumentException("root msg");
            var top = new RuntimeException("top", root);

            // when
            String message = ExceptionUtils.getRootCauseMessage(top, 10, "fallback");

            // then
            assertThat(message).isEqualTo("root msg");
        }

        @Test
        void blankRootMessage_returnsFallback() {
            // given
            var root = new IllegalArgumentException("   ");
            var top = new RuntimeException("top", root);

            // when
            String message = ExceptionUtils.getRootCauseMessage(top, 10, "fallback");

            // then
            assertThat(message).isEqualTo("fallback");
        }

        @Test
        void nullRootMessage_returnsFallback() {
            // given
            var root = new IllegalArgumentException((String) null);

            // when
            String message = ExceptionUtils.getRootCauseMessage(root, 10, "fb");

            // then
            assertThat(message).isEqualTo("fb");
        }
    }

    @Nested
    @DisplayName("getMessageOrTypeName")
    class GetMessageOrTypeName {

        @Test
        void nonBlankMessage_returnsMessage() {
            // given
            var ex = new RuntimeException("hello");

            // when
            String result = ExceptionUtils.getMessageOrTypeName(ex);

            // then
            assertThat(result).isEqualTo("hello");
        }

        @Test
        void nullMessage_returnsClassName() {
            // given
            var ex = new RuntimeException((String) null);

            // when
            String result = ExceptionUtils.getMessageOrTypeName(ex);

            // then
            assertThat(result).isEqualTo("java.lang.RuntimeException");
        }

        @Test
        void blankMessage_returnsClassName() {
            // given
            var ex = new RuntimeException("   ");

            // when
            String result = ExceptionUtils.getMessageOrTypeName(ex);

            // then
            assertThat(result).isEqualTo("java.lang.RuntimeException");
        }
    }
}
