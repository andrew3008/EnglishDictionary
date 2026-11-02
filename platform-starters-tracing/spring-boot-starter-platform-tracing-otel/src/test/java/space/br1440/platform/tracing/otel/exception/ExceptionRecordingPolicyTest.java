package space.br1440.platform.tracing.otel.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ExceptionRecordingPolicyTest {

    private static final StackTraceElement[] NO_FRAMES = new StackTraceElement[0];

    @Test
    void rendersClassHeaderAndFrameWithinBudget() {
        // given
        IllegalStateException exception = new IllegalStateException("boom");
        exception.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("com.example.A", "method", "A.java", 4)
        });
        ExceptionRecordingPolicy policy = new ExceptionRecordingPolicy(true, true);

        // when
        RenderedExceptionStack rendered = policy.renderStack(exception);

        // then
        assertThat(rendered).isNotNull();
        assertThat(rendered.truncated()).isFalse();
        assertThat(rendered.text()).isEqualTo("""
                java.lang.IllegalStateException: boom
                \tat com.example.A.method(A.java:4)
                """);
        assertThat(rendered.text()).hasSizeLessThanOrEqualTo(BoundedExceptionStackRenderer.DEFAULT_CHARACTER_BUDGET);
    }

    @Test
    void omitsFramesBeyondCapWithoutExhaustingCharacterBudget() {
        // given
        Throwable exception = new IllegalStateException("boom");
        exception.setStackTrace(frames(40, "com.example.Short"));
        ExceptionRecordingPolicy policy = new ExceptionRecordingPolicy(true, true, 100_000);

        // when
        RenderedExceptionStack rendered = policy.renderStack(exception);

        // then
        assertThat(rendered).isNotNull();
        assertThat(rendered.truncated()).isTrue();
        assertThat(rendered.text()).contains(BoundedExceptionStackRenderer.framesOmitted(8));
        assertThat(rendered.text()).doesNotContain(BoundedExceptionStackRenderer.BUDGET_EXHAUSTED);
        assertThat(countOf(rendered.text(), "\tat ")).isEqualTo(BoundedExceptionStackRenderer.MAX_FRAMES);
    }

    @Test
    void keepsDeepestCauseHeaderWhenOuterFramesExceedBudget() {
        // given
        IllegalStateException cause = new IllegalStateException("root-marker");
        cause.setStackTrace(NO_FRAMES);
        IllegalStateException outer = new IllegalStateException("outer", cause);
        outer.setStackTrace(frames(40, "com.example.VeryLongClassNameThatConsumesBudget"));
        ExceptionRecordingPolicy policy = new ExceptionRecordingPolicy(true, true, 200);

        // when
        RenderedExceptionStack rendered = policy.renderStack(outer);

        // then
        assertThat(rendered).isNotNull();
        assertThat(rendered.truncated()).isTrue();
        assertThat(rendered.text()).contains("root-marker");
        assertThat(rendered.text()).contains("Caused by: java.lang.IllegalStateException: root-marker");
        assertThat(rendered.text()).contains(BoundedExceptionStackRenderer.BUDGET_EXHAUSTED);
        assertThat(rendered.text().length()).isLessThanOrEqualTo(200);
        assertThat(rendered.text()).doesNotContain("VeryLongClassNameThatConsumesBudget39");
    }

    @Test
    void stopsCauseChainAtTenLevels() {
        // given
        Throwable exception = causeChain(12);
        ExceptionRecordingPolicy policy = new ExceptionRecordingPolicy(true, true, 100_000);

        // when
        RenderedExceptionStack rendered = policy.renderStack(exception);

        // then
        assertThat(rendered).isNotNull();
        assertThat(rendered.truncated()).isTrue();
        assertThat(countOf(rendered.text(), "Caused by:")).isEqualTo(BoundedExceptionStackRenderer.MAX_CAUSED_BY_LEVELS);
        assertThat(rendered.text()).contains("cause-10");
        assertThat(rendered.text()).contains(BoundedExceptionStackRenderer.FURTHER_CAUSES_OMITTED);
        assertThat(rendered.text()).doesNotContain("cause-11");
        assertThat(rendered.text()).doesNotContain("cause-12");
    }

    @Test
    void keepsTenCauseLevelsWithoutTruncationFlag() {
        // given
        Throwable exception = causeChain(10);
        ExceptionRecordingPolicy policy = new ExceptionRecordingPolicy(true, true, 100_000);

        // when
        RenderedExceptionStack rendered = policy.renderStack(exception);

        // then
        assertThat(rendered).isNotNull();
        assertThat(rendered.truncated()).isFalse();
        assertThat(countOf(rendered.text(), "Caused by:")).isEqualTo(10);
        assertThat(rendered.text()).contains("cause-10");
    }

    @Test
    void stopsCircularCauseChain() {
        // given
        IllegalStateException first = new IllegalStateException("first");
        IllegalStateException second = new IllegalStateException("second", first);
        first.initCause(second);
        first.setStackTrace(NO_FRAMES);
        second.setStackTrace(NO_FRAMES);
        ExceptionRecordingPolicy policy = new ExceptionRecordingPolicy(true, true);

        // when
        RenderedExceptionStack rendered = policy.renderStack(first);

        // then
        assertThat(rendered).isNotNull();
        assertThat(rendered.text()).contains("Caused by: [CIRCULAR REFERENCE: java.lang.IllegalStateException]");
        assertThat(countOf(rendered.text(), "Caused by:")).isEqualTo(2);
        assertThat(rendered.truncated()).isFalse();
    }

    @Test
    void doesNotRenderSuppressedExceptions() {
        // given
        IllegalStateException exception = new IllegalStateException("outer");
        exception.setStackTrace(NO_FRAMES);
        exception.addSuppressed(new SuppressedSecretException("suppressed-secret-value"));
        ExceptionRecordingPolicy policy = new ExceptionRecordingPolicy(true, true);

        // when
        RenderedExceptionStack rendered = policy.renderStack(exception);

        // then
        assertThat(rendered).isNotNull();
        assertThat(rendered.text()).doesNotContain("suppressed-secret-value");
        assertThat(rendered.text()).doesNotContain("SuppressedSecretException");
        assertThat(rendered.text()).doesNotContain("Suppressed:");
    }

    @Test
    void truncatesSanitizedMessageTo512Characters() {
        // given
        String message = "a".repeat(600);
        IllegalStateException exception = new IllegalStateException(message);
        exception.setStackTrace(NO_FRAMES);
        ExceptionRecordingPolicy policy = new ExceptionRecordingPolicy(true, true);

        // when
        RenderedExceptionStack rendered = policy.renderStack(exception);

        // then
        assertThat(rendered).isNotNull();
        assertThat(rendered.text()).contains("a".repeat(512));
        assertThat(rendered.text()).doesNotContain("a".repeat(513));
        assertThat(policy.sanitizeOrNull(exception)).hasSize(512);
    }

    @Test
    void stackHeaderKeepsSanitizedMessageWhenMessageAttributeIsDisabled() {
        // given
        IllegalStateException exception = new IllegalStateException("boom");
        exception.setStackTrace(NO_FRAMES);
        ExceptionRecordingPolicy policy = new ExceptionRecordingPolicy(false, true);

        // when
        RenderedExceptionStack rendered = policy.renderStack(exception);

        // then
        assertThat(rendered).isNotNull();
        assertThat(policy.sanitizeOrNull(exception)).isNull();
        assertThat(rendered.text()).contains("boom");
    }

    @Test
    void returnsNullWhenStackIsDisabledOrExceptionIsNull() {
        // given
        ExceptionRecordingPolicy policy = new ExceptionRecordingPolicy(true, false);

        // when
        RenderedExceptionStack disabled = policy.renderStack(new IllegalStateException("boom"));
        RenderedExceptionStack absent = new ExceptionRecordingPolicy(true, true).renderStack(null);

        // then
        assertThat(disabled).isNull();
        assertThat(absent).isNull();
    }

    @Test
    void rejectsNonPositiveCharacterBudget() {
        // given
        int budget = 0;

        // when
        assertThatThrownBy(() -> new ExceptionRecordingPolicy(true, true, budget))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("characterBudget must be positive");

        // then
        assertThatThrownBy(() -> new ExceptionRecordingPolicy(true, true, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("characterBudget must be positive");
    }

    private static Throwable causeChain(int causedByLevels) {
        Throwable current = new IllegalStateException("cause-" + causedByLevels);
        current.setStackTrace(NO_FRAMES);
        for (int index = causedByLevels - 1; index >= 0; index--) {
            IllegalStateException wrapper = new IllegalStateException("cause-" + index, current);
            wrapper.setStackTrace(NO_FRAMES);
            current = wrapper;
        }

        return current;
    }

    @SuppressWarnings("SameParameterValue")
    private static StackTraceElement[] frames(int frameCount, String classNamePrefix) {
        StackTraceElement[] frames = new StackTraceElement[frameCount];
        for (int index = 0; index < frameCount; index++) {
            frames[index] = new StackTraceElement(classNamePrefix + index, "method", "Type.java", index + 1);
        }

        return frames;
    }

    private static int countOf(String text, String token) {
        int count = 0;
        int from = 0;
        while (from <= text.length()) {
            int found = text.indexOf(token, from);
            if (found < 0) {
                return count;
            }

            count++;
            from = found + token.length();
        }

        return count;
    }

    private static final class SuppressedSecretException extends Exception {

        private SuppressedSecretException(String message) {
            super(message);
        }
    }
}
