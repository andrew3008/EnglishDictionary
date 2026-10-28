package space.br1440.platform.tracing.api.propagation.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import space.br1440.platform.tracing.api.propagation.PlatformHeaders;

class OutboundPropagationHeadersTest {

    @Test
    void emptyHasNoHeaders() {
        // when
        OutboundPropagationHeaders headers = OutboundPropagationHeaders.EMPTY;

        // then
        assertThat(headers.forceTrace()).isEmpty();
        assertThat(headers.qaTrace()).isEmpty();
        assertThat(headers.requestId()).isEmpty();
        assertThat(headers.correlationId()).isEmpty();
    }

    @Test
    @SuppressWarnings({"ConstantConditions", "DataFlowIssue"})
    void constructorRejectsNullSlots() {
        // when
        // then
        assertThatNullPointerException().isThrownBy(() ->
                new OutboundPropagationHeaders(null, Optional.empty(), Optional.empty(), Optional.empty()));

        assertThatNullPointerException().isThrownBy(() ->
                new OutboundPropagationHeaders(Optional.empty(), null, Optional.empty(), Optional.empty()));

        assertThatNullPointerException().isThrownBy(() ->
                new OutboundPropagationHeaders(Optional.empty(), Optional.empty(), null, Optional.empty()));

        assertThatNullPointerException().isThrownBy(() ->
                new OutboundPropagationHeaders(Optional.empty(), Optional.empty(), Optional.empty(), null));
    }

    @Test
    void slotsContainValuesForFixedHeaderNames() {
        // when
        OutboundPropagationHeaders headers = new OutboundPropagationHeaders(
                Optional.of("on"), Optional.of("1"), Optional.of("request-1"), Optional.of("correlation-1")
        );

        // then
        assertThat(headers.forceTrace()).contains("on");
        assertThat(headers.qaTrace()).contains("1");
        assertThat(headers.requestId()).contains("request-1");
        assertThat(headers.correlationId()).contains("correlation-1");
        assertThat(PlatformHeaders.OUTBOUND_CONTROL_HEADERS).containsExactly(
                PlatformHeaders.X_TRACE_ON, PlatformHeaders.X_QA_TRACE,
                PlatformHeaders.X_REQUEST_ID, PlatformHeaders.X_CORRELATION_ID
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"first\rsecond", "first\nsecond", "first\r\nsecond"})
    void constructorRejectsLineBreaksInEverySlot(String value) {
        // given
        Optional<String> unsafe = Optional.of(value);

        // when
        // then
        assertThatIllegalArgumentException().isThrownBy(() ->
                new OutboundPropagationHeaders(unsafe, Optional.empty(), Optional.empty(), Optional.empty()));

        assertThatIllegalArgumentException().isThrownBy(() ->
                new OutboundPropagationHeaders(Optional.empty(), unsafe, Optional.empty(), Optional.empty()));

        assertThatIllegalArgumentException().isThrownBy(() ->
                new OutboundPropagationHeaders(Optional.empty(), Optional.empty(), unsafe, Optional.empty()));

        assertThatIllegalArgumentException().isThrownBy(() ->
                new OutboundPropagationHeaders(Optional.empty(), Optional.empty(), Optional.empty(), unsafe));
    }
}
