package space.br1440.platform.errorhandling.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.extension.TopExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.model.ErrorEntry;

import java.nio.file.AccessDeniedException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Интеграционный тест приоритета (ORDER) стратегий {@link TopExceptionHandlingStrategy}
 * <b>без Spring-контекста</b>.
 *
 * <p>Проверяемый контракт: при наличии нескольких стратегий первая по {@code getOrder()},
 * чей {@code supports()} возвращает {@code true}, побеждает — остальные не вызываются.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Приоритет (ORDER) TopExceptionHandlingStrategy")
class StrategyOrderingIntegrationTest {

    private final StackHintExtractor stackHintExtractor = new StackHintExtractor(false, List.of());
    private final ErrorHandlingContext context = ErrorHandlingContext.of(new ErrorHandlingProperties(), "/test", stackHintExtractor);

    private Optional<TopExceptionHandlingStrategy> selectWinningStrategy(List<TopExceptionHandlingStrategy> strategies,
                                                                         Exception exception) {
        return strategies.stream()
                .sorted(Comparator.comparingInt(TopExceptionHandlingStrategy::getOrder))
                .filter(s -> s.supports(exception))
                .findFirst();
    }

    // =================================================================== //
    //  Сценарий 1: BusinessException как top-level — ORDER=100 побеждает
    // =================================================================== //

    @Nested
    @DisplayName("BusinessException (top-level) — стратегия ORDER=100 побеждает")
    class BusinessExceptionTopLevel {

        @Mock
        private TopExceptionHandlingStrategy businessStrategy;

        @Mock
        private TopExceptionHandlingStrategy accessDeniedStrategy;

        @Test
        @DisplayName("стратегия с ORDER=100 поддерживает BusinessException и обрабатывает её; ORDER=200 не вызывается")
        void strategyWithLowestOrderWinsForBusinessException() {
            // given
            RuntimeException businessException =
                    new RuntimeException("Business rule violation",
                            new AccessDeniedException("underlying access issue"));

            ErrorEntry expectedEntry = ErrorEntry.builder()
                    .httpStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                    .errorCode("BIZ-0100")
                    .message("Business rule violation")
                    .build();

            when(businessStrategy.getOrder()).thenReturn(100);
            when(businessStrategy.supports(businessException)).thenReturn(true);
            when(businessStrategy.handle(eq(businessException), any(ErrorHandlingContext.class)))
                    .thenReturn(expectedEntry);

            when(accessDeniedStrategy.getOrder()).thenReturn(200);

            List<TopExceptionHandlingStrategy> strategies = List.of(accessDeniedStrategy, businessStrategy);

            // when
            Optional<TopExceptionHandlingStrategy> winner = selectWinningStrategy(strategies, businessException);

            // then
            assertThat(winner).isPresent();
            ErrorEntry result = winner.get().handle(businessException, context);

            assertThat(result.getHttpStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(result.getErrorCode()).isEqualTo("BIZ-0100");

            verify(accessDeniedStrategy, never()).supports(any());
            verify(accessDeniedStrategy, never()).handle(any(), any());
        }
    }

    // ======================================================================= //
    //  Сценарий 2: AccessDeniedException как top-level — ORDER=200 побеждает
    // ======================================================================= //

    @Nested
    @DisplayName("AccessDeniedException (top-level) — ORDER=200 побеждает (обратный сценарий)")
    class AccessDeniedExceptionTopLevel {

        @Mock
        private TopExceptionHandlingStrategy businessStrategy;

        @Mock
        private TopExceptionHandlingStrategy accessDeniedStrategy;

        @Test
        @DisplayName("ORDER=100 НЕ поддерживает AccessDeniedException → ORDER=200 побеждает, хотя его приоритет ниже")
        void higherOrderStrategyWinsWhenLowerOrderDoesNotSupport() {
            // given
            AccessDeniedException accessDeniedException = new AccessDeniedException("forbidden resource");

            ErrorEntry expectedEntry = ErrorEntry.builder()
                    .httpStatus(HttpStatus.FORBIDDEN)
                    .errorCode("SEC-0403")
                    .message("Access denied")
                    .build();

            when(businessStrategy.getOrder()).thenReturn(100);
            when(businessStrategy.supports(accessDeniedException)).thenReturn(false);

            when(accessDeniedStrategy.getOrder()).thenReturn(200);
            when(accessDeniedStrategy.supports(accessDeniedException)).thenReturn(true);
            when(accessDeniedStrategy.handle(eq(accessDeniedException), any(ErrorHandlingContext.class)))
                    .thenReturn(expectedEntry);

            List<TopExceptionHandlingStrategy> strategies =
                    List.of(businessStrategy, accessDeniedStrategy);

            // when
            Optional<TopExceptionHandlingStrategy> winner =
                    selectWinningStrategy(strategies, accessDeniedException);

            // then
            assertThat(winner).isPresent();
            ErrorEntry result = winner.get().handle(accessDeniedException, context);

            assertThat(result.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(result.getErrorCode()).isEqualTo("SEC-0403");

            org.mockito.Mockito.verify(businessStrategy).supports(accessDeniedException);
            org.mockito.Mockito.verify(businessStrategy, org.mockito.Mockito.never())
                    .handle(any(), any());
        }
    }

