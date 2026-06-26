package space.br1440.platform.errorhandling.core.resolver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.server.ResponseStatusException;
import space.br1440.platform.errorhandling.core.strategy.cause.http.AnnotationResponseStatusContributor;
import space.br1440.platform.errorhandling.core.strategy.cause.http.SpringResponseStatusExceptionContributor;
import space.br1440.platform.errorhandling.extension.HttpStatusResolution;
import space.br1440.platform.errorhandling.extension.ResponseStatusContributor;
import space.br1440.platform.errorhandling.testing.ExceptionChainBuilder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ResponseStatusResolverBean")
class ResponseStatusResolverBeanTest {

    @ResponseStatus(HttpStatus.GONE)
    static class GoneException extends RuntimeException {}

    static class WrapperException extends RuntimeException {
        WrapperException(Throwable cause) {
            super("wrapper", cause);
        }
    }

    static class AlwaysTeapotContributor implements ResponseStatusContributor {
        @Override
        @NonNull
        public Optional<HttpStatusResolution> resolve(@NonNull Throwable throwable) {
            if (throwable instanceof IllegalStateException) {
                return Optional.of(new HttpStatusResolution(HttpStatus.I_AM_A_TEAPOT, "teapot"));
            }

            return Optional.empty();
        }

        @Override
        public int getOrder() {
            return -1000;
        }
    }

    private static ResponseStatusResolverBean beanWithBuiltins() {
        return new ResponseStatusResolverBean(List.of(
                new SpringResponseStatusExceptionContributor(),
                new AnnotationResponseStatusContributor()
        ));
    }

    @Test
    @DisplayName("резолвит @ResponseStatus на самом исключении")
    void annotation_directException() {
        // given
        var bean = beanWithBuiltins();

        // when
        Optional<HttpStatusResolution> result = bean.resolve(new GoneException(), 5);

        // then
        assertThat(result).isPresent();
        assertThat(result.get().httpStatus()).isEqualTo(HttpStatus.GONE);
    }

    @Test
    @DisplayName("резолвит @ResponseStatus из обёрнутой причины (обход по глубине)")
    void annotation_wrappedCause() {
        // given
        var bean = beanWithBuiltins();
        var ex = new WrapperException(new GoneException());

        // when
        Optional<HttpStatusResolution> result = bean.resolve(ex, 3);

        // then
        assertThat(result).isPresent();
        assertThat(result.get().httpStatus()).isEqualTo(HttpStatus.GONE);
    }

    @Test
    @DisplayName("не уходит глубже maxDepth — пусто, если совпадение глубже")
    void maxDepth_stopsTraversal() {
        // given
        var bean = beanWithBuiltins();
        var ex = new WrapperException(new WrapperException(new GoneException()));

        // when
        Optional<HttpStatusResolution> result = bean.resolve(ex, 1);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("кастомный contributor с наивысшим приоритетом побеждает аннотационный")
    void customContributor_highestPrecedence_winsOverAnnotation() {
        // given
        var bean = new ResponseStatusResolverBean(List.of(
                new AlwaysTeapotContributor(),
                new AnnotationResponseStatusContributor()
        ));

        // when
        Optional<HttpStatusResolution> result = bean.resolve(new IllegalStateException("test"), 0);

        // then
        assertThat(result).isPresent();
        assertThat(result.get().httpStatus()).isEqualTo(HttpStatus.I_AM_A_TEAPOT);
    }

    @Test
    @DisplayName("резолвит ResponseStatusException (Spring contributor, порядок 50)")
    void springResponseStatusException_resolved() {
        // given
        var bean = beanWithBuiltins();
        var ex = new ResponseStatusException(HttpStatus.LOCKED, "resource is locked");

        // when
        Optional<HttpStatusResolution> result = bean.resolve(ex, 0);

        // then
        assertThat(result).isPresent();
        assertThat(result.get().httpStatus()).isEqualTo(HttpStatus.LOCKED);
        assertThat(result.get().reasonPhrase()).isEqualTo("resource is locked");
    }

    @Test
    @DisplayName("пусто, если ни один contributor не сработал ни на одном узле")
    void noMatch_returnsEmpty() {
        // given
        var bean = beanWithBuiltins();

        // when / then
        assertThat(bean.resolve(new RuntimeException("plain"), 5)).isEmpty();
    }

    @Nested
    @DisplayName("Защита от циклов")
    class CycleGuard {

        @Test
        @DisplayName("самозамыкание A→A — завершается без зависания")
        void selfLoop_terminates() {
            // given
            var bean = beanWithBuiltins();
            var ex = new RuntimeException("self") {
                @Override
                public synchronized Throwable getCause() {
                    return this;
                }
            };

            // when / then
            assertThat(bean.resolve(ex, 10)).isEmpty();
        }

        @Test
        @DisplayName("взаимный цикл A→B→A — завершается без зависания")
        void mutualCycle_terminates() {
            // given
            var bean = beanWithBuiltins();
            // a.getCause() == b → b.getCause() == a: cycle without reflection into java.base.
            var b = new RuntimeException("B");
            var a = ExceptionChainBuilder.withForcedCause("A", b);
            var bCyclic = ExceptionChainBuilder.withForcedCause("B", a);

            // when / then — bCyclic → a → bCyclic → ... (cycle), must terminate
            assertThat(bean.resolve(bCyclic, 20)).isEmpty();
        }
    }

    @Test
    @DisplayName("пустой список contributor'ов — всегда пустой результат")
    void emptyContributors_returnsEmpty() {
        // given
        var bean = new ResponseStatusResolverBean(List.of());

        // when / then
        assertThat(bean.resolve(new GoneException(), 5)).isEmpty();
    }

    @Test
    @DisplayName("maxDepth=0 — смотрит только корневое исключение, не причины")
    void maxDepthZero_inspectsOnlyRoot() {
        // given
        var bean = beanWithBuiltins();
        var ex = new WrapperException(new GoneException());

        // when / then
        assertThat(bean.resolve(ex, 0)).isEmpty();
    }

    @Test
    @DisplayName("отрицательный maxDepth приводится к 0")
    void negativeMaxDepth_treatedAsZero() {
        // given
        var bean = beanWithBuiltins();
        var ex = new WrapperException(new GoneException());

        // when / then
        assertThat(bean.resolve(ex, -5)).isEmpty();
    }

}
