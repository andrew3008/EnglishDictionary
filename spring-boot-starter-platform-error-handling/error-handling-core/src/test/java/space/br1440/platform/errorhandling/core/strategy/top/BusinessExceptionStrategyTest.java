package space.br1440.platform.errorhandling.core.strategy.top;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.core.resolver.CauseChainResolver;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.extension.order.TopExceptionStrategyOrder;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.exception.business.BusinessException;
import space.br1440.platform.errorhandling.exception.datasource.ResourceNotFoundException;
import space.br1440.platform.errorhandling.exception.inconsistency.ConcurrentOperationException;
import space.br1440.platform.errorhandling.http.HttpStatusResolver;
import space.br1440.platform.errorhandling.model.ErrorEntry;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Контрактные тесты {@link BusinessExceptionStrategy}.
 *
 * <p>Покрывают: type-determinism {@code supports()} (только {@link BusinessException}),
 * корректную работу {@link HttpStatusResolver} в {@code handle()} и стабильность
 * порядка через {@link TopExceptionStrategyOrder#BUSINESS}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BusinessExceptionStrategy")
class BusinessExceptionStrategyTest {

    @Mock
    private HttpStatusResolver httpStatusResolver;

    private BusinessExceptionStrategy strategy;

    private static ErrorHandlingContext context(String path) {
        return ErrorHandlingContext.of(
                new ErrorHandlingProperties(), path, new StackHintExtractor(false, List.of()));
    }

    @BeforeEach
    void setUp() {
        CauseChainResolver resolver = new CauseChainResolver(Collections.emptyList());
        strategy = new BusinessExceptionStrategy(resolver, httpStatusResolver);
    }

    /**
     * Узкий тестовый наследник {@link BusinessException} без HTTP-маркеров —
     * статус подставляет резолвер-мок.
     */
    private static final class TestBusinessRuleException extends BusinessException {
        TestBusinessRuleException(String message) {
            super(message, Map.of());
        }
    }

    @Nested
    @DisplayName("supports — принимает только BusinessException")
    class Supports {

        @Test
        @DisplayName("принимает наследника BusinessException")
        void acceptsBusinessExceptionDescendant() {
            assertThat(strategy.supports(new TestBusinessRuleException("rule violated"))).isTrue();
        }

        @Test
        @DisplayName("не принимает не-business AbstractErrorResponseException — он намеренно проваливается в Unknown handler")
        void rejectsNonBusinessAbstractErrorResponseException() {
            // ResourceNotFoundException — наследник AbstractErrorResponseException, но НЕ BusinessException.
            // Контракт: на top-уровне такие исключения недопустимы и должны попадать в DefaultUnknownExceptionHandler.
            var ex = new ResourceNotFoundException("User", "42");

            assertThat(strategy.supports(ex)).isFalse();
        }

        @Test
        @DisplayName("не принимает inconsistency-исключение (не business)")
        void rejectsInconsistencyException() {
            var ex = new ConcurrentOperationException("export", "res-1");

            assertThat(strategy.supports(ex)).isFalse();
        }

        @Test
        @DisplayName("не принимает обычный RuntimeException")
        void rejectsPlainRuntimeException() {
            assertThat(strategy.supports(new RuntimeException("not platform"))).isFalse();
        }

        @Test
        @DisplayName("не принимает IllegalArgumentException")
        void rejectsIllegalArgumentException() {
            assertThat(strategy.supports(new IllegalArgumentException("bad"))).isFalse();
        }
    }

    @Nested
    @DisplayName("handle — формирование ErrorEntry")
    class Handle {

        @Test
        @DisplayName("HTTP-статус берётся из HttpStatusResolver")
        void usesHttpStatusResolver() {
            when(httpStatusResolver.resolve(any())).thenReturn(HttpStatus.UNPROCESSABLE_ENTITY);
            var ex = new TestBusinessRuleException("rule violated");

            ErrorEntry entry = strategy.handle(ex, context("/api/v1/orders/42"));

            assertThat(entry.getHttpStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }

        @Test
        @DisplayName("errorCode выводится из имени класса исключения (CamelCase → SCREAMING_SNAKE_CASE)")
        void errorCodeDerivedFromExceptionClassName() {
            when(httpStatusResolver.resolve(any())).thenReturn(HttpStatus.CONFLICT);
            var ex = new TestBusinessRuleException("rule violated");

            ErrorEntry entry = strategy.handle(ex, context("/"));

            assertThat(entry.getErrorCode()).isEqualTo("TEST_BUSINESS_RULE");
        }
    }

    @Nested
    @DisplayName("ordering")
    class Ordering {

        @Test
        @DisplayName("getOrder() = TopExceptionStrategyOrder.BUSINESS")
        void orderMatchesBusinessConstant() {
            assertThat(strategy.getOrder()).isEqualTo(TopExceptionStrategyOrder.BUSINESS);
        }
    }
}
