package space.br1440.platform.errorhandling.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.core.resolver.CauseChainResolver;
import space.br1440.platform.errorhandling.core.util.CauseChainNavigator;
import space.br1440.platform.errorhandling.extension.ResolvedRootCause;
import space.br1440.platform.errorhandling.extension.RootCauseExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.testing.ExceptionChainBuilder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Интеграционные тесты для {@link CauseChainResolver}.
 *
 * <p>Проверяют поведение резолвера на сложных цепочках исключений
 * (глубокие, циклические, множественные совпадения) без поднятия Spring-контекста.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CauseChainResolver — интеграционные сценарии")
class CauseChainResolutionIntegrationTest {

    @Mock
    private RootCauseExceptionHandlingStrategy strategy;

    @Mock
    private RootCauseExceptionHandlingStrategy highPriorityStrategy;

    @Mock
    private RootCauseExceptionHandlingStrategy lowPriorityStrategy;

    // ――――――――――――――――――――――――――――――――――――――――――――――――――――――――――
    //  1. Глубокая цепочка (6+ уровней)
    // ――――――――――――――――――――――――――――――――――――――――――――――――――――――――――

    @Nested
    @DisplayName("Глубокая цепочка причин")
    class DeepChain {

        @Test
        @DisplayName("resolve находит совпадение на самом глубоком уровне (6+ звеньев)")
        void resolveFindsMatchAtDeepestLevel() {
            // given
            when(strategy.supportsCause(any())).thenAnswer(inv -> {
                Throwable t = inv.getArgument(0);
                return t instanceof IllegalArgumentException;
            });
            RootCauseResolution expected = RootCauseResolution.of(
                    HttpStatus.BAD_REQUEST, "invalid argument", List.of());
            when(strategy.extractCauseDetails(any())).thenReturn(expected);

            Throwable chain = ExceptionChainBuilder
                    .root(new IllegalArgumentException("deep root"))
                    .wrappedBy(RuntimeException.class, "level-5")
                    .wrappedBy(RuntimeException.class, "level-4")
                    .wrappedBy(RuntimeException.class, "level-3")
                    .wrappedBy(RuntimeException.class, "level-2")
                    .wrappedBy(RuntimeException.class, "level-1")
                    .wrappedBy(RuntimeException.class, "top")
                    .build();

            // when
            CauseChainResolver resolver = new CauseChainResolver(List.of(strategy));
            ResolvedRootCause resolved = resolver.resolve(chain);

            // then
            assertThat(resolved.cause()).isInstanceOf(IllegalArgumentException.class);
            assertThat(resolved.cause().getMessage()).isEqualTo("deep root");
            assertThat(resolved.resolution()).isSameAs(expected);
        }

        @Test
        @DisplayName("resolveAll собирает все совпадения из глубокой цепочки")
        void resolveAllCollectsAllMatchesFromDeepChain() {
            // given
            when(strategy.supportsCause(any())).thenAnswer(inv -> {
                Throwable t = inv.getArgument(0);
                return t instanceof IllegalStateException
                        || t instanceof IllegalArgumentException;
            });
            when(strategy.extractCauseDetails(any())).thenAnswer(inv -> {
                Throwable t = inv.getArgument(0);
                return RootCauseResolution.of(
                        HttpStatus.BAD_REQUEST, t.getMessage(), List.of());
            });

            Throwable chain = ExceptionChainBuilder
                    .root(new IllegalArgumentException("root-arg"))
                    .wrappedBy(RuntimeException.class, "skip-1")
                    .wrappedBy(RuntimeException.class, "skip-2")
                    .wrappedBy(IllegalStateException.class, "mid-state")
                    .wrappedBy(RuntimeException.class, "skip-3")
                    .wrappedBy(RuntimeException.class, "skip-4")
                    .wrappedBy(RuntimeException.class, "top")
                    .build();

            // when
            CauseChainResolver resolver = new CauseChainResolver(List.of(strategy));
            List<ResolvedRootCause> results = resolver.resolveAll(chain);

            // then
            assertThat(results).hasSize(2);
            assertThat(results.get(0).cause()).isInstanceOf(IllegalStateException.class);
            assertThat(results.get(0).cause().getMessage()).isEqualTo("mid-state");
            assertThat(results.get(1).cause()).isInstanceOf(IllegalArgumentException.class);
            assertThat(results.get(1).cause().getMessage()).isEqualTo("root-arg");
        }
    }

