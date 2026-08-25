package space.br1440.platform.tracing.otel.javaagent.diagnostics;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformLogControlRegistrationTest {

    @Test
    void bindCloseRestoresPreviousAndStaleCloseDoesNotClearNewerBinding() throws Exception {
        PlatformLogControl control = new PlatformLogControl();
        TracingSettingsSource policyA = TracingSettingsSource.isolatedDefaults();
        TracingSettingsSource policyB = TracingSettingsSource.isolatedSampling(true, Map.of(), 0.5d);

        AutoCloseable token1 = control.bindAcceptedPolicy(policyA);
        token1.close();

        AutoCloseable token2 = control.bindAcceptedPolicy(policyB);
        token1.close();
        assertThat(control.getLevel()).isNotNull();

        token2.close();
        token2.close();
    }

    @Test
    void rebindWhileActiveFails() throws Exception {
        PlatformLogControl control = new PlatformLogControl();
        AutoCloseable token1 = control.bindAcceptedPolicy(TracingSettingsSource.isolatedDefaults());
        assertThatThrownBy(() -> control.bindAcceptedPolicy(TracingSettingsSource.isolatedDefaults()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already has an active");
        token1.close();
    }
}
