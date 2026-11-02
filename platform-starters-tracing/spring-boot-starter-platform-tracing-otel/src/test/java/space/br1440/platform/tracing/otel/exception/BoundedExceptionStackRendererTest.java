package space.br1440.platform.tracing.otel.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoundedExceptionStackRendererTest {

    private static final StackTraceElement[] NO_FRAMES = new StackTraceElement[0];

    private static final Function<Throwable, String> RAW_MESSAGE = Throwable::getMessage;

    private static final int EXHAUSTED_LINE = BoundedExceptionStackRenderer.BUDGET_EXHAUSTED.length() + 1;

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 10, 32, 33})
    void budgetNotLargerThanExhaustedLineReturnsItsPrefixOnly(int budget) {
        // given
        Throwable exception = withFrames("boom", 3);

        // when
        RenderedExceptionStack rendered = new BoundedExceptionStackRenderer(budget, RAW_MESSAGE).render(exception);

        // then
        assertThat(rendered.truncated()).isTrue();
        assertThat(rendered.text())
                .hasSize(budget)
                .isEqualTo((BoundedExceptionStackRenderer.BUDGET_EXHAUSTED + "\n").substring(0, budget));
    }

    @Test
    void budgetOneAboveExhaustedLineKeepsOnlyTheMarker() {
        // given
        Throwable exception = withFrames("boom", 3);

        // when
        RenderedExceptionStack rendered = new BoundedExceptionStackRenderer(EXHAUSTED_LINE + 1, RAW_MESSAGE)
                .render(exception);

        // then
        assertThat(rendered.text()).isEqualTo(BoundedExceptionStackRenderer.BUDGET_EXHAUSTED + "\n");
        assertThat(rendered.truncated()).isTrue();
    }

    @Test
    void singleHeaderLongerThanBodyIsCutToBody() {
        // given
        IllegalStateException exception = new IllegalStateException("x".repeat(200));
        exception.setStackTrace(NO_FRAMES);
        int budget = EXHAUSTED_LINE + 20;

        // when
        RenderedExceptionStack rendered = new BoundedExceptionStackRenderer(budget, RAW_MESSAGE).render(exception);

        // then
        assertThat(rendered.text()).isEqualTo("java.lang.IllegalSt\n" + BoundedExceptionStackRenderer.BUDGET_EXHAUSTED + "\n");
        assertThat(rendered.text()).hasSize(budget);
    }

    @Test
    void droppingOuterLevelsLeavesCausedByAsFirstLine() {
        // given
        Throwable root = new IllegalStateException("root");
        Throwable middle = new IllegalStateException("middle", root);
        Throwable outer = new IllegalStateException("outer", middle);
        for (Throwable level : new Throwable[] {root, middle, outer}) {
            level.setStackTrace(NO_FRAMES);
        }

        String causedBy = "Caused by: java.lang.IllegalStateException\n";
        int budget = EXHAUSTED_LINE + 2 * causedBy.length();

        // when
        RenderedExceptionStack rendered = new BoundedExceptionStackRenderer(budget, RAW_MESSAGE).render(outer);

        // then
        assertThat(rendered.text()).isEqualTo(causedBy + causedBy + BoundedExceptionStackRenderer.BUDGET_EXHAUSTED + "\n");
        assertThat(rendered.text()).startsWith("Caused by: ");
        assertThat(rendered.truncated()).isTrue();
    }

    @Test
    void messagesAreDroppedFromOuterLevelFirst() {
        // given
        Throwable root = new IllegalStateException("root-message");
        Throwable outer = new IllegalStateException("outer-message", root);
        root.setStackTrace(frames(1, "com.example.PushesNaturalCostOverBudget"));
        outer.setStackTrace(NO_FRAMES);
        String outerHeader = "java.lang.IllegalStateException\n";
        String rootHeader = "Caused by: java.lang.IllegalStateException: root-message\n";
        int budget = EXHAUSTED_LINE + outerHeader.length() + rootHeader.length();

        // when
        RenderedExceptionStack rendered = new BoundedExceptionStackRenderer(budget, RAW_MESSAGE).render(outer);

        // then
        assertThat(rendered.text()).isEqualTo(outerHeader + rootHeader + BoundedExceptionStackRenderer.BUDGET_EXHAUSTED + "\n");
    }

    @Test
    void circularReferenceOutranksFrames() {
        // given
        IllegalStateException outer = new IllegalStateException("outer");
        IllegalStateException root = new IllegalStateException("root", outer);
        outer.initCause(root);
        outer.setStackTrace(NO_FRAMES);
        root.setStackTrace(frames(5, "com.example.Deep"));
        String outerHeader = "java.lang.IllegalStateException: outer\n";
        String rootHeader = "Caused by: java.lang.IllegalStateException: root\n";
        String circular = "Caused by: [CIRCULAR REFERENCE: java.lang.IllegalStateException]\n";
        int oneFrame = ("\tat " + root.getStackTrace()[0] + "\n").length();
        int budget = EXHAUSTED_LINE + outerHeader.length() + rootHeader.length() + circular.length() + oneFrame
                + BoundedExceptionStackRenderer.framesOmitted(4).length() + 1;

        // when
        RenderedExceptionStack rendered = new BoundedExceptionStackRenderer(budget, RAW_MESSAGE).render(outer);

        // then
        assertThat(rendered.text()).isEqualTo(outerHeader + rootHeader
                + "\tat " + root.getStackTrace()[0] + "\n"
                + BoundedExceptionStackRenderer.framesOmitted(4) + "\n"
                + circular
                + BoundedExceptionStackRenderer.BUDGET_EXHAUSTED + "\n");
    }

    @Test
    void headersOutrankCircularReference() {
        // given
        IllegalStateException outer = new IllegalStateException("outer");
        IllegalStateException root = new IllegalStateException("root", outer);
        outer.initCause(root);
        outer.setStackTrace(NO_FRAMES);
        root.setStackTrace(frames(5, "com.example.Deep"));
        String outerHeader = "java.lang.IllegalStateException: outer\n";
        String rootHeader = "Caused by: java.lang.IllegalStateException: root\n";

        // when
        RenderedExceptionStack rendered = new BoundedExceptionStackRenderer(
                EXHAUSTED_LINE + outerHeader.length() + rootHeader.length(), RAW_MESSAGE).render(outer);

        // then
        assertThat(rendered.text()).isEqualTo(outerHeader + rootHeader + BoundedExceptionStackRenderer.BUDGET_EXHAUSTED + "\n");
    }

    @Test
    void framesAreAllocatedToDeepestCauseFirst() {
        // given
        IllegalStateException root = new IllegalStateException("root");
        root.setStackTrace(frames(3, "com.example.Root"));
        IllegalStateException outer = new IllegalStateException("outer", root);
        outer.setStackTrace(frames(3, "com.example.Outer"));
        int headers = "java.lang.IllegalStateException: outer\n".length()
                + "Caused by: java.lang.IllegalStateException: root\n".length();
        int rootFrames = 0;
        for (StackTraceElement frame : root.getStackTrace()) {
            rootFrames += ("\tat " + frame + "\n").length();
        }

        // when
        RenderedExceptionStack rendered = new BoundedExceptionStackRenderer(EXHAUSTED_LINE + headers + rootFrames, RAW_MESSAGE)
                .render(outer);

        // then
        assertThat(rendered.text())
                .contains("\tat com.example.Root0.method")
                .contains("\tat com.example.Root2.method")
                .doesNotContain("com.example.Outer")
                .doesNotContain("frames omitted");
        assertThat(rendered.truncated()).isTrue();
    }

    @Test
    void causeLimitIsCheckedBeforeCircularityOfTheEleventhCause() {
        // given
        MutableCause deepest = new MutableCause("c10");
        Throwable[] chain = new Throwable[11];
        chain[10] = deepest;
        for (int index = 9; index >= 0; index--) {
            chain[index] = new IllegalStateException("c" + index, chain[index + 1]);
        }

        deepest.cause = chain[0];
        for (Throwable level : chain) {
            level.setStackTrace(NO_FRAMES);
        }

        // when
        RenderedExceptionStack rendered = new BoundedExceptionStackRenderer(100_000, RAW_MESSAGE).render(chain[0]);

        // then
        assertThat(rendered.truncated()).isTrue();
        assertThat(rendered.text())
                .contains("Caused by: " + MutableCause.class.getName() + ": c10")
                .contains(BoundedExceptionStackRenderer.FURTHER_CAUSES_OMITTED)
                .doesNotContain("CIRCULAR");
        assertThat(rendered.text().split("\n")).hasSize(12);
    }

    @Test
    void furtherCausesMarkerIsDroppedWhenHeadersConsumeTheBudget() {
        // given
        Throwable exception = causeChain(12);
        String outerHeader = "java.lang.IllegalStateException\n";
        String causedByHeader = "Caused by: java.lang.IllegalStateException\n";
        int headers = outerHeader.length() + BoundedExceptionStackRenderer.MAX_CAUSED_BY_LEVELS * causedByHeader.length();
        int budget = EXHAUSTED_LINE + headers;

        // when
        RenderedExceptionStack rendered = new BoundedExceptionStackRenderer(budget, RAW_MESSAGE).render(exception);

        // then
        assertThat(rendered.truncated()).isTrue();
        assertThat(rendered.text()).hasSize(budget);
        assertThat(rendered.text()).doesNotContain(BoundedExceptionStackRenderer.FURTHER_CAUSES_OMITTED);
        assertThat(rendered.text()).doesNotContain("cause-");
        assertThat(rendered.text()).endsWith(BoundedExceptionStackRenderer.BUDGET_EXHAUSTED + "\n");
        assertThat(countOf(rendered.text(), "Caused by:")).isEqualTo(BoundedExceptionStackRenderer.MAX_CAUSED_BY_LEVELS);
    }

    @Test
    void circularReferenceNamesOnlyTheClassAndHidesTheMessage() {
        // given
        IllegalStateException first = new IllegalStateException("secret-first");
        IllegalStateException second = new IllegalStateException("second", first);
        first.initCause(second);
        first.setStackTrace(NO_FRAMES);
        second.setStackTrace(NO_FRAMES);

        // when
        RenderedExceptionStack rendered = new BoundedExceptionStackRenderer(1_000, throwable -> null).render(first);

        // then
        assertThat(rendered.text()).isEqualTo("""
                java.lang.IllegalStateException
                Caused by: java.lang.IllegalStateException
                Caused by: [CIRCULAR REFERENCE: java.lang.IllegalStateException]
                """);
        assertThat(rendered.truncated()).isFalse();
    }

    @Test
    void sanitizerIsTheOnlySourceOfHeaderMessages() {
        // given
        IllegalStateException exception = new IllegalStateException("raw-secret");
        exception.setStackTrace(NO_FRAMES);

        // when
        RenderedExceptionStack rendered = new BoundedExceptionStackRenderer(1_000, throwable -> "[redacted]").render(exception);

        // then
        assertThat(rendered.text()).isEqualTo("java.lang.IllegalStateException: [redacted]\n");
    }

    @Test
    void emptySanitizerMessageIsRenderedAfterColon() {
        // given
        IllegalStateException exception = new IllegalStateException("raw");
        exception.setStackTrace(NO_FRAMES);

        // when
        RenderedExceptionStack rendered = new BoundedExceptionStackRenderer(1_000, throwable -> "").render(exception);

        // then
        assertThat(rendered.text()).isEqualTo("java.lang.IllegalStateException: \n");
        assertThat(rendered.truncated()).isFalse();
    }

    @Test
    void naturalCostEqualToBudgetIsNotTruncated() {
        // given
        IllegalStateException exception = new IllegalStateException("boom");
        exception.setStackTrace(NO_FRAMES);
        String natural = new BoundedExceptionStackRenderer(10_000, RAW_MESSAGE).render(exception).text();

        // when
        RenderedExceptionStack rendered = new BoundedExceptionStackRenderer(natural.length(), RAW_MESSAGE).render(exception);

        // then
        assertThat(rendered.truncated()).isFalse();
        assertThat(rendered.text()).isEqualTo(natural);
        assertThat(rendered.text()).hasSize(natural.length());
    }

    @Test
    void nullStackTraceFromOverridingThrowableIsTreatedAsNoFrames() {
        // given
        Throwable exception = new Throwable("no-frames") {
            @Override
            public StackTraceElement[] getStackTrace() {
                return null;
            }
        };

        // when
        RenderedExceptionStack rendered = new BoundedExceptionStackRenderer(1_000, RAW_MESSAGE).render(exception);

        // then
        assertThat(rendered.text()).endsWith(": no-frames\n");
        assertThat(rendered.truncated()).isFalse();
    }

    @Test
    void defaultCharacterBudgetStaysWithinSdkAttributeValueLengthLimit() {
        // Бюджет не выше OtelSdkDefaults.DEFAULT_SPAN_ATTRIBUTE_VALUE_LENGTH_LIMIT
        // в модуле javaagent-extension. Иначе SDK оставит голову exception.stacktrace.
        // given
        int sdkAttributeValueLengthLimit = 1_000;

        // when
        int budget = BoundedExceptionStackRenderer.DEFAULT_CHARACTER_BUDGET;

        // then
        assertThat(budget).isEqualTo(sdkAttributeValueLengthLimit);
    }

    @Test
    void textAlwaysEndsWithLineFeedAndNeverExceedsBudgetOnRandomInput() {
        // given
        Random random = new Random(7L);
        List<Throwable> throwables = new ArrayList<>();
        List<Integer> budgets = new ArrayList<>();
        for (int caseIndex = 0; caseIndex < 5_000; caseIndex++) {
            throwables.add(randomChain(random));
            budgets.add(EXHAUSTED_LINE + 1 + random.nextInt(1_500));
        }

        // when
        List<RenderedExceptionStack> rendered = new ArrayList<>();
        for (int caseIndex = 0; caseIndex < throwables.size(); caseIndex++) {
            rendered.add(new BoundedExceptionStackRenderer(
                    budgets.get(caseIndex), ExceptionRecordingPolicy::sanitizedMessageOrNull)
                    .render(throwables.get(caseIndex)));
        }

        // then
        for (int caseIndex = 0; caseIndex < rendered.size(); caseIndex++) {
            RenderedExceptionStack stack = rendered.get(caseIndex);
            assertThat(stack.text().length()).isLessThanOrEqualTo(budgets.get(caseIndex));
            assertThat(stack.text()).endsWith("\n").doesNotContain("\r");
            assertThat(stack.text().startsWith("\tat ")).isFalse();
            if (stack.text().contains(BoundedExceptionStackRenderer.BUDGET_EXHAUSTED)) {
                assertThat(stack.truncated()).isTrue();
                assertThat(stack.text()).endsWith(BoundedExceptionStackRenderer.BUDGET_EXHAUSTED + "\n");
            }
        }
    }

    @Test
    void rejectsNonPositiveCharacterBudget() {
        // given
        int budget = 0;

        // when
        assertThatThrownBy(() -> new BoundedExceptionStackRenderer(budget, RAW_MESSAGE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("characterBudget must be positive");

        // then
        assertThatThrownBy(() -> new BoundedExceptionStackRenderer(-1, RAW_MESSAGE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("characterBudget must be positive");
    }

    @Test
    void rejectsNullSanitizer() {
        // given
        int budget = 1;

        // when
        Throwable thrown = catchThrowable(() -> new BoundedExceptionStackRenderer(budget, null));

        // then
        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessage("messageSanitizer");
    }

    private static Throwable withFrames(String message, int frameCount) {
        IllegalStateException exception = new IllegalStateException(message);
        exception.setStackTrace(frames(frameCount, "com.example.Type"));
        return exception;
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

    private static Throwable randomChain(Random random) {
        int depth = random.nextInt(14);
        List<Throwable> chain = new ArrayList<>();
        Throwable current = null;
        for (int level = depth; level >= 0; level--) {
            Throwable next = randomThrowable(random, level, current);
            chain.add(next);
            current = next;
        }

        if ((chain.size() > 1) && (random.nextInt(3) == 0) && (chain.get(0) instanceof MutableCauseException deepest)) {
            deepest.cause = chain.get(random.nextInt(chain.size()));
        }

        return current;
    }

    private static Throwable randomThrowable(Random random, int level, Throwable cause) {
        String message = switch (random.nextInt(6)) {
            case 0 -> null;
            case 1 -> "   ";
            case 2 -> "m" + level;
            case 3 -> "msg-" + level + "-" + "x".repeat(random.nextInt(80));
            case 4 -> "long-" + "y".repeat(400 + random.nextInt(300));
            default -> "  padded " + level + "  ";
        };
        Throwable throwable = switch (random.nextInt(3)) {
            case 0 -> new IllegalStateException(message, cause);
            case 1 -> new RuntimeException(message, cause);
            default -> new MutableCauseException(message, cause);
        };
        int frameCount = switch (random.nextInt(5)) {
            case 0 -> 0;
            case 1 -> 1 + random.nextInt(3);
            case 2 -> 20 + random.nextInt(20);
            case 3 -> 33 + random.nextInt(40);
            default -> random.nextInt(8);
        };
        StackTraceElement[] frames = new StackTraceElement[frameCount];
        String className = (random.nextBoolean() ? "a.B" : "com.example.service.VeryLongClassNameForBudgetPressure")
                + level;
        for (int index = 0; index < frameCount; index++) {
            frames[index] = new StackTraceElement(className, "m" + index, random.nextBoolean() ? "B.java" : null,
                    random.nextInt(3) == 0 ? -1 : index + 1);
        }

        throwable.setStackTrace(frames);
        return throwable;
    }

    private static final class MutableCause extends RuntimeException {

        private Throwable cause;

        private MutableCause(String message) {
            super(message, null);
        }

        @Override
        public synchronized Throwable getCause() {
            return cause;
        }
    }

    private static final class MutableCauseException extends Exception {

        private Throwable cause;

        private MutableCauseException(String message, Throwable cause) {
            super(message, null);
            this.cause = cause;
        }

        @Override
        public synchronized Throwable getCause() {
            return cause;
        }
    }
}
