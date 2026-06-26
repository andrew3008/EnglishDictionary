package space.br1440.platform.errorhandling.property;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.core.resolver.CauseChainResolver;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.extension.ResolvedRootCause;
import space.br1440.platform.errorhandling.extension.RootCauseExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.model.ErrorEntry;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Инвариантные проверки {@link CauseChainResolver}: детерминированная генерация
 * цепочек {@link Throwable} (JUnit&nbsp;5 + циклы, без jqwik).
 *
 * <p>Семантику прежних {@code @Property}: устойчивость к ошибкам, идемпотентность,
 * корректность fallback и кэша.
 */
@DisplayName("CauseChainResolver (инварианты)")
class CauseChainResolverPropertyTest {

    private static final int ITERATIONS = 320;

    private static final long SEED_NEVER_THROWS = 0xCCA1_553E_0000_0001L;
    private static final long SEED_IDEMPOTENT = 0xCCA1_553E_0000_0002L;
    private static final long SEED_CAUSE_ELEMENT = 0xCCA1_553E_0000_0003L;
    private static final long SEED_SUBSET_ALL = 0xCCA1_553E_0000_0004L;
    private static final long SEED_FALLBACK_500 = 0xCCA1_553E_0000_0005L;
    private static final long SEED_WITH_STRATEGY_NEVER_THROW = 0xCCA1_553E_0000_0006L;
    private static final long SEED_MATCH_OR_FALLBACK = 0xCCA1_553E_0000_0007L;
    private static final long SEED_CACHE_IDEMPOTENCY = 0xCCA1_553E_0000_0008L;
    private static final long SEED_CACHE_CAUSE_CHAIN = 0xCCA1_553E_0000_0009L;

    private final CauseChainResolver fallbackResolver = new CauseChainResolver(List.of());

    private final RootCauseExceptionHandlingStrategy illegalArgStrategy = new RootCauseExceptionHandlingStrategy() {
        @Override
        public int getOrder() {
            return 0;
        }

        @Override
        public @NotNull LogPolicy logPolicy(@NotNull Exception exception, ErrorEntry errorEntry) {
            return LogPolicy.WARN;
        }

        @Override
        public boolean supportsCause(Throwable cause) {
            return cause instanceof IllegalArgumentException;
        }

        @Override
        public RootCauseResolution extractCauseDetails(Throwable cause) {
            return RootCauseResolution.of(
                    HttpStatus.BAD_REQUEST, cause.getMessage(), List.of());
        }
    };

    private final CauseChainResolver withIllegalArgStrategy = new CauseChainResolver(List.of(illegalArgStrategy));

