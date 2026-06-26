package space.br1440.platform.errorhandling.integration.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.core.resolver.CauseChainResolver;
import space.br1440.platform.errorhandling.core.strategy.cause.network.TimeoutExceptionStrategy;
import space.br1440.platform.errorhandling.extension.ResolvedRootCause;
import space.br1440.platform.errorhandling.testing.StrategyTestContext;
import space.br1440.platform.errorhandling.testing.annotation.ErrorHandlingTest;
import space.br1440.platform.errorhandling.testing.annotation.WithCauseChain;
import space.br1440.platform.errorhandling.testing.annotation.WithStrategy;

import java.util.concurrent.TimeoutException;

import static space.br1440.platform.errorhandling.testing.assertions.ErrorHandlingAssertions.assertThat;

@ErrorHandlingTest
@WithStrategy(TimeoutExceptionStrategy.class)
@DisplayName("Декларативный тест: @ErrorHandlingTest + TimeoutExceptionStrategy")
class DeclarativeTimeoutStrategyTest {

    @Test
    @DisplayName("CauseChainResolver распознаёт TimeoutException и возвращает 504 GATEWAY_TIMEOUT")
    @WithCauseChain(exceptions = {RuntimeException.class, TimeoutException.class})
    void resolverHandlesTimeoutInCauseChain(StrategyTestContext ctx) {
        // given
        Throwable chain = ctx.getBuiltCauseChain();

        // when
        ResolvedRootCause resolved = ctx.resolve(chain);

        // then
        assertThat(resolved.resolution())
                .hasHttpStatus(HttpStatus.GATEWAY_TIMEOUT)
                .containsRetryInfo();
    }

    @Test
    @DisplayName("supportsCause → true для TimeoutException через инжектированную стратегию")
    void strategyRecognizesTimeoutException(TimeoutExceptionStrategy strategy) {
        // given
        var cause = new TimeoutException("Connection timed out");

        // when / then
        assertThat(strategy.supportsCause(cause)).isTrue();
    }

    @Test
    @DisplayName("CauseChainResolver возвращает fallback для неподдерживаемого исключения")
    void resolverFallsBackForUnknownException(CauseChainResolver resolver) {
        // given
        var unknown = new IllegalStateException("unrelated");

        // when
        ResolvedRootCause resolved = resolver.resolve(unknown);

        // then
        assertThat(resolved.resolution())
                .hasHttpStatus(HttpStatus.INTERNAL_SERVER_ERROR);
    }

}
