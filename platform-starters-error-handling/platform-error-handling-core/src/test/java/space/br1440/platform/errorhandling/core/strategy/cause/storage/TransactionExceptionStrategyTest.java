package space.br1440.platform.errorhandling.core.strategy.cause.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.UnexpectedRollbackException;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.Retryability;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TransactionExceptionStrategy")
class TransactionExceptionStrategyTest {

    private final TransactionExceptionStrategy strategy =
            new TransactionExceptionStrategy(new StackHintExtractor(false, List.of()));

    @Test
    @DisplayName("supports TransactionTimedOutException")
    void supportsTimeout() {
        assertThat(strategy.supportsCause(new TransactionTimedOutException("tx timeout", null))).isTrue();
    }

    @Test
    @DisplayName("classifies TIMEOUT")
    void timeoutResolution() {
        RootCauseResolution resolution = strategy.extractCauseDetails(
                new TransactionTimedOutException("tx timeout", null));
        assertThat(resolution.httpStatus()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(resolution.retryability()).isEqualTo(Retryability.RETRYABLE);
        assertThat(reason(resolution)).isEqualTo("TIMEOUT");
    }

    @Test
    @DisplayName("classifies UNEXPECTED_ROLLBACK")
    void unexpectedRollback() {
        RootCauseResolution resolution = strategy.extractCauseDetails(new UnexpectedRollbackException("rollback"));
        assertThat(resolution.httpStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(reason(resolution)).isEqualTo("UNEXPECTED_ROLLBACK");
    }

    @Test
    @DisplayName("classifies CANNOT_CREATE")
    void cannotCreate() {
        RootCauseResolution resolution = strategy.extractCauseDetails(
                new CannotCreateTransactionException("cannot create", null));
        assertThat(resolution.httpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(reason(resolution)).isEqualTo("CANNOT_CREATE");
    }

    private static String reason(RootCauseResolution resolution) {
        return resolution.details().stream()
                .filter(ErrorInfoDetail.class::isInstance)
                .map(ErrorInfoDetail.class::cast)
                .findFirst()
                .orElseThrow()
                .getReason();
    }
}
