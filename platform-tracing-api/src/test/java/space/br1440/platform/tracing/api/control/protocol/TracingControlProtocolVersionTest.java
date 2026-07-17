package space.br1440.platform.tracing.api.control.protocol;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TracingControlProtocolVersionTest {

    @Test
    void parsesSupportedPrimitiveRepresentations() {
        assertThat(TracingControlProtocolVersion.parse(1)).contains(new TracingControlProtocolVersion(1));
        assertThat(TracingControlProtocolVersion.parse(1L)).contains(new TracingControlProtocolVersion(1));
        assertThat(TracingControlProtocolVersion.parse(" 1 ")).contains(new TracingControlProtocolVersion(1));
    }

    @Test
    void rejectsMalformedVersionRepresentations() {
        assertThat(TracingControlProtocolVersion.parse(null)).isEmpty();
        assertThat(TracingControlProtocolVersion.parse("")).isEmpty();
        assertThat(TracingControlProtocolVersion.parse("abc")).isEmpty();
        assertThat(TracingControlProtocolVersion.parse(new Object())).isEmpty();
    }
}
