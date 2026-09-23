package space.br1440.platform.tracing.otel.javaagent.logging;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
        // then
        assertThat(BoundedThrowableRenderer.renderFrame(nativeMethod))
                .isEqualTo("com.acme.Foo.bar(Native Method)");
        assertThat(BoundedThrowableRenderer.renderFrame(unknownSource))
                .isEqualTo("com.acme.Foo.bar(Unknown Source)");
    }

    @Test
    void exception_messages_are_absent_from_the_digest() {
        // given
        Throwable root = new IllegalStateException(
                SECRET_MARKER,
                new IllegalArgumentException(SECRET_MARKER)
        );
        root.addSuppressed(new IllegalStateException(SECRET_MARKER));

        // when
        String rendered = BoundedThrowableRenderer.render(root);

        // then
        assertThat(rendered).doesNotContain(SECRET_MARKER);
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
        String longClassName = "c".repeat(BoundedThrowableRenderer.MAX_TOTAL_LENGTH + 32);

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
        assertThat(capped).hasSize(BoundedThrowableRenderer.MAX_TOTAL_LENGTH);
    }
}
