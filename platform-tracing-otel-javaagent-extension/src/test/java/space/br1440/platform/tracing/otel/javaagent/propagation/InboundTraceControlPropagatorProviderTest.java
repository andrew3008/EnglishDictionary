package space.br1440.platform.tracing.otel.javaagent.propagation;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.core.propagation.control.PlatformTraceContextKeys;
import space.br1440.platform.tracing.api.propagation.control.InboundTraceControl;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-2 (Фаза 15): named {@code ConfigurablePropagatorProvider} ({@code platform-trace-control}).
 */
@DisplayName("InboundTraceControlPropagatorProvider (named platform-trace-control)")
class InboundTraceControlPropagatorProviderTest {

    private static final TextMapGetter<Map<String, String>> MAP_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    };

    private static ConfigProperties config(Map<String, String> map) {
        return DefaultConfigProperties.createFromMap(map);
    }

    @Test
    @DisplayName("getName == platform-trace-control")
    void getName_is_platform_trace_control() {
        assertThat(new InboundTraceControlPropagatorProvider().getName())
                .isEqualTo("platform-trace-control");
    }

    @Test
    @DisplayName("getPropagator извлекает X-Trace-On из carrier в Context")
    void getPropagator_extracts_X_Trace_On() {
        TextMapPropagator propagator =
                new InboundTraceControlPropagatorProvider().getPropagator(config(Map.of()));

        // fields() публикует платформенные заголовки (дефолтные имена).
        assertThat(propagator.fields()).contains("X-Trace-On", "X-QA-Trace", "X-Request-Id");

        Map<String, String> carrier = new HashMap<>();
        carrier.put("X-Trace-On", "on");
        carrier.put("X-Request-Id", "req-1");

        Context ctx = propagator.extract(Context.root(), carrier, MAP_GETTER);

        InboundTraceControl control = ctx.get(PlatformTraceContextKeys.TRACE_CONTROL);
        assertThat(control).isNotNull();
        assertThat(control.forceTrace()).isTrue();
        assertThat(control.requestId()).isEqualTo("req-1");
    }

    @Test
    @DisplayName("getPropagator учитывает кастомные имена заголовков из ConfigProperties")
    void getPropagator_honors_custom_header_names() {
        TextMapPropagator propagator = new InboundTraceControlPropagatorProvider()
                .getPropagator(config(Map.of(
                        "platform.tracing.propagation.platform-headers.force-trace-header", "X-Force")));

        assertThat(propagator.fields()).contains("X-Force");
    }
}
