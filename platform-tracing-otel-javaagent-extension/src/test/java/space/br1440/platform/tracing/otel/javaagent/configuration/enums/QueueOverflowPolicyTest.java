package space.br1440.platform.tracing.otel.javaagent.configuration.enums;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults;

import static org.assertj.core.api.Assertions.assertThat;

class QueueOverflowPolicyTest {

    @Test
    void values_match_contract_strings() {
        assertThat(QueueOverflowPolicy.UPSTREAM.value()).isEqualTo("UPSTREAM");
        assertThat(QueueOverflowPolicy.DROP_OLDEST.value()).isEqualTo("DROP_OLDEST");
    }

    @Test
    void DROP_OLDEST_equals_platform_default() {
        assertThat(QueueOverflowPolicy.DROP_OLDEST.value())
                .isEqualTo(ExtensionDefaults.DEFAULT_QUEUE_OVERFLOW_POLICY);
    }
}