    // ――――――――――――――――――――――――――――――――――――――――――――――――――――――――――
    //  2. Циклическая цепочка
    // ――――――――――――――――――――――――――――――――――――――――――――――――――――――――――

    @Nested
    @DisplayName("Циклическая цепочка причин")
    class CyclicChain {

        @Test
        @DisplayName("resolve корректно завершается при зацикленной цепочке")
        void resolveTerminatesGracefullyOnCyclicCauseChain() {
            // given
            lenient().when(strategy.getOrder()).thenReturn(0);
            when(strategy.supportsCause(any())).thenReturn(false);

            // a.getCause() == b → b.getCause() == a: cycle without reflection into java.base.
            Exception b = new RuntimeException("B");
            Exception a = ExceptionChainBuilder.withForcedCause("A", b);
            Exception bCyclic = ExceptionChainBuilder.withForcedCause("B", a);

            CauseChainResolver resolver = new CauseChainResolver(List.of(strategy));

            // when / then — bCyclic → a → bCyclic → ... (cycle), must terminate
            assertThatCode(() -> resolver.resolve(bCyclic)).doesNotThrowAnyException();

            ResolvedRootCause resolved = resolver.resolve(bCyclic);
            assertThat(resolved.resolution().httpStatus())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        @DisplayName("resolveAll корректно завершается при зацикленной цепочке")
        void resolveAllTerminatesGracefullyOnCyclicCauseChain() {
            // given
            lenient().when(strategy.getOrder()).thenReturn(0);
            when(strategy.supportsCause(any())).thenReturn(false);

            // x.getCause() == y → y.getCause() == x: cycle without reflection into java.base.
            Exception y = new RuntimeException("Y");
            Exception x = ExceptionChainBuilder.withForcedCause("X", y);
            Exception yCyclic = ExceptionChainBuilder.withForcedCause("Y", x);

            CauseChainResolver resolver = new CauseChainResolver(List.of(strategy));

            // when / then — yCyclic → x → yCyclic → ... (cycle), must terminate
            assertThatCode(() -> resolver.resolveAll(yCyclic)).doesNotThrowAnyException();

            List<ResolvedRootCause> results = resolver.resolveAll(yCyclic);
            assertThat(results).hasSize(1);
            assertThat(results.getFirst().resolution().httpStatus())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        @DisplayName("resolve не обходит больше MAX_CAUSE_DEPTH звеньев")
        void resolveRespectsMaxCauseDepthLimit() {
            // given
            lenient().when(strategy.getOrder()).thenReturn(0);
            when(strategy.supportsCause(any())).thenReturn(false);

            int overLimit = CauseChainNavigator.DEFAULT_MAX_DEPTH + 5;
            Throwable head = new RuntimeException("root");
            for (int i = 0; i < overLimit; i++) {
                head = new RuntimeException("level-" + i, head);
            }
            Throwable top = head;

            CauseChainResolver resolver = new CauseChainResolver(List.of(strategy));

            // when / then
            assertThatCode(() -> resolver.resolve(top)).doesNotThrowAnyException();
        }
    }

    // ――――――――――――――――――――――――――――――――――――――――――――――――――――――――――
    //  3. resolveAll vs resolve: семантика сбора результатов
    // ――――――――――――――――――――――――――――――――――――――――――――――――――――――――――

    @Nested
    @DisplayName("resolveAll vs resolve — различие семантики")
    class ResolveAllVsResolve {

        @Test
        @DisplayName("resolve останавливается на ПЕРВОМ совпадении")
        void resolveStopsAtFirstMatch() {
            // given
            when(strategy.supportsCause(any())).thenAnswer(inv -> {
                Throwable t = inv.getArgument(0);
                return t instanceof IllegalStateException
                        || t instanceof IllegalArgumentException;
            });
            RootCauseResolution firstResolution = RootCauseResolution.of(
                    HttpStatus.CONFLICT, "first match", List.of());
            when(strategy.extractCauseDetails(any())).thenReturn(firstResolution);

            Throwable chain = ExceptionChainBuilder
                    .root(new IllegalArgumentException("leaf"))
                    .wrappedBy(IllegalStateException.class, "middle")
                    .wrappedBy(RuntimeException.class, "top")
                    .build();

            // when
            CauseChainResolver resolver = new CauseChainResolver(List.of(strategy));
            ResolvedRootCause resolved = resolver.resolve(chain);

            // then
            assertThat(resolved.cause()).isInstanceOf(IllegalStateException.class);
            assertThat(resolved.resolution()).isSameAs(firstResolution);
        }

        @Test
        @DisplayName("resolveAll собирает ВСЕ совпадающие звенья")
        void resolveAllCollectsAllMatchingLinks() {
            // given
            when(strategy.supportsCause(any())).thenAnswer(inv -> {
                Throwable t = inv.getArgument(0);
                return t instanceof IllegalStateException
                        || t instanceof IllegalArgumentException;
            });
            when(strategy.extractCauseDetails(any())).thenAnswer(inv -> {
                Throwable t = inv.getArgument(0);
                return RootCauseResolution.of(
                        HttpStatus.BAD_REQUEST, t.getClass().getSimpleName(), List.of()
                );
            });

            Throwable chain = ExceptionChainBuilder
                    .root(new IllegalArgumentException("leaf"))
                    .wrappedBy(IllegalStateException.class, "middle")
                    .wrappedBy(RuntimeException.class, "top")
                    .build();

            // when
            CauseChainResolver resolver = new CauseChainResolver(List.of(strategy));
            List<ResolvedRootCause> results = resolver.resolveAll(chain);

            // then
            assertThat(results).hasSize(2);
            assertThat(results.get(0).cause()).isInstanceOf(IllegalStateException.class);
            assertThat(results.get(1).cause()).isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ――――――――――――――――――――――――――――――――――――――――――――――――――――――――――
    //  4. Fallback при отсутствии совпадений
    // ――――――――――――――――――――――――――――――――――――――――――――――――――――――――――

    @Nested
    @DisplayName("Fallback — ни одна стратегия не подошла")
    class FallbackBehavior {

        @Test
        @DisplayName("resolve возвращает fallback с INTERNAL_SERVER_ERROR")
        void resolveFallbackReturnsInternalServerError() {
            // given
            lenient().when(strategy.getOrder()).thenReturn(0);
            when(strategy.supportsCause(any())).thenReturn(false);

            RuntimeException ex = new RuntimeException("unrecognized cause");
            CauseChainResolver resolver = new CauseChainResolver(List.of(strategy));

            // when
            ResolvedRootCause resolved = resolver.resolve(ex);

            // then
            assertThat(resolved.cause()).isSameAs(ex);
            assertThat(resolved.resolution().httpStatus())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(resolved.resolution().message())
                    .isEqualTo("An internal error occurred");
        }

        @Test
        @DisplayName("resolveAll возвращает единственный fallback при отсутствии совпадений")
        void resolveAllFallbackReturnsSingleElement() {
            // given
            lenient().when(strategy.getOrder()).thenReturn(0);
            when(strategy.supportsCause(any())).thenReturn(false);

            Throwable chain = ExceptionChainBuilder
                    .root(new RuntimeException("deep"))
                    .wrappedBy(RuntimeException.class, "mid")
                    .wrappedBy(RuntimeException.class, "top")
                    .build();

            // when
            CauseChainResolver resolver = new CauseChainResolver(List.of(strategy));
            List<ResolvedRootCause> results = resolver.resolveAll(chain);

            // then
            assertThat(results).hasSize(1);
            assertThat(results.getFirst().cause().getMessage()).isEqualTo("top");
            assertThat(results.getFirst().resolution().httpStatus())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        @DisplayName("fallback содержит diagnosticMetadata с rootCauseClass")
        void fallbackContainsDiagnosticMetadata() {
            // given
            lenient().when(strategy.getOrder()).thenReturn(0);
            when(strategy.supportsCause(any())).thenReturn(false);

            IllegalStateException ex = new IllegalStateException("state error");
            CauseChainResolver resolver = new CauseChainResolver(List.of(strategy));

            // when
            ResolvedRootCause resolved = resolver.resolve(ex);

            // then
            assertThat(resolved.resolution().details()).hasSize(1);
        }
    }

    // ――――――――――――――――――――――――――――――――――――――――――――――――――――――――――
    //  5. Приоритет стратегий (ordering)
    // ――――――――――――――――――――――――――――――――――――――――――――――――――――――――――

    @Nested
    @DisplayName("Приоритет стратегий — меньший order побеждает")
    class StrategyOrdering {

        @Test
        @DisplayName("стратегия с меньшим getOrder() применяется первой")
        void lowerOrderStrategyWins() {
            // given
            when(highPriorityStrategy.getOrder()).thenReturn(10);
            when(lowPriorityStrategy.getOrder()).thenReturn(100);

            when(highPriorityStrategy.supportsCause(any())).thenReturn(true);
            lenient().when(lowPriorityStrategy.supportsCause(any())).thenReturn(true);

            RootCauseResolution highRes = RootCauseResolution.of(
                    HttpStatus.BAD_REQUEST, "from high priority", List.of());
            RootCauseResolution lowRes = RootCauseResolution.of(
                    HttpStatus.CONFLICT, "from low priority", List.of());

            when(highPriorityStrategy.extractCauseDetails(any())).thenReturn(highRes);
            lenient().when(lowPriorityStrategy.extractCauseDetails(any())).thenReturn(lowRes);

            CauseChainResolver resolver = new CauseChainResolver(
                    List.of(lowPriorityStrategy, highPriorityStrategy)
            );
            RuntimeException ex = new RuntimeException("test");

            // when
            ResolvedRootCause resolved = resolver.resolve(ex);

            // then
            assertThat(resolved.resolution()).isSameAs(highRes);
            assertThat(resolved.resolution().message()).isEqualTo("from high priority");
        }

        @Test
        @DisplayName("resolveAll тоже применяет стратегии в порядке order")
        void resolveAllRespectsStrategyOrdering() {
            // given
            when(highPriorityStrategy.getOrder()).thenReturn(5);
            when(lowPriorityStrategy.getOrder()).thenReturn(50);

            when(highPriorityStrategy.supportsCause(any())).thenReturn(true);
            lenient().when(lowPriorityStrategy.supportsCause(any())).thenReturn(true);

            RootCauseResolution highRes = RootCauseResolution.of(HttpStatus.BAD_REQUEST, "high", List.of());
            when(highPriorityStrategy.extractCauseDetails(any())).thenReturn(highRes);

            RootCauseResolution lowRes = RootCauseResolution.of(
                    HttpStatus.CONFLICT, "low", List.of()
            );
            lenient().when(lowPriorityStrategy.extractCauseDetails(any())).thenReturn(lowRes);

            Throwable chain = ExceptionChainBuilder
                    .root(new IllegalArgumentException("leaf"))
                    .wrappedBy(RuntimeException.class, "top")
                    .build();

            // when
            CauseChainResolver resolver = new CauseChainResolver(
                    List.of(lowPriorityStrategy, highPriorityStrategy)
            );
            List<ResolvedRootCause> results = resolver.resolveAll(chain);

            // then
            assertThat(results).isNotEmpty()
                    .allSatisfy(r ->
                            assertThat(r.resolution().message()).isEqualTo("high"));
        }
    }

}
