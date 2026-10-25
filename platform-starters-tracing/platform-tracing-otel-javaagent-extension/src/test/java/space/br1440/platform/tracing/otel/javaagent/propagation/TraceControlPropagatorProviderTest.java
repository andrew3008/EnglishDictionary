package space.br1440.platform.tracing.otel.javaagent.propagation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.propagation.PlatformHeaders;
import space.br1440.platform.tracing.api.propagation.control.InboundTraceControl;
import space.br1440.platform.tracing.otel.javaagent.configuration.PlatformTracingDefaultsProvider;
import space.br1440.platform.tracing.runtime.propagation.control.PlatformTraceContextKeys;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;

@DisplayName("TraceControlPropagatorProvider (named platform-trace-control)")
class TraceControlPropagatorProviderTest {

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
        // given/when/then
        assertThat(new TraceControlPropagatorProvider().getName())
                .isEqualTo("platform-trace-control");
    }

    @Test
    @DisplayName("getPropagator извлекает X-Trace-On из carrier в Context")
    void getPropagator_extracts_X_Trace_On() {
        // given
        TextMapPropagator propagator =
                new TraceControlPropagatorProvider().getPropagator(config(Map.of()));

        assertThat(propagator.fields()).contains("X-Trace-On", "X-QA-Trace", "X-Request-Id");

        Map<String, String> carrier = new HashMap<>();
        carrier.put("X-Trace-On", "on");
        carrier.put("X-Request-Id", "req-1");

        // when
        Context ctx = propagator.extract(Context.root(), carrier, MAP_GETTER);

        // then
        InboundTraceControl control = ctx.get(PlatformTraceContextKeys.TRACE_CONTROL);
        assertThat(control).isNotNull();
        assertThat(control.forceTrace()).isTrue();
        assertThat(control.requestId()).isNull();
    }

    @Test
    @DisplayName("getPropagator игнорирует свойства имён заголовков")
    void getPropagatorIgnoresHeaderNameProperties() {
        // given
        TextMapPropagator propagator = new TraceControlPropagatorProvider()
                .getPropagator(config(Map.of(
                        "platform.tracing.propagation.platform-headers.force-trace-header", "X-Force")));

        // when
        // then
        assertThat(propagator.fields()).containsExactlyInAnyOrder(
                PlatformHeaders.X_TRACE_ON,
                PlatformHeaders.X_QA_TRACE,
                PlatformHeaders.X_REQUEST_ID);
    }

    @Test
    @DisplayName("defaults supplier не задаёт имена платформенных заголовков")
    void defaultsProviderDoesNotSupplyPlatformHeaderNames() {
        // given
        Map<String, String> supplied = new PlatformTracingDefaultsProvider().supply();

        // when
        // then
        assertThat(supplied).doesNotContainKeys(
                "platform.tracing.propagation.platform-headers.force-trace-header",
                "platform.tracing.propagation.platform-headers.qa-trace-header",
                "platform.tracing.propagation.platform-headers.request-id-header");
    }

    @Test
    @DisplayName("возвращаемый propagator находится за SafeTextMapPropagator boundary")
    void getPropagator_isBehindSafeTextMapPropagatorBoundary() {
        // given
        TextMapPropagator propagator = new TraceControlPropagatorProvider().getPropagator(config(Map.of()));

        // when/then
        assertThat(propagator).isInstanceOf(SafeTextMapPropagator.class);
    }

    @Test
    @DisplayName("fields() содержит только настроенные control headers (default header names)")
    void fields_containsExactlyConfiguredControlHeaders() {
        // given
        TextMapPropagator propagator = new TraceControlPropagatorProvider().getPropagator(config(Map.of()));

        // when/then
        assertThat(propagator.fields()).containsExactlyInAnyOrder("X-Trace-On", "X-QA-Trace", "X-Request-Id");
    }
}
