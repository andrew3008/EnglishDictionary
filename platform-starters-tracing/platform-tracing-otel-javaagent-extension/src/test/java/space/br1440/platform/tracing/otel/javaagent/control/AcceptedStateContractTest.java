package space.br1440.platform.tracing.otel.javaagent.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsStore;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.ExpectedSettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;

class AcceptedStateContractTest {

    @Test
    void acceptedRevisionCarriesOnlyLogicalAcceptedState() {
        // given/when/then
        assertThat(TracingSettingsRevision.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("agentInstanceId", "acceptedSequence", "policy");
    }

    @Test
    void storeCannotAcceptGenericMapOrRebaseFunction() {
        // given
        Method commit = Arrays.stream(TracingSettingsStore.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("commit"))
                .findFirst()
                .orElseThrow();

        // then
        assertThat(commit.getParameterTypes())
                .containsExactly(ExpectedSettingsRevision.class, TracingSettings.class)
                .doesNotContain(Map.class, UnaryOperator.class);
        assertThat(TracingSettingsStore.class.getTypeParameters()).isEmpty();
    }
}
