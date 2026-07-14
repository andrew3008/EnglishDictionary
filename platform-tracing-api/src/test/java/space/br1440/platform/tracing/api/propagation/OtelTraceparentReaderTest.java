package space.br1440.platform.tracing.api.propagation;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.span.RemoteSpanLink;
import space.br1440.platform.tracing.core.propagation.OtelTraceparentReaderImpl;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link OtelTraceparentReaderImpl} via the {@link OtelTraceparentReader} interface.
 * Placed in the api test package to keep the test name stable and because the interface
 * contract is what callers depend on.
 */
class OtelTraceparentReaderTest {

    private static final OtelTraceparentReader READER = OtelTraceparentReaderImpl.INSTANCE;

    private static final String TRACE_ID = "0102030405060708090a0b0c0d0e0f10";
    private static final String SPAN_ID = "0102030405060708";
    private static final String VALID_TRACEPARENT = "00-" + TRACE_ID + "-" + SPAN_ID + "-01";
    private static final String PROBE_TRACE_ID = "01234567890123456789012345678901";
    private static final String PROBE_SPAN_ID = "0123456789012345";

    // ------------------------------------------------------------------
    // read(traceparent) — single-header overload
    // ------------------------------------------------------------------

    @Test
    void read_validV00_returnsSampledLink() {
        Optional<RemoteSpanLink> parsed = READER.read(VALID_TRACEPARENT);

        assertThat(parsed).isPresent();
        RemoteSpanLink link = parsed.orElseThrow();
        assertThat(link.traceId()).isEqualTo(TRACE_ID);
        assertThat(link.spanId()).isEqualTo(SPAN_ID);
        assertThat(link.traceFlags()).isEqualTo((byte) 0x01);
        assertThat(link.traceState()).isNull();
    }

    @Test
    void read_invalidValues_areEmpty() {
        assertThat(READER.read((String) null)).isEmpty();
        assertThat(READER.read("")).isEmpty();
        assertThat(READER.read("   ")).isEmpty();
        assertThat(READER.read("not-a-traceparent")).isEmpty();
        assertThat(READER.read("00-00000000000000000000000000000000-" + SPAN_ID + "-01")).isEmpty();
        assertThat(READER.read("00-" + TRACE_ID + "-0000000000000000-01")).isEmpty();
        assertThat(READER.read("00-" + TRACE_ID + "-" + SPAN_ID + "-zz")).isEmpty();
        assertThat(READER.read("00-" + TRACE_ID + "-" + SPAN_ID)).isEmpty();
        assertThat(READER.read("00-" + TRACE_ID.substring(0, 31) + "-" + SPAN_ID + "-01")).isEmpty();
    }

    @Test
    void read_prohibitivelyLongInput_isEmpty() {
        assertThat(READER.read("x".repeat(10_000))).isEmpty();
    }

    @Test
    void read_probeObservedEdgeCases_followOtelBehavior() {
        // version=ff is rejected by OTel W3CTraceContextPropagator (W3C reserves 0xff as invalid)
        assertThat(READER.read("ff-" + PROBE_TRACE_ID + "-" + PROBE_SPAN_ID + "-01")).isEmpty();
        // extra parts for version=00 are rejected per W3C spec
        assertThat(READER.read("00-" + PROBE_TRACE_ID + "-" + PROBE_SPAN_ID + "-01-extra")).isEmpty();
        // flags must be exactly 2 hex chars
        assertThat(READER.read("00-" + PROBE_TRACE_ID + "-" + PROBE_SPAN_ID + "-1")).isEmpty();
        assertThat(READER.read("00-" + PROBE_TRACE_ID + "-" + PROBE_SPAN_ID + "-001")).isEmpty();

        // uppercase hex is normalised to lowercase by OTel
        Optional<RemoteSpanLink> uppercase = READER.read(
                "00-" + PROBE_TRACE_ID.toUpperCase() + "-" + PROBE_SPAN_ID.toUpperCase() + "-01");
        assertThat(uppercase).isPresent();
        assertThat(uppercase.orElseThrow().traceId()).isEqualTo(PROBE_TRACE_ID);
        assertThat(uppercase.orElseThrow().spanId()).isEqualTo(PROBE_SPAN_ID);

        // future version (not 00, not ff) with extra parts is accepted per W3C §3.3 forward-compat
        Optional<RemoteSpanLink> futureVersionExtra = READER.read(
                "01-" + PROBE_TRACE_ID + "-" + PROBE_SPAN_ID + "-01-extra");
        assertThat(futureVersionExtra).isPresent();
        assertThat(futureVersionExtra.orElseThrow().traceId()).isEqualTo(PROBE_TRACE_ID);
    }

