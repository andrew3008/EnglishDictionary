package space.br1440.platform.tracing.api.propagation;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.span.RemoteSpanLink;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TraceparentParserTest {

    private static final String VALID_TRACEPARENT =
            "00-0102030405060708090a0b0c0d0e0f10-0102030405060708-01";

    @Test
    void parseTraceparent_validValue_returnsSampledLink() {
        Optional<RemoteSpanLink> parsed = TraceparentParser.parseTraceparent(VALID_TRACEPARENT);

        assertThat(parsed).isPresent();
        assertThat(parsed.get().traceId()).isEqualTo("0102030405060708090a0b0c0d0e0f10");
        assertThat(parsed.get().spanId()).isEqualTo("0102030405060708");
        assertThat(parsed.get().traceFlags()).isEqualTo((byte) 0x01);
        assertThat(parsed.get().traceState()).isNull();
    }

    @Test
    void parseTraceparent_uppercaseHex_isNormalized() {
        Optional<RemoteSpanLink> parsed = TraceparentParser.parseTraceparent(
                "00-0102030405060708090A0B0C0D0E0F10-0102030405060708-01");

        assertThat(parsed).isPresent();
        assertThat(parsed.get().traceId()).isEqualTo("0102030405060708090a0b0c0d0e0f10");
    }

    @Test
    void parseTraceparent_invalidValues_areIgnored() {
        assertThat(TraceparentParser.parseTraceparent(null)).isEmpty();
        assertThat(TraceparentParser.parseTraceparent("")).isEmpty();
        assertThat(TraceparentParser.parseTraceparent("not-a-traceparent")).isEmpty();
        assertThat(TraceparentParser.parseTraceparent(
                "00-00000000000000000000000000000000-0000000000000000-00")).isEmpty();
        assertThat(TraceparentParser.parseTraceparent(
                "00-0102030405060708090a0b0c0d0e0f10-0102030405060708-zz")).isEmpty();
    }

    @Test
    void requireTraceparent_validValue_returnsLink() {
        RemoteSpanLink link = TraceparentParser.requireTraceparent(VALID_TRACEPARENT);

        assertThat(link.traceId()).isEqualTo("0102030405060708090a0b0c0d0e0f10");
        assertThat(link.spanId()).isEqualTo("0102030405060708");
    }

    @Test
    void requireTraceparent_invalidValue_throws() {
        assertThatThrownBy(() -> TraceparentParser.requireTraceparent("invalid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid traceparent");
    }
}