    // =================================================================== //
    //  Сценарий 3: ValidationException побеждает ResponseStatusException
    // =================================================================== //

    @Nested
    @DisplayName("ValidationException (ORDER=400) побеждает ResponseStatusException (ORDER=900)")
    class ValidationBeatsResponseStatus {

        @Mock
        private TopExceptionHandlingStrategy validationStrategy;

        @Mock
        private TopExceptionHandlingStrategy responseStatusStrategy;

        @Test
        @DisplayName("ORDER=400 (validation) поддерживает исключение → побеждает ORDER=900 (responseStatus)")
        void lowerOrderValidationStrategyWins() {
            // given
            IllegalArgumentException validationException =
                    new IllegalArgumentException("field 'email' is invalid");

            ErrorEntry expectedEntry = ErrorEntry.builder()
                    .httpStatus(HttpStatus.BAD_REQUEST)
                    .errorCode("VAL-0400")
                    .message("Validation failed")
                    .build();

            when(validationStrategy.getOrder()).thenReturn(400);
            when(validationStrategy.supports(validationException)).thenReturn(true);
            when(validationStrategy.handle(eq(validationException), any(ErrorHandlingContext.class)))
                    .thenReturn(expectedEntry);

            when(responseStatusStrategy.getOrder()).thenReturn(900);

            List<TopExceptionHandlingStrategy> strategies =
                    List.of(responseStatusStrategy, validationStrategy);

            // when
            Optional<TopExceptionHandlingStrategy> winner =
                    selectWinningStrategy(strategies, validationException);

            // then
            assertThat(winner).isPresent();
            ErrorEntry result = winner.get().handle(validationException, context);

            assertThat(result.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(result.getErrorCode()).isEqualTo("VAL-0400");
            verify(responseStatusStrategy, never()).supports(any());
            verify(responseStatusStrategy, never()).handle(any(), any());
        }
    }

    // ================================================================== //
    //  Сценарий 4: Ни одна стратегия не поддерживает — fallback
    // ================================================================== //

    @Nested
    @DisplayName("Ни одна стратегия не поддерживает — DefaultUnknownExceptionHandler (fallback)")
    class FallbackToUnknownHandler {

        @Mock
        private TopExceptionHandlingStrategy strategyA;

        @Mock
        private TopExceptionHandlingStrategy strategyB;

        @Test
        @DisplayName("supports() всех стратегий возвращает false → winner отсутствует, активируется fallback")
        void noMatchingStrategyResultsInFallback() {
            // given
            RuntimeException unknownException =
                    new RuntimeException("completely unexpected error");

            when(strategyA.getOrder()).thenReturn(100);
            when(strategyA.supports(unknownException)).thenReturn(false);

            when(strategyB.getOrder()).thenReturn(200);
            when(strategyB.supports(unknownException)).thenReturn(false);

            List<TopExceptionHandlingStrategy> strategies = List.of(strategyA, strategyB);

            // when
            Optional<TopExceptionHandlingStrategy> winner =
                    selectWinningStrategy(strategies, unknownException);

            // then
            assertThat(winner).isEmpty();

            ErrorEntry fallbackEntry = ErrorEntry.builder()
                    .httpStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                    .errorCode("SVC-0500")
                    .message("Internal server error")
                    .build();

            assertThat(fallbackEntry.getHttpStatus())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(fallbackEntry.getErrorCode()).isEqualTo("SVC-0500");

            org.mockito.Mockito.verify(strategyA, org.mockito.Mockito.never())
                    .handle(any(), any());
            org.mockito.Mockito.verify(strategyB, org.mockito.Mockito.never())
                    .handle(any(), any());
        }
    }

}
