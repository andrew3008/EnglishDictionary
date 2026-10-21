package space.br1440.platform.tracing.otel.javaagent.control;

import static org.assertj.core.api.Assertions.assertThat;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_DIAGNOSTICS_LOG_LEVEL;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_ENABLED;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_EXPORTER_ENABLED;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_SAMPLING_RATIO;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_SPAN_ATTRIBUTE_VALIDATION_LEVEL;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_TRACE_CONTROL_HEADERS_ENABLED;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;

class TracingSettingsSourceDefaultPolicyTest {

    @Test
    void defaultPolicyMatchesExtensionDefaultsAndIsCapturedOnce() {
        // when
        TracingSettings policy = TracingSettingsSource.defaultPolicy();

        // then
        assertThat(policy.samplingEnabled()).isEqualTo(DEFAULT_ENABLED);
        assertThat(policy.samplingRatio()).isEqualTo(DEFAULT_SAMPLING_RATIO);
        assertThat(policy.samplingRouteRatios()).isEmpty();
        assertThat(policy.validationLevel()).isEqualTo(DEFAULT_SPAN_ATTRIBUTE_VALIDATION_LEVEL);
        assertThat(policy.exporterEnabled()).isEqualTo(DEFAULT_EXPORTER_ENABLED);
        assertThat(policy.traceControlHeadersEnabled()).isEqualTo(DEFAULT_TRACE_CONTROL_HEADERS_ENABLED);
        assertThat(policy.diagnosticsLogLevel()).isEqualTo(DEFAULT_DIAGNOSTICS_LOG_LEVEL);
        assertThat(TracingSettingsSource.defaultPolicy()).isSameAs(policy);
    }
}
