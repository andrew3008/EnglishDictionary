package space.br1440.platform.tracing.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Matcher;

import org.junit.jupiter.api.Test;

class PackagedTracingFailureEnvelopeTest {

    @Test
    void failureEnvelopeMatchesReasonScopedSummary() {
        // given
        String output = """
                Platform tracing startup rejected: runtimeState=EXTENSION_INCOMPATIBLE, \
                reason=EXTENSION_VERSION_MISMATCH, applicationVersion=1.2.0, \
                extensionVersion=2.0.0. Use the tracing starter and controlled Java agent \
                distribution from the same platform release.""";

        // when
        Matcher matcher = PackagedTracingCertificationTest.FAILURE_ENVELOPE.matcher(output);

        // then
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group(1)).isEqualTo("EXTENSION_INCOMPATIBLE");
        assertThat(matcher.group(2)).isEqualTo("EXTENSION_VERSION_MISMATCH");
        assertThat(matcher.find()).isFalse();
    }
}
