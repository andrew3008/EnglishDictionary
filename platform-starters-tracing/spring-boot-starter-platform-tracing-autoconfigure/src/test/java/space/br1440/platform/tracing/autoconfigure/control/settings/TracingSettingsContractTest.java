package space.br1440.platform.tracing.autoconfigure.control.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapSettingsResolver;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingControlChannel;
import space.br1440.platform.tracing.autoconfigure.control.TracingConfigurationGuardException;

class TracingSettingsContractTest {

    @Test
    void controlChannelHasOnlySupportedValues() {
        // given
        // when
        TracingControlChannel[] values = TracingControlChannel.values();

        // then
        assertThat(values).containsExactly(
                TracingControlChannel.STATIC,
                TracingControlChannel.CONFIG_SERVER,
                TracingControlChannel.ACTUATOR);
    }

    @Test
    void actuatorIsAllowedWithoutEnvironmentGate() {
        // given
        var environment = new MockEnvironment()
                .withProperty("platform.tracing.control.channel", "ACTUATOR");

        // when
        var settings = TracingBootstrapSettingsResolver.resolve(environment);

        // then
        assertThat(settings.controlChannel()).isEqualTo(TracingControlChannel.ACTUATOR);
        assertThat(settings.tracingEnabled()).isTrue();
    }

    @Test
    void missingControlChannelIsRejected() {
        // given
        var environment = new MockEnvironment();

        // when
        // then
        assertThatThrownBy(() -> TracingBootstrapSettingsResolver.resolve(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing required property 'platform.tracing.control.channel'");
    }

    @Test
    void emptyControlChannelIsRejected() {
        // given
        var environment = new MockEnvironment()
                .withProperty("platform.tracing.control.channel", "  ");

        // when
        // then
        assertThatThrownBy(() -> TracingBootstrapSettingsResolver.resolve(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing required property 'platform.tracing.control.channel'");
    }

    @Test
    void unknownControlChannelIsRejected() {
        // given
        var environment = new MockEnvironment()
                .withProperty("platform.tracing.control.channel", "DEV_ACTUATOR");

        // when
        // then
        assertThatThrownBy(() -> TracingBootstrapSettingsResolver.resolve(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid property 'platform.tracing.control.channel'");
    }

    @Test
    void disabledTracingWithConfigServerIsRejected() {
        // given
        var environment = new MockEnvironment()
                .withProperty("platform.tracing.control.channel", "CONFIG_SERVER")
                .withProperty("platform.tracing.enabled", "false");

        // when
        // then
        assertThatThrownBy(() -> TracingBootstrapSettingsResolver.resolve(environment))
                .isInstanceOf(TracingConfigurationGuardException.class)
                .hasMessage("Tracing disabled is incompatible with CONFIG_SERVER authority.");
    }

    @Test
    void partialSnapshotIsRejected() {
        var builder = CompleteTracingSettings.builder()
                .samplingEnabled(true)
                .samplingRatio(0.5);

        assertThatThrownBy(builder::build)
                .isInstanceOf(TracingSettingsManifestException.class);
    }

    @Test
    void completeSnapshotIsBuilt() {
        var builder = CompleteTracingSettings.builder()
                .samplingEnabled(true)
                .samplingRatio(0.5)
                .samplingRouteRatios(Map.of())
                .validationLevel("WARNING")
                .exporterEnabled(true)
                .traceControlHeadersEnabled(true)
                .diagnosticsLogLevel("INFO");

        CompleteTracingSettings settings = builder.build();

        assertThat(settings.samplingEnabled()).isTrue();
        assertThat(settings.samplingRatio()).isEqualTo(0.5);
        assertThat(settings.samplingRouteRatios()).isEmpty();
    }

    @Test
    void singleLeafMutationPreservesOtherSettings() {
        CompleteTracingSettings original = CompleteTracingSettings.builder()
                .samplingEnabled(true)
                .samplingRatio(1.0)
                .samplingRouteRatios(Map.of("checkout", 0.5, "search", 0.3))
                .validationLevel("WARNING")
                .exporterEnabled(true)
                .traceControlHeadersEnabled(true)
                .diagnosticsLogLevel("INFO")
                .build();

        CompleteTracingSettings mutated = original.withExporterEnabled(false);

        assertThat(mutated.exporterEnabled()).isFalse();
        assertThat(mutated.samplingRouteRatios())
                .containsExactlyInAnyOrderEntriesOf(Map.of("checkout", 0.5, "search", 0.3));
        assertThat(mutated.samplingEnabled()).isTrue();
        assertThat(mutated.validationLevel()).isEqualTo("WARNING");
        assertThat(mutated.traceControlHeadersEnabled()).isTrue();
        assertThat(mutated.diagnosticsLogLevel()).isEqualTo("INFO");
    }
}
