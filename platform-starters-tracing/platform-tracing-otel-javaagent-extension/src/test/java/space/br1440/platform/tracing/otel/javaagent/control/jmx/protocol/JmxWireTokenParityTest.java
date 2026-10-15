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
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;
import space.br1440.platform.tracing.otel.javaagent.processor.spantimeout.SpanTimeoutScannerState;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionLifecycle;

class JmxWireTokenParityTest {

    @Test
    void generatedTokenSetsMatchAgentEnums() {
        // given
        Set<String> expectedWriters = enumNames(SettingsWriter.values());
        Set<String> expectedAuthorities = enumNames(SettingsSourceAuthority.values());
        Set<String> expectedOutcomes = enumNames(SettingsUpdateStatus.values());
        Set<String> expectedMessageCodes = enumNames(SettingsRejectionReason.values());
        Set<String> expectedLogLevels = enumNames(AgentLogLevel.values());
        Set<String> expectedValidationLevels = enumNames(SpanAttributeValidationLevel.values());
        Set<String> expectedScannerStates = enumNames(SpanTimeoutScannerState.values());
        Set<String> expectedReadinessLifecycles = enumNames(PlatformExtensionLifecycle.values());

        // when
        Set<String> writers = JmxWireContract.VALID_WRITERS;
        Set<String> authorities = JmxWireContract.VALID_SOURCE_AUTHORITIES;
        Set<String> outcomes = JmxWireContract.VALID_OUTCOMES;
        Set<String> messageCodes = JmxWireContract.VALID_MESSAGE_CODES;
        Set<String> logLevels = JmxWireContract.VALID_LOG_LEVELS;
        Set<String> validationLevels = JmxWireContract.VALID_VALIDATION_LEVELS;
        Set<String> scannerStates = JmxWireContract.VALID_SCANNER_STATES;
        Set<String> readinessLifecycles = JmxWireContract.VALID_READINESS_LIFECYCLES;

        // then
        assertThat(writers).isEqualTo(expectedWriters);
        assertThat(authorities).isEqualTo(expectedAuthorities);
        assertThat(outcomes).isEqualTo(expectedOutcomes);
        assertThat(messageCodes).isEqualTo(expectedMessageCodes);
        assertThat(logLevels).isEqualTo(expectedLogLevels);
        assertThat(validationLevels).isEqualTo(expectedValidationLevels);
        assertThat(scannerStates).isEqualTo(expectedScannerStates);
        assertThat(readinessLifecycles).isEqualTo(expectedReadinessLifecycles);
    }

    private static Set<String> enumNames(Enum<?>[] values) {
        return Arrays.stream(values)
                .map(Enum::name)
                .collect(Collectors.toUnmodifiableSet());
    }
}
