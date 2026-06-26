package space.br1440.platform.errorhandling.mvc.internal;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.extension.TopExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.extension.UnknownExceptionHandler;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.model.ErrorEntry;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ExceptionDispatcher")
class ExceptionDispatcherTest {

    private final ErrorHandlingContext context = ErrorHandlingContext.of(
            new ErrorHandlingProperties(), "/api/test", new StackHintExtractor(false, List.of())
    );

    @Nested
    @DisplayName("Контракт dispatch")
    class DispatchContract {

        @Test
        @DisplayName("Первая поддерживающая стратегия побеждает")
        void firstSupporting_wins() {
            // given
            CountingStrategy first = new CountingStrategy(true, "FIRST");
            CountingStrategy second = new CountingStrategy(true, "SECOND");
            ExceptionDispatcher dispatcher = new ExceptionDispatcher(List.of(first, second), unknownHandler());

            // when
            ExceptionDispatcher.DispatchResult result = dispatcher.dispatch(new RuntimeException("test"), context);

            // then
            assertThat(result.entry().getErrorCode()).isEqualTo("FIRST");
            assertThat(second.handleCalls.get()).isZero();
        }

        @Test
        @DisplayName("При отсутствии поддерживающих стратегий — fallback в UnknownExceptionHandler")
        void noSupporting_fallsBack() {
            // given
            ExceptionDispatcher dispatcher = new ExceptionDispatcher(
                    List.of(new CountingStrategy(false, "SKIP")), unknownHandler()
            );

            // when
            ExceptionDispatcher.DispatchResult result = dispatcher.dispatch(new RuntimeException("test"), context);

            // then
            assertThat(result.unhandled()).isTrue();
            assertThat(result.strategyName()).isEqualTo(ExceptionDispatcher.UNKNOWN_STRATEGY_NAME);
        }

        @Test
        @DisplayName("Fallback при пустом списке стратегий")
        void emptyStrategies_fallsBack() {
            // given
            ExceptionDispatcher dispatcher = new ExceptionDispatcher(List.of(), unknownHandler());

            // when
            ExceptionDispatcher.DispatchResult result = dispatcher.dispatch(new IllegalStateException("x"), context);

            // then
            assertThat(result.unhandled()).isTrue();
        }
    }

    @Nested
    @DisplayName("Type-кэш: O(1) после первого попадания")
    class TypeCache {

        @Test
        @DisplayName("supports() для одного класса вызывается ровно один раз — последующие вызовы кэш")
        void cachedAfterFirstHit() {
            // given
            CountingStrategy strategy = new CountingStrategy(true, "OK");
            ExceptionDispatcher dispatcher = new ExceptionDispatcher(List.of(strategy), unknownHandler());

            // when
            for (int i = 0; i < 100; i++) {
                dispatcher.dispatch(new IllegalArgumentException("test " + i), context);
            }

            // then
            assertThat(strategy.supportsCalls.get()).isOne();
            assertThat(strategy.handleCalls.get()).isEqualTo(100);
        }

        @Test
        @DisplayName("Разные классы исключений — отдельные cache slot'ы")
        void differentTypes_separateSlots() {
            // given
            CountingStrategy strategy = new CountingStrategy(true, "OK");
            ExceptionDispatcher dispatcher = new ExceptionDispatcher(List.of(strategy), unknownHandler());

            // when
            dispatcher.dispatch(new IllegalArgumentException("x"), context);
            dispatcher.dispatch(new IllegalStateException("y"), context);
            dispatcher.dispatch(new IllegalArgumentException("z"), context);

            // then
            assertThat(strategy.supportsCalls.get()).isEqualTo(2);
            assertThat(dispatcher.cacheSize()).isEqualTo(2);
        }

        @Test
        @DisplayName("При достижении лимита cardinality новые типы маршрутизируются без кэширования")
        void atCapacity_newTypesUseLinearScan() {
            // given
            int cap = 4;
            CountingStrategy strategy = new CountingStrategy(true, "OK");
            ExceptionDispatcher dispatcher = new ExceptionDispatcher(List.of(strategy), unknownHandler(), cap);

            // when
            dispatcher.dispatch(new IllegalArgumentException(), context);
            dispatcher.dispatch(new IllegalStateException(), context);
            dispatcher.dispatch(new NumberFormatException(), context);
            dispatcher.dispatch(new ArithmeticException(), context);

            // then
            assertThat(dispatcher.cacheSize()).isEqualTo(cap);

            // when
            int callsBefore = strategy.supportsCalls.get();
            dispatcher.dispatch(new ClassCastException(), context);

            // then
            assertThat(strategy.supportsCalls.get()).isEqualTo(callsBefore + 1);
            assertThat(dispatcher.cacheSize()).isEqualTo(cap);
        }
    }

    @Nested
    @DisplayName("Валидация конструктора")
    class ConstructorValidation {

        @Test
        @DisplayName("maxCachedTypes <= 0 — IllegalArgumentException")
        void invalidCacheLimit_throws() {
            // when
            assertThatThrownBy(() -> new ExceptionDispatcher(List.of(), unknownHandler(), 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static UnknownExceptionHandler unknownHandler() {
        return new UnknownExceptionHandler() {
            @Override
            @NonNull
            public @NotNull ErrorEntry handle(@NonNull Exception exception, @NonNull ErrorHandlingContext context) {
                return ErrorEntry.builder()
                        .httpStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .errorCode("UNKNOWN")
                        .message("unknown")
                        .build();
            }
        };
    }

    /** Тестовая стратегия со счётчиками вызовов. */
    private static final class CountingStrategy implements TopExceptionHandlingStrategy {

        final AtomicInteger supportsCalls = new AtomicInteger();
        final AtomicInteger handleCalls = new AtomicInteger();
        private final boolean supports;
        private final String code;

        CountingStrategy(boolean supports, String code) {
            this.supports = supports;
            this.code = code;
        }

        @Override
        public boolean supports(@NonNull Exception exception) {
            supportsCalls.incrementAndGet();
            return supports;
        }

        @Override
        @NonNull
        public ErrorEntry handle(@NonNull Exception exception, @NonNull ErrorHandlingContext context) {
            handleCalls.incrementAndGet();
            return ErrorEntry.builder()
                    .httpStatus(HttpStatus.BAD_REQUEST)
                    .errorCode(code)
                    .message(code)
                    .build();
        }

        @Override
        @NonNull
        public LogPolicy logPolicy(@NonNull Exception exception, @NonNull ErrorEntry entry) {
            return LogPolicy.WARN;
        }

        @Override
        public int getOrder() {
            return 0;
        }
    }
}
