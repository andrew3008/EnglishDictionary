package space.br1440.platform.tracing.otel.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExceptionRecordingPolicyTest {

    private static final StackTraceElement[] NO_FRAMES = new StackTraceElement[0];

    @ParameterizedTest
    @ValueSource(ints = {500, 511, 512, 600})
    void detectsSensitiveAssignmentBeforeMessageTruncation(int prefixLength) {
        // given
        IllegalStateException exception = new IllegalStateException("x".repeat(prefixLength) + " password=secret-canary");
        ExceptionRecordingPolicy policy = ExceptionRecordingPolicy.secureDefault();

        // when
        String message = policy.sanitizeOrNull(exception);

        // then
        assertThat(message).isEqualTo("<redacted>");
    }

    @Test
    void masksUrlCredentialsBeforeMessageTruncation() {
        // given
        String userinfo = "user:" + "secret".repeat(100);
        IllegalStateException exception = new IllegalStateException(
                "Failed https://" + userinfo + "@host/?token=value#fragment-canary");
        ExceptionRecordingPolicy policy = new ExceptionRecordingPolicy(true, false, 1_000);

        // when
        String message = policy.sanitizeOrNull(exception);

        // then
        assertThat(message).isEqualTo("Failed https://***@host/?token=***#***");
    }

    @Test
    void masksEveryAllowedCauseMessage() {
        // given
        IllegalStateException cause = new IllegalStateException("Cause http://host/?token=cause-secret#cause-fragment-canary");
        cause.setStackTrace(NO_FRAMES);
        IllegalStateException exception = new IllegalStateException("Failed https://user:password@host/", cause);
        exception.setStackTrace(NO_FRAMES);
        ExceptionRecordingPolicy policy = new ExceptionRecordingPolicy(true, true, 1_000);

        // when
        String rendered = renderStack(policy, exception);

        // then
        assertThat(rendered).isNotNull();
        assertThat(rendered).contains("Failed https://***@host/", "Cause http://host/?token=***#***");
        assertThat(rendered).doesNotContain("password", "cause-secret", "cause-fragment-canary");
        assertThat(policy.sanitizeOrNull(exception)).isEqualTo("Failed https://***@host/");
    }

    @Test
    void omitsOversizedMessageAndKeepsExceptionType() {
        // given
        IllegalStateException exception = new IllegalStateException("https://user:" + "s".repeat(4_096) + "@host/");
        exception.setStackTrace(NO_FRAMES);
        ExceptionRecordingPolicy policy = new ExceptionRecordingPolicy(true, true, 1_000);

        // when
        String message = policy.sanitizeOrNull(exception);
        String rendered = policy.renderStack(exception, message);

        // then
        assertThat(message).isNull();
        assertThat(rendered).isNotNull();
        assertThat(rendered).isEqualTo("java.lang.IllegalStateException\n");
    }

    @Test
    void omitsMessageWithTruncatedUserinfo() {
        // given
        IllegalStateException exception = new IllegalStateException("Failed https://user:secret");
        ExceptionRecordingPolicy policy = new ExceptionRecordingPolicy(true, false, 1_000);

        // when
        String message = policy.sanitizeOrNull(exception);

        // then
        assertThat(message).isNull();
    }

    @Test
    void rendersClassHeaderAndFrameWithinBudget() {
        // given
        IllegalStateException exception = new IllegalStateException("boom");
        exception.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("com.example.A", "method", "A.java", 4)
        });
        ExceptionRecordingPolicy policy = new ExceptionRecordingPolicy(true, true, 1_000);

        // when
        String rendered = renderStack(policy, exception);

        // then
        assertThat(rendered).isNotNull();
        assertThat(rendered).isEqualTo("""
                java.lang.IllegalStateException: boom
                \tat com.example.A.method(A.java:4)
                """);
        assertThat(rendered).hasSizeLessThan(1_000);
    }

    @Test
    void omitsFramesBeyondCapWithoutExhaustingCharacterBudget() {
        // given
        Throwable exception = new IllegalStateException("boom");
        exception.setStackTrace(frames(40, "com.example.Short"));
        ExceptionRecordingPolicy policy = new ExceptionRecordingPolicy(true, true, 100_000);

        // when
        String rendered = renderStack(policy, exception);

        // then
        assertThat(rendered).isNotNull();
        assertThat(rendered).contains(BoundedExceptionStackRenderer.framesOmitted(8));
        assertThat(rendered).doesNotContain(BoundedExceptionStackRenderer.BUDGET_EXHAUSTED);
        assertThat(countOf(rendered, "\tat ")).isEqualTo(BoundedExceptionStackRenderer.MAX_FRAMES);
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
        String rendered = renderStack(policy, outer);

        // then
        assertThat(rendered).isNotNull();
        assertThat(rendered).contains("root-marker");
        assertThat(rendered).contains("Caused by: java.lang.IllegalStateException: root-marker");
        assertThat(rendered).contains(BoundedExceptionStackRenderer.BUDGET_EXHAUSTED);
        assertThat(rendered.length()).isLessThanOrEqualTo(200);
        assertThat(rendered).doesNotContain("VeryLongClassNameThatConsumesBudget39");
    }

    @Test
    void stopsCauseChainAtTenLevels() {
        // given
        Throwable exception = causeChain(12);
        ExceptionRecordingPolicy policy = new ExceptionRecordingPolicy(true, true, 100_000);

        // when
        String rendered = renderStack(policy, exception);

        // then
        assertThat(rendered).isNotNull();
        assertThat(countOf(rendered, "Caused by:")).isEqualTo(BoundedExceptionStackRenderer.MAX_CAUSED_BY_LEVELS);
        assertThat(rendered).contains("cause-10");
        assertThat(rendered).contains(BoundedExceptionStackRenderer.FURTHER_CAUSES_OMITTED);
        assertThat(rendered).doesNotContain("cause-11");
        assertThat(rendered).doesNotContain("cause-12");
    }

    @Test
    void keepsExactlyTenCauseLevels() {
        // given
        Throwable exception = causeChain(10);
        ExceptionRecordingPolicy policy = new ExceptionRecordingPolicy(true, true, 100_000);

        // when
        String rendered = renderStack(policy, exception);

        // then
        assertThat(rendered).isNotNull();
        assertThat(countOf(rendered, "Caused by:")).isEqualTo(10);
        assertThat(rendered).contains("cause-10");
    }

    @Test
    void stopsCircularCauseChain() {
        // given
        IllegalStateException first = new IllegalStateException("first");
        IllegalStateException second = new IllegalStateException("second", first);
        first.initCause(second);
        first.setStackTrace(NO_FRAMES);
        second.setStackTrace(NO_FRAMES);
        ExceptionRecordingPolicy policy = new ExceptionRecordingPolicy(true, true, 1_000);

        // when
        String rendered = renderStack(policy, first);

        // then
        assertThat(rendered).isNotNull();
        assertThat(rendered).contains("Caused by: [CIRCULAR REFERENCE: java.lang.IllegalStateException]");
        assertThat(countOf(rendered, "Caused by:")).isEqualTo(2);
    }

    @Test
    void doesNotRenderSuppressedExceptions() {
        // given
        IllegalStateException exception = new IllegalStateException("outer");
        exception.setStackTrace(NO_FRAMES);
        exception.addSuppressed(new SuppressedSecretException("suppressed-secret-value"));
        ExceptionRecordingPolicy policy = new ExceptionRecordingPolicy(true, true, 1_000);

        // when
        String rendered = renderStack(policy, exception);

        // then
        assertThat(rendered).isNotNull();
        assertThat(rendered).doesNotContain("suppressed-secret-value");
        assertThat(rendered).doesNotContain("SuppressedSecretException");
        assertThat(rendered).doesNotContain("Suppressed:");
    }

    @Test
    void truncatesSanitizedMessageTo512Characters() {
        // given
        String message = "a".repeat(600);
        IllegalStateException exception = new IllegalStateException(message);
        exception.setStackTrace(NO_FRAMES);
        ExceptionRecordingPolicy policy = new ExceptionRecordingPolicy(true, true, 1_000);

        // when
        String rendered = renderStack(policy, exception);

        // then
        assertThat(rendered).isNotNull();
        assertThat(rendered).contains("a".repeat(512));
        assertThat(rendered).doesNotContain("a".repeat(513));
        assertThat(policy.sanitizeOrNull(exception)).hasSize(512);
    }

    @Test
    void stackHeaderOmitsMessageWhenMessagesAreDisabled() {
        // given
        IllegalStateException exception = new IllegalStateException("boom");
        exception.setStackTrace(NO_FRAMES);
        ExceptionRecordingPolicy policy = new ExceptionRecordingPolicy(false, true, 1_000);

        // when
        String rendered = policy.renderStack(exception, "boom");

        // then
        assertThat(rendered).isNotNull();
        assertThat(policy.sanitizeOrNull(exception)).isNull();
        assertThat(rendered).isEqualTo("java.lang.IllegalStateException\n");
    }

    @Test
    void returnsNullWhenStackIsDisabledOrExceptionIsNull() {
        // given
        ExceptionRecordingPolicy policy = new ExceptionRecordingPolicy(true, false, 1_000);

        // when
        String disabled = policy.renderStack(new IllegalStateException("boom"), null);
        String absent = new ExceptionRecordingPolicy(true, true, 1_000).renderStack(null, null);

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
                .hasMessage("SDK attribute value length limit must be positive");

        // then
        assertThatThrownBy(() -> new ExceptionRecordingPolicy(true, true, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("SDK attribute value length limit must be positive");
    }

    @ParameterizedTest
    @ValueSource(ints = {510, 511, 512})
    void truncationDoesNotSplitSurrogatePair(int prefixLength) {
        // given
        ExceptionRecordingPolicy policy = new ExceptionRecordingPolicy(true, true, 1_000);
        IllegalStateException exception = new IllegalStateException("a".repeat(prefixLength) + "\uD83D\uDE80z");

        // when
        String message = policy.sanitizeOrNull(exception);

        // then
        String expected = "a".repeat(prefixLength) + (prefixLength == 510 ? "\uD83D\uDE80" : "");
        assertThat(message).isEqualTo(expected);
        assertThat(message).hasSizeLessThanOrEqualTo(512);
    }

    private static String renderStack(ExceptionRecordingPolicy policy, Throwable exception) {
        return policy.renderStack(exception, policy.sanitizeOrNull(exception));
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
