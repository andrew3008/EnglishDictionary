package space.br1440.platform.errorhandling.core.resolver;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.extension.ResolvedRootCause;
import space.br1440.platform.errorhandling.extension.RootCauseExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.testing.ExceptionChainBuilder;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CauseChainResolver")
class CauseChainResolverTest {

    @Mock
    private RootCauseExceptionHandlingStrategy highPriority;

    @Mock
    private RootCauseExceptionHandlingStrategy lowPriority;

    @Nested
    @DisplayName("resolve")
    class Resolve {

        @Test
        void sortsStrategiesByOrderAscending() {
            // given
            when(highPriority.getOrder()).thenReturn(0);
            when(lowPriority.getOrder()).thenReturn(100);

            when(highPriority.supportsCause(any())).thenReturn(false);
            when(lowPriority.supportsCause(any())).thenReturn(true);
            RootCauseResolution details = RootCauseResolution.of(HttpStatus.BAD_REQUEST, "x", List.of());
            when(lowPriority.extractCauseDetails(any())).thenReturn(details);

            CauseChainResolver resolver = new CauseChainResolver(List.of(lowPriority, highPriority));
            IllegalStateException ex = new IllegalStateException("e");

            // when
            ResolvedRootCause resolved = resolver.resolve(ex);

            // then
            assertThat(resolved.cause()).isSameAs(ex);
            assertThat(resolved.resolution()).isSameAs(details);
            verify(lowPriority).extractCauseDetails(ex);
            verify(highPriority, never()).extractCauseDetails(any());
        }

        @Test
        void firstMatchingStrategyWinsWhenBothSupportSameThrowable() {
            // given
            when(highPriority.getOrder()).thenReturn(0);
            when(lowPriority.getOrder()).thenReturn(50);

            when(highPriority.supportsCause(any())).thenReturn(true);
            RootCauseResolution fromHigh = RootCauseResolution.of(HttpStatus.BAD_REQUEST, "a", List.of());
            when(highPriority.extractCauseDetails(any())).thenReturn(fromHigh);

            CauseChainResolver resolver = new CauseChainResolver(List.of(lowPriority, highPriority));
            IllegalStateException ex = new IllegalStateException("e");

            // when
            ResolvedRootCause resolved = resolver.resolve(ex);

            // then
            assertThat(resolved.resolution()).isSameAs(fromHigh);
            verify(lowPriority, never()).extractCauseDetails(any());
        }

        @Test
        void walksCauseChainUntilSupportedThrowable() {
            // given
            when(highPriority.supportsCause(any())).thenAnswer(inv -> {
                Throwable t = inv.getArgument(0);
                return t instanceof IllegalStateException;
            });
            RootCauseResolution details = RootCauseResolution.of(HttpStatus.BAD_REQUEST, "inner", List.of());
            when(highPriority.extractCauseDetails(any())).thenReturn(details);

            CauseChainResolver resolver = new CauseChainResolver(List.of(highPriority));
            IllegalStateException inner = new IllegalStateException("inner");
            RuntimeException top = new RuntimeException("top", inner);

            // when
            ResolvedRootCause resolved = resolver.resolve(top);

            // then
            assertThat(resolved.cause()).isSameAs(inner);
            verify(highPriority).extractCauseDetails(inner);
        }

