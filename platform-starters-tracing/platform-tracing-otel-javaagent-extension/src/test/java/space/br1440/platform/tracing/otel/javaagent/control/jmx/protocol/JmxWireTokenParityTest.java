package space.br1440.platform.tracing.otel.javaagent.control.jmx.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsSourceAuthority;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsWriter;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsRejectionReason;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateStatus;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;

class JmxWireTokenParityTest {

    @Test
    void generatedTokenSetsMatchAgentEnums() {
        // given
        Set<String> expectedWriters = enumNames(SettingsWriter.values());
        Set<String> expectedAuthorities = enumNames(SettingsSourceAuthority.values());
        Set<String> expectedOutcomes = enumNames(SettingsUpdateStatus.values());
        Set<String> expectedMessageCodes = enumNames(SettingsRejectionReason.values());

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

    private static Set<String> enumNames(Enum<?>[] values) {
        return Arrays.stream(values)
                .map(Enum::name)
                .collect(Collectors.toUnmodifiableSet());
    }
}
