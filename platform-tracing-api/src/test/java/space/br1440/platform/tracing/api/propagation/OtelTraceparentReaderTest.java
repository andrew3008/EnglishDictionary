package space.br1440.platform.tracing.api.propagation;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.span.RemoteSpanLink;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OtelTraceparentReaderTest {

    private static final String TRACE_ID = "0102030405060708090a0b0c0d0e0f10";
    private static final String SPAN_ID = "0102030405060708";
    private static final String VALID_TRACEPARENT = "00-" + TRACE_ID + "-" + SPAN_ID + "-01";
    private static final String PROBE_TRACE_ID = "01234567890123456789012345678901";
    private static final String PROBE_SPAN_ID = "0123456789012345";

    @Test
    void read_validV00_returnsSampledLink() {
        Optional<RemoteSpanLink> parsed = OtelTraceparentReader.read(VALID_TRACEPARENT);

        assertThat(parsed).isPresent();
        RemoteSpanLink link = parsed.orElseThrow();
        assertThat(link.traceId()).isEqualTo(TRACE_ID);
        assertThat(link.spanId()).isEqualTo(SPAN_ID);
        assertThat(link.traceFlags()).isEqualTo((byte) 0x01);
        assertThat(link.traceState()).isNull();
    }

    @Test
    void read_invalidValues_areEmpty() {
        assertThat(OtelTraceparentReader.read(null)).isEmpty();
        assertThat(OtelTraceparentReader.read("")).isEmpty();
        assertThat(OtelTraceparentReader.read("   ")).isEmpty();
        assertThat(OtelTraceparentReader.read("not-a-traceparent")).isEmpty();
        assertThat(OtelTraceparentReader.read("00-00000000000000000000000000000000-" + SPAN_ID + "-01")).isEmpty();
        assertThat(OtelTraceparentReader.read("00-" + TRACE_ID + "-0000000000000000-01")).isEmpty();
        assertThat(OtelTraceparentReader.read("00-" + TRACE_ID + "-" + SPAN_ID + "-zz")).isEmpty();
        assertThat(OtelTraceparentReader.read("00-" + TRACE_ID + "-" + SPAN_ID)).isEmpty();
        assertThat(OtelTraceparentReader.read("00-" + TRACE_ID.substring(0, 31) + "-" + SPAN_ID + "-01")).isEmpty();
    }

    @Test
    void read_prohibitivelyLongInput_isEmpty() {
        assertThat(OtelTraceparentReader.read("x".repeat(10_000))).isEmpty();
    }

    @Test
    void read_probeObservedEdgeCases_followOtelBehavior() {
        assertThat(OtelTraceparentReader.read("ff-" + PROBE_TRACE_ID + "-" + PROBE_SPAN_ID + "-01")).isEmpty();
        assertThat(OtelTraceparentReader.read("00-" + PROBE_TRACE_ID + "-" + PROBE_SPAN_ID + "-01-extra")).isEmpty();
        assertThat(OtelTraceparentReader.read("00-" + PROBE_TRACE_ID + "-" + PROBE_SPAN_ID + "-1")).isEmpty();
        assertThat(OtelTraceparentReader.read("00-" + PROBE_TRACE_ID + "-" + PROBE_SPAN_ID + "-001")).isEmpty();

        Optional<RemoteSpanLink> uppercase = OtelTraceparentReader.read(
                "00-" + PROBE_TRACE_ID.toUpperCase() + "-" + PROBE_SPAN_ID.toUpperCase() + "-01");
        assertThat(uppercase).isPresent();
        assertThat(uppercase.orElseThrow().traceId()).isEqualTo(PROBE_TRACE_ID);
        assertThat(uppercase.orElseThrow().spanId()).isEqualTo(PROBE_SPAN_ID);

        Optional<RemoteSpanLink> futureVersionExtra = OtelTraceparentReader.read(
                "01-" + PROBE_TRACE_ID + "-" + PROBE_SPAN_ID + "-01-extra");
        assertThat(futureVersionExtra).isPresent();
        assertThat(futureVersionExtra.orElseThrow().traceId()).isEqualTo(PROBE_TRACE_ID);
    }

    @Test
    void read_flagsAreBitPreserving() {
        Optional<RemoteSpanLink> unsampled = OtelTraceparentReader.read("00-" + TRACE_ID + "-" + SPAN_ID + "-00");
        assertThat(unsampled).isPresent();
        assertThat(unsampled.orElseThrow().traceFlags()).isEqualTo((byte) 0x00);

        Optional<RemoteSpanLink> allFlags = OtelTraceparentReader.read("00-" + TRACE_ID + "-" + SPAN_ID + "-ff");
        assertThat(allFlags).isPresent();
        assertThat(allFlags.orElseThrow().traceFlags()).isEqualTo((byte) 0xff);
    }

    @Test
    void require_validValue_returnsLink() {
        RemoteSpanLink link = OtelTraceparentReader.require(VALID_TRACEPARENT);

        assertThat(link.traceId()).isEqualTo(TRACE_ID);
        assertThat(link.spanId()).isEqualTo(SPAN_ID);
    }

    @Test
    void require_invalidValue_throwsSanitizedIllegalArgumentException() {
        assertThatThrownBy(() -> OtelTraceparentReader.require("invalid\ntraceparent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid traceparent")
                .hasMessageContaining("invalid?traceparent");
    }

    @Test
    void require_nullValue_throwsNullPointerException() {
        assertThatThrownBy(() -> OtelTraceparentReader.require(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("traceparent");
    }
}
