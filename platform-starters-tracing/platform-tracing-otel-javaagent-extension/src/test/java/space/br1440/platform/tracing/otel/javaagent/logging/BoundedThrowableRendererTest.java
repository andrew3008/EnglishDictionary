package space.br1440.platform.tracing.otel.javaagent.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class BoundedThrowableRendererTest {

    private static final String SECRET_MARKER = "SECRET-MARKER";

    @Test
    void frame_uses_class_method_file_and_line() {
        // given
        StackTraceElement frame = new StackTraceElement(
                "app",
                "my.mod",
                "1.2",
                "com.acme.Foo",
                "bar",
                "Foo.java",
                42);

        // when
        String rendered = BoundedThrowableRenderer.renderFrame(frame);

        // then
        assertThat(rendered).isEqualTo("com.acme.Foo.bar(Foo.java:42)");
        assertThat(rendered).doesNotContain("app/", "my.mod@1.2");
    }

    @Test
    void native_method_and_unknown_source_use_fixed_locations() {
        // given
        StackTraceElement nativeMethod = new StackTraceElement(
                "com.acme.Foo",
                "bar",
                "Foo.java",
                -2);
        StackTraceElement unknownSource = new StackTraceElement(
                "com.acme.Foo",
                "bar",
                null,
                -1);

        // when
        String renderedNativeMethod = BoundedThrowableRenderer.renderFrame(nativeMethod);
        String renderedUnknownSource = BoundedThrowableRenderer.renderFrame(unknownSource);

        // then
        assertThat(renderedNativeMethod).isEqualTo("com.acme.Foo.bar(Native Method)");
        assertThat(renderedUnknownSource).isEqualTo("com.acme.Foo.bar(Unknown Source)");
    }

    @Test
    void exception_messages_are_absent_from_the_entire_graph_digest() {
        // given
        Throwable suppressedCause = stackless(new UnsupportedOperationException(SECRET_MARKER));
        Throwable suppressed = stackless(new IllegalArgumentException(SECRET_MARKER, suppressedCause));
        Throwable rootCause = stackless(new IllegalStateException(SECRET_MARKER));
        Throwable root = stackless(new RuntimeException(SECRET_MARKER, rootCause));
        root.addSuppressed(suppressed);

        // when
        String rendered = BoundedThrowableRenderer.render(root);

        // then
        assertThat(rendered)
                .doesNotContain(SECRET_MARKER)
                .contains(
                        IllegalStateException.class.getName(),
                        IllegalArgumentException.class.getName(),
                        UnsupportedOperationException.class.getName()
                );
    }

    @Test
    void cause_cycle_is_stopped_by_the_identity_set() {
        // given
        Throwable root = stackless(new IllegalStateException(SECRET_MARKER));
        Throwable cause = stackless(new IllegalArgumentException(SECRET_MARKER));
        root.initCause(cause);
        cause.initCause(root);

        // when
        String rendered = BoundedThrowableRenderer.render(root);

        // then
        assertThat(rendered)
                .doesNotContain(SECRET_MARKER)
                .contains("caused by ... (already visited)")
                .containsOnlyOnce(IllegalStateException.class.getName())
                .containsOnlyOnce(IllegalArgumentException.class.getName());
    }

    @Test
    void suppressed_cycle_is_stopped_by_the_identity_set() {
        // given
        Throwable root = stackless(new IllegalStateException(SECRET_MARKER));
        Throwable suppressed = stackless(new IllegalArgumentException(SECRET_MARKER));
        root.addSuppressed(suppressed);
        suppressed.addSuppressed(root);

        // when
        String rendered = BoundedThrowableRenderer.render(root);

        // then
        assertThat(rendered)
                .doesNotContain(SECRET_MARKER)
                .contains("suppressed ... (already visited)")
                .containsOnlyOnce(IllegalStateException.class.getName())
                .containsOnlyOnce(IllegalArgumentException.class.getName());
    }

    @Test
    void shared_cause_and_suppressed_node_is_rendered_once() {
        // given
        Throwable shared = stackless(new IllegalArgumentException(SECRET_MARKER));
        Throwable root = stackless(new IllegalStateException(SECRET_MARKER));
        root.initCause(shared);
        root.addSuppressed(shared);

        // when
        String rendered = BoundedThrowableRenderer.render(root);

        // then
        assertThat(rendered)
                .doesNotContain(SECRET_MARKER)
                .contains("suppressed ... (already visited)")
                .containsOnlyOnce(IllegalArgumentException.class.getName());
    }

    @Test
    void cause_of_suppressed_node_is_traversed() {
        // given
        Throwable nestedCause = stackless(new UnsupportedOperationException(SECRET_MARKER));
        Throwable suppressed = stackless(new IllegalArgumentException(SECRET_MARKER, nestedCause));
        Throwable root = stackless(new IllegalStateException(SECRET_MARKER));
        root.addSuppressed(suppressed);

        // when
        String rendered = BoundedThrowableRenderer.render(root);

        // then
        assertThat(rendered)
                .doesNotContain(SECRET_MARKER)
                .contains(
                        "suppressed " + IllegalArgumentException.class.getName(),
                        "caused by " + UnsupportedOperationException.class.getName()
                );
    }

    @Test
    void primary_cause_chain_precedes_suppressed_graph_and_has_an_independent_cause_budget() {
        // given
        Throwable suppressedChain = stackless(new UnsupportedOperationException(SECRET_MARKER));
        for (int i = 0; i <= BoundedThrowableRenderer.MAX_CAUSES; i++) {
            suppressedChain = stackless(new IllegalArgumentException(SECRET_MARKER, suppressedChain));
        }
        Throwable root = stackless(new IllegalStateException(SECRET_MARKER));
        root.addSuppressed(suppressedChain);
        root.initCause(stackless(new ArithmeticException(SECRET_MARKER)));

        // when
        String rendered = BoundedThrowableRenderer.render(root);

        // then
        assertThat(rendered)
                .doesNotContain(SECRET_MARKER)
                .contains(
                        "caused by " + ArithmeticException.class.getName(),
                        "suppressed " + IllegalArgumentException.class.getName(),
                        "cause edge budget exhausted"
                );
        assertThat(rendered.indexOf("caused by " + ArithmeticException.class.getName()))
                .isLessThan(rendered.indexOf("suppressed " + IllegalArgumentException.class.getName()));
        assertThat(rendered.lines().filter(line -> line.startsWith("caused by ")).count())
                .isEqualTo(BoundedThrowableRenderer.MAX_CAUSES + 2L);
        assertThat(rendered.lines().filter(line -> line.contains("cause edge budget exhausted")).count())
                .isEqualTo(1);
    }

    @Test
    void suppressed_budget_is_applied_per_node_and_named_by_its_marker() {
        // given
        Throwable root = stackless(new IllegalStateException(SECRET_MARKER));
        Throwable cause = stackless(new IllegalArgumentException(SECRET_MARKER));
        root.initCause(cause);
        for (int i = 0; i < BoundedThrowableRenderer.MAX_SUPPRESSED + 2; i++) {
            root.addSuppressed(stackless(new IllegalMonitorStateException(SECRET_MARKER)));
            cause.addSuppressed(stackless(new UnsupportedOperationException(SECRET_MARKER)));
        }

        // when
        String rendered = BoundedThrowableRenderer.render(root);

        // then
        assertThat(rendered).doesNotContain(SECRET_MARKER);
        assertThat(rendered).contains("per-node suppression budget exhausted");
        assertThat(rendered.lines().filter(line -> line.startsWith("suppressed ")).count())
                .isEqualTo(BoundedThrowableRenderer.MAX_SUPPRESSED * 2L);
        assertThat(rendered.lines().filter(line -> line.contains("per-node suppression budget exhausted")).count())
                .isEqualTo(2);
    }

    @Test
    void thousands_of_nodes_are_stopped_by_the_global_node_budget() {
        // given
        Throwable root = new GraphThrowable(SECRET_MARKER);
        Throwable current = root;
        for (int i = 0; i < 2_000; i++) {
            Throwable next = new GraphThrowable(SECRET_MARKER);
            current.addSuppressed(next);
            current = next;
        }

        // when
        String rendered = BoundedThrowableRenderer.render(root);

        // then
        assertThat(rendered)
                .doesNotContain(SECRET_MARKER)
                .contains("node budget exhausted")
                .hasSizeLessThanOrEqualTo(BoundedThrowableRenderer.maxDigestChars());
        assertThat(rendered.lines().filter(line -> line.endsWith(GraphThrowable.class.getName())).count())
                .isEqualTo(BoundedThrowableRenderer.MAX_NODES);
    }

    @Test
    void thousands_of_frames_are_stopped_by_the_global_frame_budget() {
        // given
        Throwable root = stackless(new IllegalStateException(SECRET_MARKER));
        Throwable suppressed = stackless(new IllegalArgumentException(SECRET_MARKER));
        root.setStackTrace(frames(15, "com.acme.RootFrame"));
        suppressed.setStackTrace(frames(2_000, "com.acme.SuppressedFrame"));
        root.addSuppressed(suppressed);

        // when
        String rendered = BoundedThrowableRenderer.render(root);

        // then
        assertThat(rendered)
                .doesNotContain(SECRET_MARKER)
                .contains("total frame budget exhausted")
                .hasSizeLessThanOrEqualTo(BoundedThrowableRenderer.maxDigestChars());
        assertThat(rendered.lines().filter(line -> line.startsWith("\tat ")).count())
                .isEqualTo(BoundedThrowableRenderer.MAX_FRAMES);
    }

    @Test
    void primary_cause_chain_receives_frames_before_suppressed_graph() {
        // given
        Throwable cause = stackless(new ArithmeticException(SECRET_MARKER));
        cause.setStackTrace(frames(100, "com.acme.CauseFrame"));
        Throwable suppressed = stackless(new IllegalArgumentException(SECRET_MARKER));
        suppressed.setStackTrace(frames(100, "com.acme.SuppressedFrame"));
        Throwable root = stackless(new IllegalStateException(SECRET_MARKER, cause));
        root.setStackTrace(frames(100, "com.acme.RootFrame"));
        root.addSuppressed(suppressed);

        // when
        String rendered = BoundedThrowableRenderer.render(root);

        // then
        assertThat(rendered)
                .doesNotContain(SECRET_MARKER)
                .contains("caused by " + ArithmeticException.class.getName())
                .contains("suppressed " + IllegalArgumentException.class.getName())
                .hasSizeLessThanOrEqualTo(BoundedThrowableRenderer.maxDigestChars());
        assertThat(rendered.lines().filter(line -> line.contains("com.acme.RootFrame")).count())
                .isEqualTo(BoundedThrowableRenderer.MAX_FRAMES / 2L);
        assertThat(rendered.lines().filter(line -> line.contains("com.acme.CauseFrame")).count())
                .isEqualTo(BoundedThrowableRenderer.MAX_FRAMES / 2L);
        assertThat(rendered).doesNotContain("com.acme.SuppressedFrame");
        assertThat(rendered.lines().filter(line -> line.startsWith("\tat ")).count())
                .isEqualTo(BoundedThrowableRenderer.MAX_FRAMES);
    }

    @Test
    void primary_cause_structure_survives_character_pressure_from_root_frame() {
        // given
        Throwable omittedCause = stackless(new IllegalMonitorStateException(SECRET_MARKER));
        Throwable thirdCause = stackless(new UnsupportedOperationException(SECRET_MARKER, omittedCause));
        Throwable secondCause = stackless(new IllegalArgumentException(SECRET_MARKER, thirdCause));
        Throwable firstCause = stackless(new ArithmeticException(SECRET_MARKER));
        firstCause.initCause(secondCause);
        Throwable root = stackless(new IllegalStateException(SECRET_MARKER, firstCause));
        root.setStackTrace(new StackTraceElement[]{
                new StackTraceElement(
                        "com.acme.RootFrame".repeat(BoundedThrowableRenderer.MAX_TOTAL_LENGTH),
                        "call",
                        "Frame.java",
                        1)
        });

        // when
        String rendered = BoundedThrowableRenderer.render(root);

        // then
        assertThat(rendered)
                .doesNotContain(SECRET_MARKER, IllegalMonitorStateException.class.getName())
                .contains(
                        "character budget reserved for primary cause chain",
                        "caused by " + ArithmeticException.class.getName(),
                        "caused by " + IllegalArgumentException.class.getName(),
                        "caused by " + UnsupportedOperationException.class.getName(),
                        "cause edge budget exhausted"
                )
                .hasSizeLessThanOrEqualTo(BoundedThrowableRenderer.maxDigestChars());
    }

    @Test
    void character_budget_is_enforced_while_rendering_a_frame() {
        // given
        Throwable root = stackless(new IllegalStateException(SECRET_MARKER));
        root.setStackTrace(new StackTraceElement[]{
                new StackTraceElement(
                        "c".repeat(BoundedThrowableRenderer.MAX_TOTAL_LENGTH + 1_000),
                        "call",
                        "Frame.java",
                        1)
        });

        // when
        String rendered = BoundedThrowableRenderer.render(root);

        // then
        assertThat(rendered)
                .doesNotContain(SECRET_MARKER)
                .endsWith("... (character budget exhausted)")
                .hasSizeLessThanOrEqualTo(BoundedThrowableRenderer.maxDigestChars());
    }

    @Test
    void rendering_failure_omits_the_exception_message_and_is_capped() {
        // given
        Throwable hostile = new IllegalStateException(SECRET_MARKER) {
            @Override
            public StackTraceElement[] getStackTrace() {
                throw new IllegalStateException(SECRET_MARKER);
            }
        };
        String longClassName = "\uD83D\uDE00".repeat(BoundedThrowableRenderer.MAX_TOTAL_LENGTH);

        // when
        String rendered = BoundedThrowableRenderer.render(hostile);
        String capped = BoundedThrowableRenderer.renderingFailureDetail(
                longClassName,
                "java.lang.IllegalStateException"
        );

        // then
        assertThat(rendered)
                .doesNotContain(SECRET_MARKER)
                .contains("detail rendering failed")
                .hasSizeLessThanOrEqualTo(BoundedThrowableRenderer.MAX_TOTAL_LENGTH);
        assertThat(capped)
                .startsWith("\uD83D\uDE00")
                .contains(
                        "... (character budget exhausted)",
                        "detail rendering failed",
                        "java.lang.IllegalStateException"
                )
                .hasSizeLessThanOrEqualTo(BoundedThrowableRenderer.maxDigestChars());
        int markerIndex = capped.indexOf("\n... (character budget exhausted)");
        assertThat(Character.isHighSurrogate(capped.charAt(markerIndex - 1))).isFalse();
    }

    @ParameterizedTest
    @MethodSource("fatalRenderingFailures")
    void fatal_rendering_failures_are_propagated(Error fatalFailure) {
        // given
        Throwable hostile = new IllegalStateException(SECRET_MARKER) {
            @Override
            public StackTraceElement[] getStackTrace() {
                throw fatalFailure;
            }
        };

        // when
        // then
        assertThatThrownBy(() -> BoundedThrowableRenderer.render(hostile))
                .isSameAs(fatalFailure);
    }

    @SuppressWarnings("removal")
    private static Stream<Error> fatalRenderingFailures() {
        return Stream.of(
                new OutOfMemoryError(SECRET_MARKER),
                new ThreadDeath(),
                new LinkageError(SECRET_MARKER)
        );
    }

    private static <T extends Throwable> T stackless(T throwable) {
        throwable.setStackTrace(new StackTraceElement[0]);
        return throwable;
    }

    private static StackTraceElement[] frames(int count, String classPrefix) {
        StackTraceElement[] frames = new StackTraceElement[count];
        for (int i = 0; i < frames.length; i++) {
            frames[i] = new StackTraceElement(classPrefix + i, "call", "Frame.java", i + 1);
        }
        return frames;
    }

    private static final class GraphThrowable extends Throwable {

        private GraphThrowable(String message) {
            super(message, null, true, false);
        }
    }
}
