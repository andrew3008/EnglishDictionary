package space.br1440.platform.tracing.otel.javaagent.jmx.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RemoteJmxDenialReasonTest {

    @Test
    void denial_reasons_publish_bounded_machine_codes() {
        // given
        // when
        // then
        assertThat(RemoteJmxDenialReason.values())
                .extracting(Enum::name)
                .containsExactly(
                        "NONE",
                        "REMOTE_JMX_EVIDENCE_UNKNOWN",
                        "REMOTE_JMX_MANAGEMENT_AGENT_CONFIGURED");
    }

    @Test
    void none_does_not_deny() {
        // given
        // when
        // then
        assertThat(RemoteJmxDenialReason.NONE.denies()).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = RemoteJmxDenialReason.class, mode = EnumSource.Mode.EXCLUDE, names = "NONE")
    void every_other_reason_denies(RemoteJmxDenialReason reason) {
        // given
        // when
        // then
        assertThat(reason.denies()).isTrue();
    }
}