    @Test
    void read_flagsAreBitPreserving() {
        Optional<RemoteSpanLink> unsampled = READER.read("00-" + TRACE_ID + "-" + SPAN_ID + "-00");
        assertThat(unsampled).isPresent();
        assertThat(unsampled.orElseThrow().traceFlags()).isEqualTo((byte) 0x00);

        Optional<RemoteSpanLink> allFlags = READER.read("00-" + TRACE_ID + "-" + SPAN_ID + "-ff");
        assertThat(allFlags).isPresent();
        assertThat(allFlags.orElseThrow().traceFlags()).isEqualTo((byte) 0xff);
    }

    // ------------------------------------------------------------------
    // read(traceparent, tracestate) — dual-header overload (defect #2 fix)
    // ------------------------------------------------------------------

    @Test
    void read_withValidTracestate_populatesTraceStateField() {
        String tracestate = "vendor1=abc123,vendor2=xyz789";
        Optional<RemoteSpanLink> result = READER.read(VALID_TRACEPARENT, tracestate);

        assertThat(result).isPresent();
        RemoteSpanLink link = result.orElseThrow();
        assertThat(link.traceId()).isEqualTo(TRACE_ID);
        assertThat(link.spanId()).isEqualTo(SPAN_ID);
        // traceState must be non-null and contain vendor entries
        assertThat(link.traceState()).isNotNull();
        assertThat(link.traceState()).contains("vendor1=abc123");
        assertThat(link.traceState()).contains("vendor2=xyz789");
    }

    @Test
    void read_withNullTracestate_traceStateRemainsNull() {
        Optional<RemoteSpanLink> result = READER.read(VALID_TRACEPARENT, null);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().traceState()).isNull();
    }

    @Test
    void read_withBlankTracestate_traceStateRemainsNull() {
        Optional<RemoteSpanLink> result = READER.read(VALID_TRACEPARENT, "  ");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().traceState()).isNull();
    }

    @Test
    void read_withInvalidTraceparentAndValidTracestate_isEmpty() {
        assertThat(READER.read("not-valid", "vendor=abc")).isEmpty();
    }

    // ------------------------------------------------------------------
    // require
    // ------------------------------------------------------------------

    @Test
    void require_validValue_returnsLink() {
        RemoteSpanLink link = READER.require(VALID_TRACEPARENT);

        assertThat(link.traceId()).isEqualTo(TRACE_ID);
        assertThat(link.spanId()).isEqualTo(SPAN_ID);
    }

    @Test
    void require_invalidValue_throwsSanitizedIllegalArgumentException() {
        assertThatThrownBy(() -> READER.require("invalid\ntraceparent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid traceparent")
                .hasMessageContaining("invalid?traceparent");
    }

    @Test
    void require_nullValue_throwsNullPointerException() {
        assertThatThrownBy(() -> READER.require(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("traceparent");
    }

    // ------------------------------------------------------------------
    // sanitize — truncation marker
    // ------------------------------------------------------------------

    @Test
    void sanitize_longInput_appendsTruncationMarker() {
        String longInput = "a".repeat(200);
        String sanitized = OtelTraceparentReaderImpl.sanitize(longInput);

        assertThat(sanitized).hasSize(128 + "\u2026[truncated]".length());
        assertThat(sanitized).endsWith("\u2026[truncated]");
    }

    @Test
    void sanitize_shortInput_noTruncation() {
        String sanitized = OtelTraceparentReaderImpl.sanitize("hello");
        assertThat(sanitized).isEqualTo("hello");
        assertThat(sanitized).doesNotContain("[truncated]");
    }
}