    @Test
    @DisplayName("resolve() не выбрасывает исключений ни для какой цепочки")
    void resolve_neverThrows() {
        SplittableRandom rng = new SplittableRandom(SEED_NEVER_THROWS);
        for (int i = 0; i < ITERATIONS; i++) {
            Throwable chain = randomChain(rng);
            assertThatCode(() -> fallbackResolver.resolve(chain)).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("resolve() идемпотентен: два последовательных вызова — тот же cause и резолюция")
    void resolve_isIdempotent() {
        SplittableRandom rng = new SplittableRandom(SEED_IDEMPOTENT);
        for (int i = 0; i < ITERATIONS; i++) {
            Throwable chain = randomChain(rng);
            ResolvedRootCause first = fallbackResolver.resolve(chain);
            ResolvedRootCause second = fallbackResolver.resolve(chain);

            assertThat(first.cause()).isSameAs(second.cause());
            assertThat(first.resolution().httpStatus()).isEqualTo(second.resolution().httpStatus());
            assertThat(first.resolution().message()).isEqualTo(second.resolution().message());
        }
    }

    @Test
    @DisplayName("resolve().cause() — элемент исходной цепочки причин")
    void resolve_causeIsElementOfOriginalChain() {
        SplittableRandom rng = new SplittableRandom(SEED_CAUSE_ELEMENT);
        for (int i = 0; i < ITERATIONS; i++) {
            Throwable chain = randomChain(rng);
            ResolvedRootCause resolved = fallbackResolver.resolve(chain);
            Set<Throwable> chainElements = collectChainElements(chain);
            assertThat(chainElements).contains(resolved.cause());
        }
    }

    @Test
    @DisplayName("resolveAll(): каждый cause — элемент исходной цепочки")
    void resolveAll_returnsSubsetOfOriginalChain() {
        SplittableRandom rng = new SplittableRandom(SEED_SUBSET_ALL);
        for (int i = 0; i < ITERATIONS; i++) {
            Throwable chain = randomChain(rng);
            List<ResolvedRootCause> results = fallbackResolver.resolveAll(chain);
            Set<Throwable> chainElements = collectChainElements(chain);
            for (ResolvedRootCause rc : results) {
                assertThat(chainElements).contains(rc.cause());
            }
        }
    }

    @Test
    @DisplayName("При пустом списке стратегий — fallback INTERNAL_SERVER_ERROR и непустое message")
    void resolve_withEmptyStrategies_returnsFallbackInternalServerError() {
        SplittableRandom rng = new SplittableRandom(SEED_FALLBACK_500);
        for (int i = 0; i < ITERATIONS; i++) {
            Throwable chain = randomChain(rng);
            ResolvedRootCause resolved = fallbackResolver.resolve(chain);
            assertThat(resolved.resolution().httpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(resolved.resolution().message()).isNotBlank();
        }
    }

    @Test
    @DisplayName("Со стратегией под IllegalArgumentException — resolve не бросает")
    void resolve_withStrategy_neverThrows() {
        SplittableRandom rng = new SplittableRandom(SEED_WITH_STRATEGY_NEVER_THROW);
        for (int i = 0; i < ITERATIONS; i++) {
            Throwable chain = randomChain(rng);
            assertThatCode(() -> withIllegalArgStrategy.resolve(chain)).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("Со стратегией: IAE в цепочке → BAD_REQUEST; иначе → INTERNAL_SERVER_ERROR")
    void resolve_withStrategy_matchesOrFallsBack() {
        SplittableRandom rng = new SplittableRandom(SEED_MATCH_OR_FALLBACK);
        for (int i = 0; i < ITERATIONS; i++) {
            Throwable chain = randomChain(rng);
            ResolvedRootCause resolved = withIllegalArgStrategy.resolve(chain);
            boolean chainContainsIllegalArg = collectChainElements(chain).stream()
                    .anyMatch(IllegalArgumentException.class::isInstance);

            if (chainContainsIllegalArg) {
                assertThat(resolved.resolution().httpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(resolved.cause()).isInstanceOf(IllegalArgumentException.class);
            } else {
                assertThat(resolved.resolution().httpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            }
        }
    }

    @Test
    @DisplayName("После прогрева кэша повторный resolve даёт тот же результат")
    void resolve_cacheDoesNotBreakIdempotency() {
        SplittableRandom rng = new SplittableRandom(SEED_CACHE_IDEMPOTENCY);
        for (int i = 0; i < ITERATIONS; i++) {
            Throwable chain = randomChain(rng);
            ResolvedRootCause warmup = withIllegalArgStrategy.resolve(chain);
            ResolvedRootCause cached = withIllegalArgStrategy.resolve(chain);

            assertThat(cached.cause()).isSameAs(warmup.cause());
            assertThat(cached.resolution().httpStatus()).isEqualTo(warmup.resolution().httpStatus());
            assertThat(cached.resolution().message()).isEqualTo(warmup.resolution().message());
        }
    }

    @Test
    @DisplayName("С кэшем: resolve().cause() остаётся элементом исходной цепочки")
    void resolve_withCache_causeIsElementOfOriginalChain() {
        SplittableRandom rng = new SplittableRandom(SEED_CACHE_CAUSE_CHAIN);
        for (int i = 0; i < ITERATIONS; i++) {
            Throwable chain = randomChain(rng);
            withIllegalArgStrategy.resolve(chain);
            ResolvedRootCause resolved = withIllegalArgStrategy.resolve(chain);
            Set<Throwable> chainElements = collectChainElements(chain);
            assertThat(chainElements).contains(resolved.cause());
        }
    }

    /**
     * Длина цепочки по смыслу jqwik {@code recursive(..., 1, 10)}: глубина 1..10
     * (корень — один из базовых типов, далее 0..9 обёрток {@link RuntimeException}).
     */
    private static Throwable randomChain(SplittableRandom rng) {
        int depth = rng.nextInt(1, 11);
        Throwable current = randomBase(rng);
        for (int i = 1; i < depth; i++) {
            current = new RuntimeException("wrapper", current);
        }
        return current;
    }

    private static Throwable randomBase(SplittableRandom rng) {
        return switch (rng.nextInt(4)) {
            case 0 -> new RuntimeException("base-runtime");
            case 1 -> new IllegalArgumentException("base-illegal-arg");
            case 2 -> new IllegalStateException("base-illegal-state");
            default -> new NullPointerException("base-npe");
        };
    }

    private static Set<Throwable> collectChainElements(Throwable top) {
        Set<Throwable> elements = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = top;
        int guard = 0;
        while (current != null && guard < 30 && elements.add(current)) {
            current = current.getCause();
            guard++;
        }
        return elements;
    }
}
