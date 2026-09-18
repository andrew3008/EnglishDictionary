package space.br1440.platform.tracing.otel.javaagent.propagation;

import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PlatformPropagatorsDefaultsCustomizer (add platform-trace-control if absent)")
class PlatformPropagatorsDefaultsCustomizerTest {

    private final PlatformPropagatorsDefaultsCustomizer customizer = new PlatformPropagatorsDefaultsCustomizer();

    private Map<String, String> apply(Map<String, String> config) {
        return customizer.apply(DefaultConfigProperties.createFromMap(config));
    }

    @Test
    @DisplayName("otel.propagators не задан → override = tracecontext,baggage,platform-trace-control")
    void default_appends_platform() {
        // when
        Map<String, String> override = apply(Map.of());
        // then
        assertThat(override).containsEntry("otel.propagators",
                "tracecontext,baggage,platform-trace-control");
    }

    @Test
    @DisplayName("otel.propagators=tracecontext,baggage → дописывается platform-trace-control")
    void explicit_w3c_appends_platform() {
        // when
        Map<String, String> override = apply(Map.of("otel.propagators", "tracecontext,baggage"));
        // then
        assertThat(override).containsEntry("otel.propagators",
                "tracecontext,baggage,platform-trace-control");
    }

    @Test
    @DisplayName("otel.propagators уже содержит platform-trace-control → override пуст (нет дубля)")
    void already_present_no_override() {
        // when
        Map<String, String> override = apply(Map.of(
                "otel.propagators", "tracecontext,baggage,platform-trace-control"));
        // then
        assertThat(override).isEmpty();
    }

    @Test
    @DisplayName("otel.propagators=none → override пуст (оператор отключил всё)")
    void none_excludes_platform() {
        // when
        Map<String, String> override = apply(Map.of("otel.propagators", "none"));
        // then
        assertThat(override).isEmpty();
    }

    @Test
    @DisplayName("otel.propagators=b3 → platform дописывается рядом")
    void custom_propagator_appends_platform() {
        // when
        Map<String, String> override = apply(Map.of("otel.propagators", "b3"));
        // then
        assertThat(override).containsEntry("otel.propagators", "b3,platform-trace-control");
    }
}
