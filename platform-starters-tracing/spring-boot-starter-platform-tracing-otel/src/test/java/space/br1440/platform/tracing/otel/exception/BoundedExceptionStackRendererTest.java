package space.br1440.platform.tracing.otel.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        IllegalStateException exception = new IllegalStateException("boom");
        exception.setStackTrace(frames(3, "com.example.Type"));

        // when
        String rendered = render(budget, RAW_MESSAGE, exception);

        // then
        assertThat(rendered)
                .hasSize(budget)
                .isEqualTo((BoundedExceptionStackRenderer.BUDGET_EXHAUSTED + "\n").substring(0, budget));
    }

    @Test
    void budgetOneAboveExhaustedLineKeepsOnlyTheMarker() {
        // given
        IllegalStateException exception = new IllegalStateException("boom");
        exception.setStackTrace(frames(3, "com.example.Type"));

        // when
        String rendered = render(EXHAUSTED_LINE + 1, RAW_MESSAGE, exception);

        // then
        assertThat(rendered).isEqualTo(BoundedExceptionStackRenderer.BUDGET_EXHAUSTED + "\n");
    }

    @Test
    void shortNaturalStackFitsBelowMarkerLength() {
        // given
        Throwable exception = new Throwable();
        exception.setStackTrace(NO_FRAMES);

        // when
        String rendered = render(32, RAW_MESSAGE, exception);

        // then
        assertThat(rendered).isEqualTo("java.lang.Throwable\n");
    }

    @Test
    void impossibleHeaderAtTinyBudgetDoesNotReadThrowableGetters() {
        // given
        CountingException exception = new CountingException("message", null);

        // when
        String rendered = new BoundedExceptionStackRenderer(1, RAW_MESSAGE).render(exception, null);

        // then
        assertThat(rendered).isEqualTo(".");
        assertThat(exception.messageReads).isZero();
        assertThat(exception.causeReads).isZero();
        assertThat(exception.stackReads).isZero();
    }

    @Test
    void headersExhaustingBudgetAvoidStackGetters() {
        // given
        CountingException cause = new CountingException("root-".repeat(100), null);
        CountingException exception = new CountingException("outer-".repeat(100), cause);
        int headers = (CountingException.class.getName() + "\n").length()
                + ("Caused by: " + CountingException.class.getName() + "\n").length();

        // when
        String rendered = render(EXHAUSTED_LINE + headers, RAW_MESSAGE, exception);

        // then
        assertThat(rendered).hasSize(EXHAUSTED_LINE + headers);
        assertThat(exception.stackReads).isZero();
        assertThat(cause.stackReads).isZero();
    }

    @Test
    void singleHeaderLongerThanBodyIsCutToBody() {
        // given
        IllegalStateException exception = new IllegalStateException("x".repeat(200));
        exception.setStackTrace(NO_FRAMES);
        int budget = EXHAUSTED_LINE + 20;

        // when
        String rendered = render(budget, RAW_MESSAGE, exception);

        // then
        assertThat(rendered).isEqualTo("java.lang.IllegalSt\n" + BoundedExceptionStackRenderer.BUDGET_EXHAUSTED + "\n");
        assertThat(rendered).hasSize(budget);
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
        String rendered = render(budget, RAW_MESSAGE, outer);

        // then
        assertThat(rendered).isEqualTo(causedBy + causedBy + BoundedExceptionStackRenderer.BUDGET_EXHAUSTED + "\n");
        assertThat(rendered).startsWith("Caused by: ");
    }

    @Test
    void droppingOuterHeadersRestoresDeepestMessageIntoFreedBudget() {
        // given
        CountingException root = new CountingException("ROOT_DIAGNOSTIC_57P03", null);
        CountingException middle = new CountingException("middle-".repeat(100), root);
        CountingException outer = new CountingException("outer-".repeat(100), middle);
        String bareHeader = "Caused by: " + CountingException.class.getName() + "\n";
        String rootHeader = "Caused by: " + CountingException.class.getName() + ": ROOT_DIAGNOSTIC_57P03\n";
        int budget = EXHAUSTED_LINE + bareHeader.length() + rootHeader.length();

        // when
        String rendered = render(budget, RAW_MESSAGE, outer);

        // then
        assertThat(rendered).isEqualTo(bareHeader + rootHeader + BoundedExceptionStackRenderer.BUDGET_EXHAUSTED + "\n");
        assertThat(outer.stackReads).isZero();
        assertThat(middle.stackReads).isZero();
        assertThat(root.stackReads).isZero();
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
        String rendered = render(budget, RAW_MESSAGE, outer);

        // then
        assertThat(rendered).isEqualTo(outerHeader + rootHeader + BoundedExceptionStackRenderer.BUDGET_EXHAUSTED + "\n");
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
        String rendered = render(budget, RAW_MESSAGE, outer);

        // then
        assertThat(rendered).isEqualTo(outerHeader + rootHeader
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
        String rendered = render(EXHAUSTED_LINE + outerHeader.length() + rootHeader.length(), RAW_MESSAGE, outer);

        // then
        assertThat(rendered).isEqualTo(outerHeader + rootHeader + BoundedExceptionStackRenderer.BUDGET_EXHAUSTED + "\n");
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
        String rendered = render(EXHAUSTED_LINE + headers + rootFrames, RAW_MESSAGE, outer);

        // then
        assertThat(rendered)
                .contains("\tat com.example.Root0.method")
                .contains("\tat com.example.Root2.method")
                .doesNotContain("com.example.Outer")
                .doesNotContain("frames omitted");
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
        String rendered = render(100_000, RAW_MESSAGE, chain[0]);

        // then
        assertThat(rendered)
                .contains("Caused by: " + MutableCause.class.getName() + ": c10")
                .contains(BoundedExceptionStackRenderer.FURTHER_CAUSES_OMITTED)
                .doesNotContain("CIRCULAR");
        assertThat(rendered.split("\n")).hasSize(12);
    }

    @Test
    void furtherCausesMarkerIsDroppedWhenHeadersConsumeTheBudget() {
        // given
        Throwable exception = causeChain();
        String outerHeader = "java.lang.IllegalStateException\n";
        String causedByHeader = "Caused by: java.lang.IllegalStateException\n";
        int headers = outerHeader.length() + BoundedExceptionStackRenderer.MAX_CAUSED_BY_LEVELS * causedByHeader.length();
        int budget = EXHAUSTED_LINE + headers;

        // when
        String rendered = render(budget, RAW_MESSAGE, exception);

        // then
        assertThat(rendered).hasSize(budget);
        assertThat(rendered).doesNotContain(BoundedExceptionStackRenderer.FURTHER_CAUSES_OMITTED);
        assertThat(rendered).doesNotContain("cause-");
        assertThat(rendered).endsWith(BoundedExceptionStackRenderer.BUDGET_EXHAUSTED + "\n");
        assertThat(countCausedBy(rendered)).isEqualTo(BoundedExceptionStackRenderer.MAX_CAUSED_BY_LEVELS);
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
        String rendered = render(1_000, throwable -> null, first);

        // then
        assertThat(rendered).isEqualTo("""
                java.lang.IllegalStateException
                Caused by: java.lang.IllegalStateException
                Caused by: [CIRCULAR REFERENCE: java.lang.IllegalStateException]
                """);
    }

    @Test
    void sanitizerIsTheOnlySourceOfHeaderMessages() {
        // given
        IllegalStateException exception = new IllegalStateException("raw-secret");
        exception.setStackTrace(NO_FRAMES);

        // when
        String rendered = render(1_000, throwable -> "[redacted]", exception);

        // then
        assertThat(rendered).isEqualTo("java.lang.IllegalStateException: [redacted]\n");
    }

    @Test
    void emptySanitizerMessageIsRenderedAfterColon() {
        // given
        IllegalStateException exception = new IllegalStateException("raw");
        exception.setStackTrace(NO_FRAMES);

        // when
        String rendered = render(1_000, throwable -> "", exception);

        // then
        assertThat(rendered).isEqualTo("java.lang.IllegalStateException: \n");
    }

    @Test
    void naturalCostEqualToBudgetIsNotTruncated() {
        // given
        IllegalStateException exception = new IllegalStateException("boom");
        exception.setStackTrace(NO_FRAMES);
        String natural = render(10_000, RAW_MESSAGE, exception);

        // when
        String rendered = render(natural.length(), RAW_MESSAGE, exception);

        // then
        assertThat(rendered).isEqualTo(natural);
        assertThat(rendered).hasSize(natural.length());
    }

    @ParameterizedTest
    @ValueSource(ints = {999, 1_000, 1_001})
    void exactNaturalFrameBudgetDoesNotReserveExhaustedMarker(int budget) {
        // given
        Throwable exception = new Throwable();
        String className = "x".repeat(1_000 - "java.lang.Throwable\n\tat .run(A.java:1)\n".length());
        StackTraceElement frame = new StackTraceElement(className, "run", "A.java", 1);
        exception.setStackTrace(new StackTraceElement[] {frame});
        String natural = "java.lang.Throwable\n\tat " + frame + "\n";

        // when
        String rendered = render(budget, RAW_MESSAGE, exception);

        // then
        assertThat(natural).hasSize(1_000);
        assertThat(rendered).hasSizeLessThanOrEqualTo(budget);
        if (budget >= natural.length()) {
            assertThat(rendered).isEqualTo(natural);
        } else {
            assertThat(rendered).contains(BoundedExceptionStackRenderer.BUDGET_EXHAUSTED);
        }
    }

    @Test
    void preservesJdkFrameSyntaxAtExactBudget() {
        // given
        Throwable exception = new Throwable();
        StackTraceElement[] frames = {
                new StackTraceElement("app", "module", "1.0", "a.Type", "nativeCall", "Type.java", -2),
                new StackTraceElement("a.Type", "unknownCall", null, -1),
                new StackTraceElement("a.Type", "sourceCall", "Type.java", 17)
        };
        exception.setStackTrace(frames);
        StringBuilder natural = new StringBuilder("java.lang.Throwable\n");
        for (StackTraceElement frame : frames) {
            natural.append("\tat ").append(frame).append('\n');
        }
        String expected = natural.toString();

        // when
        String rendered = render(expected.length(), RAW_MESSAGE, exception);

        // then
        assertThat(rendered).isEqualTo(expected);
    }

    @Test
    void readsEachThrowableOnceAcrossNaturalAndBudgetedRendering() {
        // given
        CountingException cause = new CountingException("root", null);
        cause.setStackTrace(frames(2, "com.example.Root"));
        CountingException exception = new CountingException("outer", cause);
        exception.setStackTrace(frames(32, "com.example.Outer" + "x".repeat(100)));

        // when
        String rendered = render(1_000, RAW_MESSAGE, exception);

        // then
        assertThat(rendered).contains("com.example.Root0.method", BoundedExceptionStackRenderer.BUDGET_EXHAUSTED);
        assertThat(exception.messageReads).isEqualTo(1);
        assertThat(exception.causeReads).isEqualTo(1);
        assertThat(exception.stackReads).isEqualTo(1);
        assertThat(cause.messageReads).isEqualTo(1);
        assertThat(cause.causeReads).isEqualTo(1);
        assertThat(cause.stackReads).isEqualTo(1);
    }

    @Test
    void usesProvidedRootMessageWithoutCallingItsSanitizer() {
        // given
        CountingException exception = new CountingException("raw", null);
        exception.setStackTrace(NO_FRAMES);

        // when
        String rendered = new BoundedExceptionStackRenderer(1_000, throwable -> {
            throw new AssertionError("Root sanitizer must not be called");
        }).render(exception, "sanitized");

        // then
        assertThat(rendered).isEqualTo(CountingException.class.getName() + ": sanitized\n");
        assertThat(exception.messageReads).isZero();
    }

    @Test
    void oversizedFrameComponentsStopTheRemainingFramePrefix() {
        // given
        Throwable exception = new Throwable();
        exception.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("x".repeat(100_000), "run", "Type.java", 1),
                new StackTraceElement("com.example.LaterFrame", "run", "Type.java", 2)
        });

        // when
        String rendered = render(1_000, RAW_MESSAGE, exception);

        // then
        assertThat(rendered).isEqualTo("java.lang.Throwable\n"
                + BoundedExceptionStackRenderer.framesOmitted(2) + "\n"
                + BoundedExceptionStackRenderer.BUDGET_EXHAUSTED + "\n");
    }

    @Test
    void oversizedIgnoredNativeSourceIsConservativelyOmitted() {
        // given
        Throwable exception = new Throwable();
        exception.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("a.Type", "nativeCall", "x".repeat(100_000), -2)
        });

        // when
        String rendered = render(1_000, RAW_MESSAGE, exception);

        // then
        assertThat(rendered).contains(BoundedExceptionStackRenderer.framesOmitted(1));
        assertThat(rendered).doesNotContain("Native Method");
    }

    @Test
    void nullFrameFromOverridingThrowableUsesBoundedLiteral() {
        // given
        Throwable exception = new Throwable() {
            @Override
            public StackTraceElement[] getStackTrace() {
                return new StackTraceElement[] {null};
            }
        };

        // when
        String rendered = render(1_000, RAW_MESSAGE, exception);

        // then
        assertThat(rendered).isEqualTo(exception.getClass().getName() + "\n\tat null\n");
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
        String rendered = render(1_000, RAW_MESSAGE, exception);

        // then
        assertThat(rendered).endsWith(": no-frames\n");
    }

    @Test
    void defaultCharacterBudgetStaysWithinSdkAttributeValueLengthLimit() {
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
        List<String> rendered = new ArrayList<>();
        for (int caseIndex = 0; caseIndex < throwables.size(); caseIndex++) {
            rendered.add(render(budgets.get(caseIndex), ExceptionRecordingPolicy::sanitizedMessageOrNull,
                    throwables.get(caseIndex)));
        }

        // then
        for (int caseIndex = 0; caseIndex < rendered.size(); caseIndex++) {
            String stack = rendered.get(caseIndex);
            assertThat(stack.length()).isLessThanOrEqualTo(budgets.get(caseIndex));
            assertThat(stack).endsWith("\n").doesNotContain("\r");
            assertThat(stack.startsWith("\tat ")).isFalse();
            if (stack.contains(BoundedExceptionStackRenderer.BUDGET_EXHAUSTED)) {
                assertThat(stack).endsWith(BoundedExceptionStackRenderer.BUDGET_EXHAUSTED + "\n");
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

    private static String render(int budget, Function<Throwable, String> sanitizer, Throwable throwable) {
        return new BoundedExceptionStackRenderer(budget, sanitizer).render(throwable, sanitizer.apply(throwable));
    }

    private static Throwable causeChain() {
        Throwable current = new IllegalStateException("cause-12");
        current.setStackTrace(NO_FRAMES);
        for (int index = 11; index >= 0; index--) {
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

    private static int countCausedBy(String text) {
        String token = "Caused by:";
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

        if ((chain.size() > 1) && (random.nextInt(3) == 0) && (chain.getFirst() instanceof MutableCauseException deepest)) {
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

    private static final class CountingException extends RuntimeException {

        private int messageReads;
        private int causeReads;
        private int stackReads;

        private CountingException(String message, Throwable cause) {
            super(message, cause);
        }

        @Override
        public String getMessage() {
            messageReads++;
            return super.getMessage();
        }

        @Override
        public synchronized Throwable getCause() {
            causeReads++;
            return super.getCause();
        }

        @Override
        public StackTraceElement[] getStackTrace() {
            stackReads++;
            return super.getStackTrace();
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