        @Test
        void fallbackWhenNoStrategyMatches() {
            // given
            when(highPriority.supportsCause(any())).thenReturn(false);

            CauseChainResolver resolver = new CauseChainResolver(List.of(highPriority));
            RuntimeException ex = new RuntimeException("orphan");

            // when
            ResolvedRootCause resolved = resolver.resolve(ex);

            // then
            assertThat(resolved.cause()).isSameAs(ex);
            assertThat(resolved.resolution().httpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        void fallbackContainsDiagnosticMetadata() {
            // given
            when(highPriority.supportsCause(any())).thenReturn(false);

            CauseChainResolver resolver = new CauseChainResolver(List.of(highPriority));
            RuntimeException ex = new RuntimeException("detailed orphan");

            // when
            ResolvedRootCause resolved = resolver.resolve(ex);

            // then
            assertThat(resolved.resolution().details()).hasSize(1);
            ErrorInfoDetail info = (ErrorInfoDetail) resolved.resolution().details().getFirst();
            assertThat(info.getReason()).isEqualTo(RuntimeException.class.getName());
            assertThat(info.getDiagnosticMetadata())
                    .containsEntry("rootCauseClass", RuntimeException.class.getName())
                    .containsEntry("rootCauseMessage", "detailed orphan");
        }

        @Test
        void emptyStrategiesList_returnsFallback() {
            // given
            CauseChainResolver resolver = new CauseChainResolver(List.of());
            RuntimeException ex = new RuntimeException("no strategies");

            // when
            ResolvedRootCause resolved = resolver.resolve(ex);

            // then
            assertThat(resolved.cause()).isSameAs(ex);
            assertThat(resolved.resolution().httpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        void fallbackOmitsBlankMessage() {
            // given
            CauseChainResolver resolver = new CauseChainResolver(List.of());
            RuntimeException ex = new RuntimeException("   ");

            // when
            ResolvedRootCause resolved = resolver.resolve(ex);
            ErrorInfoDetail info = (ErrorInfoDetail) resolved.resolution().details().getFirst();

            // then
            assertThat(info.getDiagnosticMetadata()).doesNotContainKey("rootCauseMessage");
        }

        @Test
        void fallbackOmitsNullMessage() {
            // given
            CauseChainResolver resolver = new CauseChainResolver(List.of());
            RuntimeException ex = new RuntimeException((String) null);

            // when
            ResolvedRootCause resolved = resolver.resolve(ex);
            ErrorInfoDetail info = (ErrorInfoDetail) resolved.resolution().details().getFirst();

            // then
            assertThat(info.getDiagnosticMetadata()).doesNotContainKey("rootCauseMessage");
        }
    }

    @Nested
    @DisplayName("resolveAll")
    class ResolveAll {

        @Test
        void collectsEachRecognizedLinkInChainOrder() {
            // given
            when(highPriority.supportsCause(any())).thenAnswer(inv -> {
                Throwable t = inv.getArgument(0);
                return t instanceof IllegalStateException || t instanceof IllegalArgumentException;
            });
            when(highPriority.extractCauseDetails(any())).thenAnswer(inv -> {
                Throwable t = inv.getArgument(0);
                return RootCauseResolution.of(HttpStatus.BAD_REQUEST, t.getClass().getName(), List.of());
            });

            CauseChainResolver resolver = new CauseChainResolver(List.of(highPriority));
            IllegalArgumentException leaf = new IllegalArgumentException("leaf");
            IllegalStateException mid = new IllegalStateException("mid", leaf);
            RuntimeException top = new RuntimeException("top", mid);

            // when
            List<ResolvedRootCause> chain = resolver.resolveAll(top);

            // then
            assertThat(chain).hasSize(2);
            assertThat(chain.get(0).cause()).isSameAs(mid);
            assertThat(chain.get(1).cause()).isSameAs(leaf);
        }

        @Test
        void fallbackWhenChainNeverRecognized() {
            // given
            when(highPriority.supportsCause(any())).thenReturn(false);

            CauseChainResolver resolver = new CauseChainResolver(List.of(highPriority));
            RuntimeException ex = new RuntimeException("x");

            // when
            List<ResolvedRootCause> chain = resolver.resolveAll(ex);

            // then
            assertThat(chain).hasSize(1);
            assertThat(chain.getFirst().cause()).isSameAs(ex);
        }

        @Test
        void skipsUnrecognizedIntermediateLinks() {
            // given
            when(highPriority.supportsCause(any())).thenAnswer(inv -> {
                Throwable t = inv.getArgument(0);
                return t instanceof IllegalArgumentException;
            });
            when(highPriority.extractCauseDetails(any()))
                    .thenReturn(RootCauseResolution.of(HttpStatus.BAD_REQUEST, "found", List.of()));

            CauseChainResolver resolver = new CauseChainResolver(List.of(highPriority));
            IllegalArgumentException leaf = new IllegalArgumentException("leaf");
            RuntimeException mid = new RuntimeException("unrecognized-mid", leaf);
            RuntimeException top = new RuntimeException("unrecognized-top", mid);

            // when
            List<ResolvedRootCause> chain = resolver.resolveAll(top);

            // then
            assertThat(chain).hasSize(1);
            assertThat(chain.getFirst().cause()).isSameAs(leaf);
        }

        @Test
        void returnedListIsUnmodifiable() {
            // given
            CauseChainResolver resolver = new CauseChainResolver(List.of());
            RuntimeException ex = new RuntimeException("x");

            // when
            List<ResolvedRootCause> chain = resolver.resolveAll(ex);

            // then
            assertThat(chain).isUnmodifiable();
        }
    }

    @Nested
    @DisplayName("Type-indexed cache")
    class TypeIndexedCache {

        @Test
        @DisplayName("cache hit: supportsCause вызывается только один раз для повторного класса")
        void cacheHit_supportsCauseCalledOnceForSameType() {
            // given
            when(highPriority.supportsCause(any())).thenReturn(true);
            RootCauseResolution resolution = RootCauseResolution.of(HttpStatus.BAD_REQUEST, "cached", List.of());
            when(highPriority.extractCauseDetails(any())).thenReturn(resolution);

            CauseChainResolver resolver = new CauseChainResolver(List.of(highPriority));
            RuntimeException ex1 = new RuntimeException("first");
            RuntimeException ex2 = new RuntimeException("second");

            // when
            resolver.resolve(ex1);
            resolver.resolve(ex2);

            // then: supportsCause вызван ровно один раз — второй resolve читает из кэша
            verify(highPriority, times(1)).supportsCause(any());
            verify(highPriority, times(2)).extractCauseDetails(any());
        }

        @Test
        @DisplayName("cache miss → cache fill: после первого матча следующий вызов идёт через кэш")
        void cacheMissThenFill_subsequentCallsBypassesLinearScan() {
            // given
            when(highPriority.getOrder()).thenReturn(0);
            when(lowPriority.getOrder()).thenReturn(100);
            when(highPriority.supportsCause(any())).thenReturn(false);
            when(lowPriority.supportsCause(any())).thenReturn(true);
            RootCauseResolution resolution = RootCauseResolution.of(HttpStatus.BAD_REQUEST, "found", List.of());
            when(lowPriority.extractCauseDetails(any())).thenReturn(resolution);

            CauseChainResolver resolver = new CauseChainResolver(List.of(highPriority, lowPriority));

            // when
            resolver.resolve(new IllegalStateException("a"));  // заполняет кэш
            resolver.resolve(new IllegalStateException("b"));  // должен читать из кэша
            resolver.resolve(new IllegalStateException("c"));  // и снова из кэша

            // then
            verify(highPriority, times(1)).supportsCause(any());
            verify(lowPriority, times(1)).supportsCause(any());
            verify(lowPriority, times(3)).extractCauseDetails(any());
        }

        @Test
        @DisplayName("разные классы исключений кэшируются независимо")
        void differentExceptionTypes_cachedIndependently() {
            // given
            when(highPriority.supportsCause(any())).thenAnswer(inv ->
                    inv.getArgument(0) instanceof IllegalStateException
                            || inv.getArgument(0) instanceof IllegalArgumentException
            );
            RootCauseResolution resolution = RootCauseResolution.of(HttpStatus.BAD_REQUEST, "ok", List.of());
            when(highPriority.extractCauseDetails(any())).thenReturn(resolution);

            CauseChainResolver resolver = new CauseChainResolver(List.of(highPriority));

            // when — первый вызов для каждого типа идёт через линейный поиск, остальные — из кэша
            resolver.resolve(new IllegalStateException("ise-1"));
            resolver.resolve(new IllegalArgumentException("iae-1"));
            resolver.resolve(new IllegalStateException("ise-2")); // кэш hit
            resolver.resolve(new IllegalArgumentException("iae-2")); // кэш hit

            // then: supportsCause вызван ровно 2 раза (по одному на каждый новый тип)
            verify(highPriority, times(2)).supportsCause(any());
        }

        @Test
        @DisplayName("кэш не применяется при отсутствии матча (null не кэшируется)")
        void noMatch_notCached_linearScanRepeats() {
            // given
            when(highPriority.supportsCause(any())).thenReturn(false);

            CauseChainResolver resolver = new CauseChainResolver(List.of(highPriority));

            // when
            resolver.resolve(new RuntimeException("a"));
            resolver.resolve(new RuntimeException("b"));
            resolver.resolve(new RuntimeException("c"));

            // then: каждый вызов полностью проходит линейный поиск, кэша нет
            verify(highPriority, times(3)).supportsCause(any());
        }

        @Test
        @DisplayName("кэш работает корректно в resolveAll при повторных типах в цепочке")
        void resolveAll_cacheHit_innerLoopSkipped() {
            // given
            when(highPriority.supportsCause(any())).thenAnswer(inv ->
                    inv.getArgument(0) instanceof IllegalArgumentException
            );
            RootCauseResolution resolution = RootCauseResolution.of(HttpStatus.BAD_REQUEST, "leaf", List.of());
            when(highPriority.extractCauseDetails(any())).thenReturn(resolution);

            CauseChainResolver resolver = new CauseChainResolver(List.of(highPriority));

            IllegalArgumentException leaf1 = new IllegalArgumentException("leaf1");
            RuntimeException chain1 = new RuntimeException("wrapper", leaf1);

            IllegalArgumentException leaf2 = new IllegalArgumentException("leaf2");
            RuntimeException chain2 = new RuntimeException("wrapper2", leaf2);

            // when
            resolver.resolveAll(chain1); // заполняет кэш для IllegalArgumentException
            resolver.resolveAll(chain2); // читает из кэша

            // then: supportsCause для IllegalArgumentException вызван ровно один раз
            verify(highPriority, times(1)).supportsCause(any(IllegalArgumentException.class));
        }

        @Test
        @DisplayName("при достижении ёмкости кэша новые типы проходят линейный поиск без NPE")
        void cacheAtCapacity_newTypesStillResolveViaLinearScan() {
            // given
            AtomicInteger supportsCauseCallCount = new AtomicInteger(0);
            RootCauseResolution resolution = RootCauseResolution.of(HttpStatus.BAD_REQUEST, "ok", List.of());

            RootCauseExceptionHandlingStrategy alwaysTrue = new RootCauseExceptionHandlingStrategy() {
                @Override
                public int getOrder() {
                    return 0;
                }

                @Override
                public @NotNull LogPolicy logPolicy(@NotNull Exception e, @NotNull ErrorEntry entry) {
                    return space.br1440.platform.errorhandling.extension.LogPolicy.WARN;
                }

                @Override
                public boolean supportsCause(@NotNull Throwable cause) {
                    supportsCauseCallCount.incrementAndGet();
                    return true;
                }

                @Override
                public @NotNull RootCauseResolution extractCauseDetails(@NotNull Throwable cause) {
                    return resolution;
                }
            };

            CauseChainResolver resolver = new CauseChainResolver(List.of(alwaysTrue));

            // Заполняем кэш до предела через вспомогательный метод
            fillCacheToCapacity(resolver, alwaysTrue);

            // when: вызов с новым типом после достижения лимита
            int countBeforeOverflow = supportsCauseCallCount.get();
            resolver.resolve(new java.net.SocketTimeoutException("overflow type"));

            // then: overflow-тип обрабатывается через линейный поиск (supportsCause вызван ещё раз)
            assertThat(supportsCauseCallCount.get()).isGreaterThan(countBeforeOverflow);
        }

        @RepeatedTest(5)
        @DisplayName("конкурентные вызовы не вызывают гонок: результат детерминирован")
        void concurrent_noRaceConditions_resultsAreConsistent() throws Exception {
            // given
            when(highPriority.supportsCause(any())).thenReturn(true);
            RootCauseResolution resolution = RootCauseResolution.of(HttpStatus.BAD_REQUEST, "ok", List.of());
            when(highPriority.extractCauseDetails(any())).thenReturn(resolution);

            CauseChainResolver resolver = new CauseChainResolver(List.of(highPriority));

            int threads = 8;
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<ResolvedRootCause>> futures = new ArrayList<>();

            try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
                try {
                    for (int i = 0; i < threads; i++) {
                        futures.add(pool.submit(() -> {
                            ready.countDown();
                            start.await();
                            return resolver.resolve(new IllegalStateException("concurrent"));
                        }));
                    }
                    ready.await();
                    start.countDown();

                    // when & then
                    for (Future<ResolvedRootCause> f : futures) {
                        ResolvedRootCause result = f.get();
                        assertThat(result).isNotNull();
                        assertThat(result.resolution().httpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    }
                } finally {
                    pool.shutdownNow();
                }

                // supportsCause вызван хотя бы один раз (остальные — кэш)
                verify(highPriority, atLeastOnce()).supportsCause(any());
            }
        }

        @Nested
        @DisplayName("Depth limit & cycle protection")
        class DepthAndCycleProtection {

            @Test
            void stopsAtMaxCauseDepth() {
                // given
                when(highPriority.supportsCause(any())).thenReturn(false);

                int beyondMax = CauseChainResolver.MAX_CAUSE_DEPTH + 5;
                RuntimeException current = new RuntimeException("beyond-limit");
                for (int i = 0; i < beyondMax; i++) {
                    current = new RuntimeException("level-" + i, current);
                }

                CauseChainResolver resolver = new CauseChainResolver(List.of(highPriority));

                // when
                ResolvedRootCause resolved = resolver.resolve(current);

                // then
                assertThat(resolved).isNotNull();
                assertThat(resolved.cause()).isSameAs(current);
                assertThat(resolved.resolution().httpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            }

            @Test
            void resolveAll_stopsAtMaxCauseDepth() {
                // given
                when(highPriority.supportsCause(any())).thenReturn(true);
                when(highPriority.extractCauseDetails(any()))
                        .thenReturn(RootCauseResolution.of(HttpStatus.BAD_REQUEST, "matched", List.of()));

                int beyondMax = CauseChainResolver.MAX_CAUSE_DEPTH + 10;
                RuntimeException current = new RuntimeException("deep-leaf");
                for (int i = 0; i < beyondMax; i++) {
                    current = new RuntimeException("level-" + i, current);
                }

                CauseChainResolver resolver = new CauseChainResolver(List.of(highPriority));

                // when
                List<ResolvedRootCause> chain = resolver.resolveAll(current);

                // then
                assertThat(chain)
                        .hasSizeLessThanOrEqualTo(CauseChainResolver.MAX_CAUSE_DEPTH)
                        .isNotEmpty();
            }

            @Test
            void cyclicCauseChain_doesNotHang_resolve() {
                // given
                when(highPriority.supportsCause(any())).thenReturn(false);

                Exception b = new Exception("b");
                Exception a = ExceptionChainBuilder.withForcedCause("a", b);
                Exception bCyclic = ExceptionChainBuilder.withForcedCause("b", a);

                CauseChainResolver resolver = new CauseChainResolver(List.of(highPriority));

                // when
                ResolvedRootCause resolved = resolver.resolve(bCyclic);

                // then
                assertThat(resolved).isNotNull();
                assertThat(resolved.resolution().httpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            }

            @Test
            void cyclicCauseChain_doesNotHang_resolveAll() {
                // given
                when(highPriority.supportsCause(any())).thenReturn(true);
                when(highPriority.extractCauseDetails(any()))
                        .thenReturn(RootCauseResolution.of(HttpStatus.BAD_REQUEST, "cyclic", List.of()));

                Exception b = new Exception("b");
                Exception a = ExceptionChainBuilder.withForcedCause("a", b);
                Exception bCyclic = ExceptionChainBuilder.withForcedCause("b", a);

                CauseChainResolver resolver = new CauseChainResolver(List.of(highPriority));

                // when
                List<ResolvedRootCause> chain = resolver.resolveAll(bCyclic);

                // then
                assertThat(chain).hasSizeBetween(1, 3);
            }
        }

        @SuppressWarnings("unused")
        private static void fillCacheToCapacity(
                CauseChainResolver resolver,
                RootCauseExceptionHandlingStrategy strategy) {

            for (int i = 0; i < CauseChainResolver.MAX_CACHED_CAUSE_TYPES; i++) {
                RuntimeException unique = new RuntimeException("slot-" + i) {
                };

                resolver.resolve(unique);
            }
        }
    }
}
