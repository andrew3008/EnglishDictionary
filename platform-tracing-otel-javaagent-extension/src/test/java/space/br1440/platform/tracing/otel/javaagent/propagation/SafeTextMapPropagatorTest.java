package space.br1440.platform.tracing.otel.javaagent.propagation;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.javaagent.safety.TracingDiagnostics;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Failure-path тесты {@link SafeTextMapPropagator}: сбой extract возвращает ИСХОДНЫЙ context
 * (W3C/B3 не теряется), сбой inject подавляется, всё фиксируется в диагностике.
 */
class SafeTextMapPropagatorTest {

    private static final TextMapPropagator ALWAYS_THROWS = new TextMapPropagator() {
        @Override
        public Collection<String> fields() {
            throw new IllegalStateException("fields сломан");
        }

        @Override
        public <C> Context extract(Context context, C carrier, TextMapGetter<C> getter) {
            throw new IllegalStateException("extract сломан");
        }

        @Override
        public <C> void inject(Context context, C carrier, TextMapSetter<C> setter) {
            throw new IllegalStateException("inject сломан");
        }
    };

    @Test
    void extract_при_сбое_возвращает_исходный_context() {
        TracingDiagnostics diagnostics = new TracingDiagnostics();
        SafeTextMapPropagator safe = new SafeTextMapPropagator(ALWAYS_THROWS, diagnostics);
        Context input = Context.root();

        Context result = safe.extract(input, null, dummyGetter());

        // Критично: именно исходный context (а не Context.root()) — W3C/B3 уже извлечён ранее.
        assertThat(result).isSameAs(input);
        assertThat(diagnostics.getPropagatorFailures()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void inject_при_сбое_не_бросает() {
        TracingDiagnostics diagnostics = new TracingDiagnostics();
        SafeTextMapPropagator safe = new SafeTextMapPropagator(ALWAYS_THROWS, diagnostics);

        assertThatCode(() -> safe.inject(Context.root(), null, dummySetter()))
                .doesNotThrowAnyException();
        assertThat(diagnostics.getPropagatorFailures()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void fields_при_сбое_возвращает_пустой_список() {
        SafeTextMapPropagator safe = new SafeTextMapPropagator(ALWAYS_THROWS, new TracingDiagnostics());
        assertThat(safe.fields()).isEmpty();
    }

    private static TextMapGetter<Object> dummyGetter() {
        return new TextMapGetter<>() {
            @Override
            public Iterable<String> keys(Object carrier) {
                return List.of();
            }

            @Override
            public String get(Object carrier, String key) {
                return null;
            }
        };
    }

    private static TextMapSetter<Object> dummySetter() {
        return (carrier, key, value) -> {
        };
    }
}
