package space.br1440.platform.tracing.otel.extension.propagation;

import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BaggagePropagatorTypeDetector")
class BaggagePropagatorTypeDetectorTest {

    @Test
    @DisplayName("W3CBaggagePropagator → true")
    void w3cBaggagePropagator_returnsTrue() {
        assertThat(BaggagePropagatorTypeDetector.isW3cBaggagePropagator(W3CBaggagePropagator.getInstance()))
                .isTrue();
    }

    @Test
    @DisplayName("composite(W3CBaggagePropagator, W3CTraceContextPropagator) → false (составной propagator не является W3C baggage)")
    void composite_returnsFalse() {
        TextMapPropagator composite = TextMapPropagator.composite(
                W3CBaggagePropagator.getInstance(),
                W3CTraceContextPropagator.getInstance()
        );
        assertThat(BaggagePropagatorTypeDetector.isW3cBaggagePropagator(composite))
                .isFalse();
    }

    @Test
    @DisplayName("W3CTraceContextPropagator → false")
    void traceContextPropagator_returnsFalse() {
        assertThat(BaggagePropagatorTypeDetector.isW3cBaggagePropagator(W3CTraceContextPropagator.getInstance()))
                .isFalse();
    }

    @Test
    @DisplayName("произвольный TextMapPropagator → false")
    void customPropagator_returnsFalse() {
        TextMapPropagator dummy = new TextMapPropagator() {
            @Override
            public Collection<String> fields() {
                return List.of();
            }

            @Override
            public <C> void inject(io.opentelemetry.context.Context context, C carrier, TextMapSetter<C> setter) {
            }

            @Override
            public <C> io.opentelemetry.context.Context extract(io.opentelemetry.context.Context context, C carrier, TextMapGetter<C> getter) {
                return context;
            }
        };
        assertThat(BaggagePropagatorTypeDetector.isW3cBaggagePropagator(dummy))
                .isFalse();
    }
}
