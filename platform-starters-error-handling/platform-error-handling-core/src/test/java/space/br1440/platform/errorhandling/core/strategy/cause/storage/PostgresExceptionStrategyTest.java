package space.br1440.platform.errorhandling.core.strategy.cause.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L1 unit tests for {@link PostgresExceptionStrategy} (complements {@code PostgresStrategyE2ETest}).
 */
@DisplayName("PostgresExceptionStrategy")
class PostgresExceptionStrategyTest {

    private final PostgresExceptionStrategy strategy =
            new PostgresExceptionStrategy(false, new StackHintExtractor(false, List.of()));

    @Test
    @DisplayName("supports PSQLException")
    void supportsPsql() {
        assertThat(strategy.supportsCause(new PSQLException("duplicate key", PSQLState.UNIQUE_VIOLATION))).isTrue();
    }

    @Test
    @DisplayName("message path: too many clients → insufficient resources")
    void insufficientResourcesByMessage() {
        PSQLException ex = new PSQLException("sorry, too many clients already", PSQLState.UNKNOWN_STATE);
        RootCauseResolution resolution = strategy.extractCauseDetails(ex);
        assertThat(resolution.httpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        ErrorInfoDetail info = resolution.details().stream()
                .filter(ErrorInfoDetail.class::isInstance)
                .map(ErrorInfoDetail.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(info.getDomain()).isEqualTo("postgresql");
    }

    @Test
    @DisplayName("message path: timeout → query canceled / retryable band")
    void timeoutByMessage() {
        PSQLException ex = new PSQLException("canceling statement due to query timeout", PSQLState.QUERY_CANCELED);
        RootCauseResolution resolution = strategy.extractCauseDetails(ex);
        assertThat(resolution.httpStatus().is4xxClientError() || resolution.httpStatus().is5xxServerError()).isTrue();
    }
}
