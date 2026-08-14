package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@code order()} contract of both bootstrap SPI entrypoints (r5 plan &sect;12, S5
 * Gate A: "provider/listener order contract"). This does not resolve upstream tie-break behavior
 * against other {@code Ordered} implementations — S0A already recorded that as nondeterministic
 * and S7 packaged qualification exercises the order matrix in a real Agent JVM. This test only
 * guards against an accidental, silent drift of either pinned constant.
 */
class BootstrapSpiOrderContractTest {

    @Test
    void ACP_provider_и_AgentListener_используют_один_и_тот_же_документированный_order() {
        int providerOrder = new BootstrapAutoConfigurationCustomizerProvider(BootstrapCoordinator.create()).order();
        int listenerOrder = new ActivationAgentListener(BootstrapCoordinator.create()).order();

        assertThat(providerOrder).isEqualTo(100);
        assertThat(listenerOrder).isEqualTo(100);
    }
}
