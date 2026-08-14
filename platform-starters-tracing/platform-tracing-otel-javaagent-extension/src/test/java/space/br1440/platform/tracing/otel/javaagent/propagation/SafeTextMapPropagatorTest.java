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
        // Фактический diagnostics-контракт — только счётчик, без текстового логирования (см. §1.3 плана).
        assertThat(diagnostics.getPropagatorFailures()).isGreaterThanOrEqualTo(1);
        assertThat(diagnostics.getSuppressedErrors()).isGreaterThanOrEqualTo(1);
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

    // given: delegate бросает fatal LinkageError (VirtualMachineError и подобные обрабатываются
    // так же через PlatformThrowables.propagateIfFatal — здесь фиксируем именно LinkageError,
    // так как это наиболее вероятный fatal-класс в classloader-контексте Agent extension)
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
    void extract_при_fatal_throwable_не_поглощается_и_пробрасывается() {
        // given: safe boundary поверх delegate, бросающего LinkageError
        SafeTextMapPropagator safe = new SafeTextMapPropagator(ALWAYS_THROWS_FATAL, new TracingDiagnostics());

        // when/then: fatal throwable распространяется наружу, а не подавляется
        org.junit.jupiter.api.Assertions.assertThrows(LinkageError.class,
                () -> safe.extract(Context.root(), null, dummyGetter()));
    }

    @Test
    void inject_при_fatal_throwable_не_поглощается_и_пробрасывается() {
        SafeTextMapPropagator safe = new SafeTextMapPropagator(ALWAYS_THROWS_FATAL, new TracingDiagnostics());

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
