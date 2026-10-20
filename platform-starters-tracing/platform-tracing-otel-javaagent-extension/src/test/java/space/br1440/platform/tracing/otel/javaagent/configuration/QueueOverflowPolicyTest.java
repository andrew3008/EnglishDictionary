package space.br1440.platform.tracing.otel.javaagent.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class QueueOverflowPolicyTest {

    @Test
    void values_match_configuration_contract() {
        // given/when/then
        assertThat(QueueOverflowPolicy.values())
                .containsExactly(QueueOverflowPolicy.UPSTREAM, QueueOverflowPolicy.DROP_OLDEST);
    }

    @Test
    void drop_oldest_is_platform_default() {
        // given/when/then
        assertThat(ExtensionDefaults.DEFAULT_QUEUE_OVERFLOW_POLICY)
                .isSameAs(QueueOverflowPolicy.DROP_OLDEST);
    }
}
