package space.br1440.platform.tracing.api.propagation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpanScopeRemovalTest {

    @Test
    void publicSpanScopeTypeIsAbsent() {
        assertThatThrownBy(() -> Class.forName("space.br1440.platform.tracing.api.span.SpanScope"))
                .isInstanceOf(ClassNotFoundException.class);
    }
}
