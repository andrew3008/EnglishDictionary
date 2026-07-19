package space.br1440.platform.tracing.api.propagation.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.Optional;

import org.junit.jupiter.api.Test;

class OutboundPropagationHeadersTest {

    @Test
    void emptyHasNoHeaders() {
        assertThat(OutboundPropagationHeaders.EMPTY.forceTrace()).isEmpty();
        assertThat(OutboundPropagationHeaders.EMPTY.qaTrace()).isEmpty();
        assertThat(OutboundPropagationHeaders.EMPTY.requestId()).isEmpty();
    }

    @Test
    void constructorRejectsNullSlots() {
        assertThatNullPointerException().isThrownBy(() ->
                new OutboundPropagationHeaders(null, Optional.empty(), Optional.empty()));
    }

    @Test
    void headerRejectsInvalidName() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new OutboundPropagationHeaders.Header("X Invalid", "value"));
    }

    @Test
    void headerRejectsLineBreaksInValue() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new OutboundPropagationHeaders.Header("X-Request-Id", "first\r\nsecond"));
    }
}
