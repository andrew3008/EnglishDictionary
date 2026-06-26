package space.br1440.platform.errorhandling.core.util;

import lombok.experimental.UtilityClass;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.extension.ResolvedRootCause;
import space.br1440.platform.errorhandling.extension.Retryability;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Централизованный алгоритм определения retryable-статуса для top-level исключений.
 *
 * <h2>Трёхуровневый алгоритм</h2>
 * <ol>
 *   <li><b>Explicit</b>: {@code baseEntry} уже содержит {@link RetryInfoDetail} (top exception задал явно).</li>
 *   <li><b>Cause chain</b>: обход resolved causes:
 *       <ul>
 *         <li>{@link Retryability#RETRYABLE} → немедленный {@code true} (short-circuit).</li>
 *         <li>{@link Retryability#NOT_RETRYABLE} → немедленный {@code false}
 *             (блокирует HTTP status fallback).</li>
 *         <li>{@link Retryability#UNSPECIFIED} → пропуск, делегация следующему уровню.</li>
 *       </ul>
 *   </li>
 *   <li><b>HTTP status fallback</b>: итоговый {@code httpStatus} проверяется по 408/429/502/503/504.</li>
 * </ol>
 */
@UtilityClass
public class RetryabilityResolver {

    private static final Set<Integer> RETRYABLE_STATUS_CODES = Set.of(
            408,  // Request Timeout
            429,  // Too Many Requests
            502,  // Bad Gateway
            503,  // Service Unavailable
            504   // Gateway Timeout
    );

    public static boolean isRetryable(@NonNull ErrorEntry baseEntry,
                                      @NonNull List<ResolvedRootCause> chain,
                                      @NonNull HttpStatus resolvedStatus) {
        if (containsRetryInfo(baseEntry.getDetails())) {
            return true;
        }

        for (ResolvedRootCause resolved : chain) {
            Retryability signal = resolved.resolution().retryability();
            if (signal == Retryability.RETRYABLE) {
                return true;
            }

            if (signal == Retryability.NOT_RETRYABLE) {
                return false;
            }
        }

        return isRetryableByStatus(resolvedStatus);
    }

    public static boolean isRetryable(@NonNull ErrorEntry baseEntry,
                                      @NonNull RootCauseResolution RootCauseResolution) {
        if (containsRetryInfo(baseEntry.getDetails())) {
            return true;
        }

        Retryability signal = RootCauseResolution.retryability();
        if (signal == Retryability.RETRYABLE) {
            return true;
        }

        if (signal == Retryability.NOT_RETRYABLE) {
            return false;
        }

        return isRetryableByStatus(RootCauseResolution.httpStatus());
    }

    @Nullable
    public static Duration resolveRetryDelay(@NonNull List<ResolvedRootCause> chain) {
        for (ResolvedRootCause resolved : chain) {
            Duration delay = findRetryDelay(resolved.resolution().details());
            if (delay != null) {
                return delay;
            }
        }

        return null;
    }

    @Nullable
    public static Duration resolveRetryDelay(@NonNull ErrorEntry baseEntry,
                                             @NonNull List<ResolvedRootCause> chain) {
        Duration baseDelay = findRetryDelay(baseEntry.getDetails());
        if (baseDelay != null) {
            return baseDelay;
        }

        return resolveRetryDelay(chain);
    }

    @Nullable
    public static Duration resolveRetryDelay(@NonNull RootCauseResolution RootCauseResolution) {
        return findRetryDelay(RootCauseResolution.details());
    }

    public static boolean containsRetryInfo(@Nullable List<ErrorDetail> details) {
        if (details == null || details.isEmpty()) {
            return false;
        }

        for (ErrorDetail detail : details) {
            if (detail instanceof RetryInfoDetail) {
                return true;
            }
        }

        return false;
    }

    public static boolean isRetryableByStatus(@NonNull HttpStatus status) {
        return RETRYABLE_STATUS_CODES.contains(status.value());
    }

    /**
     * Извлекает задержку до повтора из первого {@link RetryInfoDetail} и возвращает
     * целое число секунд для HTTP-заголовка {@code Retry-After} (RFC 7231 §7.1.3).
     *
     * <p>Суб-секундные значения округляются вверх ({@code 500 ms → 1 s}).
     * Возвращает {@code 0}, если задержка отсутствует, нулевая или отрицательная.
     * Единственная каноническая реализация, используемая и MVC, и WebFlux handler'ами.
     */
    public static long retryDelayHeaderSeconds(@NonNull ErrorEntry entry) {
        Duration delay = findRetryDelay(entry.getDetails());
        if (delay == null || delay.isZero() || delay.isNegative()) {
            return 0L;
        }

        return (delay.toNanos() + 999_999_999L) / 1_000_000_000L;
    }

    @Nullable
    private static Duration findRetryDelay(@Nullable List<ErrorDetail> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }

        for (ErrorDetail detail : details) {
            if ((detail instanceof RetryInfoDetail retry) && (retry.getRetryDelay() != null)) {
                return retry.getRetryDelay();
            }
        }

        return null;
    }
}
