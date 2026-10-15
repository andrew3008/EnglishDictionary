package space.br1440.platform.tracing.autoconfigure.control.jmx.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.control.settings.AcceptedSettingsAuthority;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsWriter;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.export.ExportProcessorValues;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

class JmxWireTokenParityTest {

    @Test
    void generatedTokenSetsMatchSpringEnums() {
        // given
        Set<String> expectedWriters = enumNames(SettingsWriter.values());
        Set<String> expectedAuthorities = enumNames(AcceptedSettingsAuthority.values());
        Set<String> expectedOutcomes = enumNames(TracingSettingsDeliveryResult.values());
        Set<String> expectedMessageCodes = enumNames(PlatformJmxResponse.MessageCode.values());

        // when
        Set<String> writers = JmxWireContract.VALID_WRITERS;
        Set<String> authorities = JmxWireContract.VALID_SOURCE_AUTHORITIES;
        Set<String> outcomes = JmxWireContract.VALID_OUTCOMES;
        Set<String> messageCodes = JmxWireContract.VALID_MESSAGE_CODES;

        // then
        assertThat(writers).isEqualTo(expectedWriters);
        assertThat(authorities).isEqualTo(expectedAuthorities);
        assertThat(outcomes).isEqualTo(expectedOutcomes);
        assertThat(messageCodes).isEqualTo(expectedMessageCodes);
    }

    @Test
    void springMessageCodesDoNotUseLocalClientFailureNamespace() {
        // given
        PlatformJmxResponse.MessageCode[] values = PlatformJmxResponse.MessageCode.values();

        // when
        Set<String> messageCodes = enumNames(values);

        // then
        assertThat(messageCodes).noneMatch(code -> code.startsWith("JMX_CLIENT_"));
    }

    @Test
    void generatedProcessorLifecyclesMatchApplicationModel() {
        // given
        Set<String> expected = enumNames(ExportProcessorValues.LifecycleState.values());

        // when
        Set<String> actual = JmxWireContract.VALID_PROCESSOR_LIFECYCLES;

        // then
        assertThat(actual).isEqualTo(expected);
    }

    private static Set<String> enumNames(Enum<?>[] values) {
        return Arrays.stream(values)
                .map(Enum::name)
                .collect(Collectors.toUnmodifiableSet());
    }
}
