package space.br1440.platform.tracing.otel.javaagent.propagation;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.javaagent.diagnostics.TracingDiagnostics;

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
            throw new IllegalStateException("fields is broken");
        }

        @Override
        public <C> Context extract(Context context, C carrier, TextMapGetter<C> getter) {
            throw new IllegalStateException("extract is broken");
        }

        @Override
        public <C> void inject(Context context, C carrier, TextMapSetter<C> setter) {
            throw new IllegalStateException("inject is broken");
        }
    };

    @Test
    void extract_on_failure_returns_original_context() {
        // given
        TracingDiagnostics diagnostics = new TracingDiagnostics();
        SafeTextMapPropagator safe = new SafeTextMapPropagator(ALWAYS_THROWS, diagnostics);
        Context input = Context.root();

        // when
        Context result = safe.extract(input, null, dummyGetter());

        // then
        // Критично: именно исходный context (а не Context.root()) — W3C/B3 уже извлечён ранее.
        assertThat(result).isSameAs(input);
        // Фактический diagnostics-контракт — только счётчик, без текстового логирования (см. §1.3 плана).
        assertThat(diagnostics.getPropagatorFailures()).isGreaterThanOrEqualTo(1);
        assertThat(diagnostics.getSuppressedErrors()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void inject_on_failure_does_not_throw() {
        // given
        TracingDiagnostics diagnostics = new TracingDiagnostics();
        SafeTextMapPropagator safe = new SafeTextMapPropagator(ALWAYS_THROWS, diagnostics);

        // when/then
        assertThatCode(() -> safe.inject(Context.root(), null, dummySetter()))
                .doesNotThrowAnyException();
        assertThat(diagnostics.getPropagatorFailures()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void fields_on_failure_returns_empty_list() {
        // given
        SafeTextMapPropagator safe = new SafeTextMapPropagator(ALWAYS_THROWS, new TracingDiagnostics());

        // when/then
        assertThat(safe.fields()).isEmpty();
    }

    // given
    private static final TextMapPropagator ALWAYS_THROWS_FATAL = new TextMapPropagator() {
        @Override
        public Collection<String> fields() {
            throw new LinkageError("fields fatal");
        }

        @Override
        public <C> Context extract(Context context, C carrier, TextMapGetter<C> getter) {
            throw new LinkageError("extract fatal");
        }

        @Override
        public <C> void inject(Context context, C carrier, TextMapSetter<C> setter) {
            throw new LinkageError("inject fatal");
        }
    };

    @Test
    void extract_on_fatal_throwable_is_not_swallowed_and_is_rethrown() {
        // given
        SafeTextMapPropagator safe = new SafeTextMapPropagator(ALWAYS_THROWS_FATAL, new TracingDiagnostics());

        // when/then
        org.junit.jupiter.api.Assertions.assertThrows(LinkageError.class,
                () -> safe.extract(Context.root(), null, dummyGetter()));
    }

    @Test
    void inject_on_fatal_throwable_is_not_swallowed_and_is_rethrown() {
        // given
        SafeTextMapPropagator safe = new SafeTextMapPropagator(ALWAYS_THROWS_FATAL, new TracingDiagnostics());

        // when/then
        org.junit.jupiter.api.Assertions.assertThrows(LinkageError.class,
                () -> safe.inject(Context.root(), null, dummySetter()));
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
