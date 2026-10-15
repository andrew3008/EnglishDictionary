package space.br1440.platform.tracing.otel.javaagent.processor.dropoldest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;

class LifecycleStateTest {

    @Test
    void generatedProcessorLifecyclesMatchLifecycleState() {
        // given
        Set<String> expected = JmxWireContract.VALID_PROCESSOR_LIFECYCLES;

        // when
        Set<String> actual = Arrays.stream(LifecycleState.values())
                .map(Enum::name)
                .collect(Collectors.toUnmodifiableSet());

        // then
        assertThat(actual).isEqualTo(expected);
    }
}
