package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BootstrapExceptionTest {

    @Test
    void message_includes_recorded_cause_and_preserves_causal_chain() {
        // given
        IllegalStateException cause = new IllegalStateException("invalid rule syntax");
        BootstrapFailure failure = new BootstrapFailure("RULE_SET_INVALID", "prepare", cause);

        // when
        BootstrapException exception = new BootstrapException(failure);

        // then
        assertThat(exception)
                .hasMessage("Platform bootstrap failure [RULE_SET_INVALID] during phase 'prepare': "
                        + "java.lang.IllegalStateException: invalid rule syntax");
        assertThat(exception.getCause()).isSameAs(cause);
    }

    @Test
    void message_omits_cause_suffix_when_cause_is_not_recorded() {
        // given
        BootstrapFailure failure = new BootstrapFailure("RULE_SET_INVALID", "prepare", null);

        // when
        BootstrapException exception = new BootstrapException(failure);

        // then
        assertThat(exception)
                .hasMessage("Platform bootstrap failure [RULE_SET_INVALID] during phase 'prepare'");
        assertThat(exception.getCause()).isNull();
    }
}
