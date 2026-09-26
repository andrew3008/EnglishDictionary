package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BootstrapSpiOrderContractTest {

    @Test
    void ACP_provider_and_AgentListener_use_same_documented_order() {
        // given/when
        int providerOrder = new BootstrapAutoConfigurationCustomizerProvider(BootstrapCoordinator.createDefault()).order();
        int listenerOrder = new ActivationAgentListener(BootstrapCoordinator.createDefault()).order();

        // then
        assertThat(providerOrder).isEqualTo(100);
        assertThat(listenerOrder).isEqualTo(100);
    }
}
