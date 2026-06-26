package space.br1440.platform.errorhandling.core.strategy.cause.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.Retryability;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ConcurrencyExceptionStrategy")
class ConcurrencyExceptionStrategyTest {

    private final ConcurrencyExceptionStrategy strategy =
            new ConcurrencyExceptionStrategy(new StackHintExtractor(false, List.of()));

    @Test
    @DisplayName("supports OptimisticLockingFailureException")
    void supportsOptimisticLock() {
        assertThat(strategy.supportsCause(new OptimisticLockingFailureException("stale", null))).isTrue();
    }

    @Test
    @DisplayName("classifies OPTIMISTIC_LOCK as retryable 409")
    void optimisticLock() {
        RootCauseResolution resolution = strategy.extractCauseDetails(
                new OptimisticLockingFailureException("stale", null));
        assertThat(resolution.httpStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resolution.retryability()).isEqualTo(Retryability.RETRYABLE);
        assertThat(reason(resolution)).isEqualTo("OPTIMISTIC_LOCK");
    }

    @Test
    @DisplayName("classifies DEADLOCK")
    void deadlock() {
        RootCauseResolution resolution = strategy.extractCauseDetails(
                new DeadlockLoserDataAccessException("deadlock", null));
        assertThat(resolution.httpStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(reason(resolution)).isEqualTo("DEADLOCK");
    }

    @Test
    @DisplayName("classifies LOCK_TIMEOUT")
    void lockTimeout() {
        RootCauseResolution resolution = strategy.extractCauseDetails(
                new CannotAcquireLockException("lock wait", null));
        assertThat(resolution.httpStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(reason(resolution)).isEqualTo("LOCK_TIMEOUT");
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
