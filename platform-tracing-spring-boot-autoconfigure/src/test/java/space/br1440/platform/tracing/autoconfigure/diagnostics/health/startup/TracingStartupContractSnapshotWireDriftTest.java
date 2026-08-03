package space.br1440.platform.tracing.autoconfigure.diagnostics.health.startup;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.agent.AgentRuntimeState;
import space.br1440.platform.tracing.otel.runtime.state.TracingRuntimeMode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TracingStartupContractSnapshot} держит wire-строки, а не {@code TracingRuntimeMode}/
 * {@code AgentRuntimeState}, чтобы startup-ядро не зависело от {@code otel.runtime} и
 * {@code autoconfigure.agent} (иначе позитивный ArchUnit-boundary Rule A стал бы невыполним).
 * Этот тест — единственная точка, сверяющая обе системы типов, и ловит переименование enum-константы,
 * которое иначе прошло бы мимо компиляции и молча превратило бы каждый снимок в
 * {@code IllegalArgumentException} при старте контекста.
 */
class TracingStartupContractSnapshotWireDriftTest {

    @Test
    void wireRuntimeModeConstantsMatchEnumNames() {
        assertThat(TracingStartupContractSnapshot.WIRE_RUNTIME_MODE_ENABLED)
                .isEqualTo(TracingRuntimeMode.ENABLED.name());
        assertThat(TracingStartupContractSnapshot.WIRE_RUNTIME_MODE_DISABLED_BY_CONFIGURATION)
                .isEqualTo(TracingRuntimeMode.DISABLED_BY_CONFIGURATION.name());
    }

    @Test
    void wireAgentStateConstantsMatchEnumNames() {
        assertThat(TracingStartupContractSnapshot.WIRE_AGENT_STATE_READY)
                .isEqualTo(AgentRuntimeState.AGENT_READY.name());
        assertThat(TracingStartupContractSnapshot.WIRE_AGENT_STATE_DISABLED)
                .isEqualTo(AgentRuntimeState.DISABLED.name());
    }
}
