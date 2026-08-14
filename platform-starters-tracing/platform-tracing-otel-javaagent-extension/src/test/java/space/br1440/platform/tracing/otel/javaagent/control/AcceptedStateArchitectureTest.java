package space.br1440.platform.tracing.otel.javaagent.control;

import space.br1440.platform.tracing.otel.javaagent.control.settings.model.ExpectedSettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsStore;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;

/**
 * Поведенческие инварианты accepted-state store/revision.
 * <p>
 * Пакетный allowlist, запрет {@code control.state}, словарь и единственный авторитет
 * изменения закреплены в {@code ControlPlaneArchTest} (срез S8).
 */
class AcceptedStateArchitectureTest {

    @Test
    void acceptedRevisionCarriesOnlyLogicalAcceptedState() {
        assertThat(TracingSettingsRevision.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("runtimeInstanceId", "acceptedSequence", "policy");
    }

    @Test
    void storeCannotAcceptGenericMapOrRebaseFunction() {
        Method commit = Arrays.stream(TracingSettingsStore.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("commit"))
                .findFirst()
                .orElseThrow();

        assertThat(commit.getParameterTypes())
                .containsExactly(ExpectedSettingsRevision.class, TracingSettings.class)
                .doesNotContain(Map.class, UnaryOperator.class);
        assertThat(TracingSettingsStore.class.getTypeParameters()).isEmpty();
    }
}
