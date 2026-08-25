package space.br1440.platform.tracing.api.spi;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpanAttributeScrubbingRuleRemovalTest {

    @Test
    void oldSpiFqn_isNotPresent() {
        assertThatThrownBy(() -> Class.forName("space.br1440.platform.tracing.api.spi.SensitiveDataRule"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void newSpiFqn_isPresent() {
        assertThatCode(() -> Class.forName("space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule"))
                .doesNotThrowAnyException();
    }
}
