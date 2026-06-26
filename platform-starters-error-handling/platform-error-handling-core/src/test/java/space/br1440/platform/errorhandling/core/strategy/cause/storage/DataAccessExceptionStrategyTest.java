package space.br1440.platform.errorhandling.core.strategy.cause.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DataAccessExceptionStrategy")
class DataAccessExceptionStrategyTest {

    private final DataAccessExceptionStrategy strategy =
            new DataAccessExceptionStrategy(new StackHintExtractor(false, List.of()));

    @Test
    @DisplayName("supports DuplicateKeyException")
    void supportsDuplicateKey() {
        assertThat(strategy.supportsCause(new DuplicateKeyException("dup", null))).isTrue();
    }

    @Test
    @DisplayName("classifies DUPLICATE_KEY as 409")
    void duplicateKey_conflict() {
        RootCauseResolution resolution = strategy.extractCauseDetails(
                new DuplicateKeyException("uk_callsign", new RuntimeException("23505")));
        assertThat(resolution.httpStatus()).isEqualTo(HttpStatus.CONFLICT);
        ErrorInfoDetail info = resolution.details().stream()
                .filter(ErrorInfoDetail.class::isInstance)
                .map(ErrorInfoDetail.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(info.getReason()).isEqualTo("DUPLICATE_KEY");
        assertThat(info.getDomain()).isEqualTo("persistence");
    }

    @Test
    @DisplayName("classifies QUERY_TIMEOUT as 504")
    void queryTimeout_gatewayTimeout() {
        RootCauseResolution resolution = strategy.extractCauseDetails(new QueryTimeoutException("timeout", null));
        assertThat(resolution.httpStatus()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
    }

    @Test
    @DisplayName("classifies INTEGRITY_VIOLATION")
    void integrityViolation() {
        RootCauseResolution resolution = strategy.extractCauseDetails(
                new DataIntegrityViolationException("constraint", null));
        assertThat(resolution.httpStatus()).isEqualTo(HttpStatus.CONFLICT);
    }
}
