package space.br1440.platform.errorhandling.core.strategy.cause.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;

import java.sql.SQLTransientConnectionException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HikariCpExceptionStrategy")
class HikariCpExceptionStrategyTest {

    private final HikariCpExceptionStrategy strategy =
            new HikariCpExceptionStrategy(null, new StackHintExtractor(false, List.of()));

    @Test
    @DisplayName("supports SQLTransientConnectionException")
    void supportsPoolTimeout() {
        assertThat(strategy.supportsCause(new SQLTransientConnectionException("pool timeout", "08001", 0)))
                .isTrue();
    }

    @Test
    @DisplayName("classifies pool timeout as 503")
    void poolTimeout() {
        RootCauseResolution resolution = strategy.extractCauseDetails(
                new SQLTransientConnectionException("HikariPool - timed out", "08001", 0));
        assertThat(resolution.httpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        ErrorInfoDetail info = resolution.details().stream()
                .filter(ErrorInfoDetail.class::isInstance)
                .map(ErrorInfoDetail.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(info.getDomain()).isEqualTo("connection-pool");
    }

    @Test
    @DisplayName("supports CannotGetJdbcConnectionException with Hikari cause")
    void cannotGetConnection() {
        SQLTransientConnectionException root = new SQLTransientConnectionException("timed out", "08001", 0);
        CannotGetJdbcConnectionException ex = new CannotGetJdbcConnectionException("get conn", root);
        assertThat(strategy.supportsCause(ex)).isTrue();
        RootCauseResolution resolution = strategy.extractCauseDetails(ex);
        assertThat(resolution.httpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
