package space.br1440.platform.errorhandling.model.error_detail;

import lombok.Getter;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;

/**
 * Базовая задержка до допустимого повтора запроса (retry delay).
 *
 * <p>Передаётся в {@code ErrorEntry.details[]} и сериализуется в DTO как detail
 * с {@code "type": "RETRY_INFO"} (см. {@code RetryInfoDetailDTO}).
 * Спецификация рекомендует: клиент использует exponential backoff на основе этой
 * задержки — сервер сообщает только базовую задержку ({@code retry_delay}),
 * стратегию backoff реализует клиент. OpenTelemetry Protocol и Google AIP-193
 * используют тот же паттерн.
 *
 * <h2>Примеры</h2>
 * <pre>{@code
 * // retryable, задержка не определена сервером (CB-open, bulkhead, transient)
 * new RetryInfoDetail()
 *
 * // retryable с конкретной задержкой (rate limit — Retry-After известен)
 * new RetryInfoDetail(Duration.ofSeconds(30))
 * }</pre>
 *
 * @see <a href="https://google.aip.dev/193">Google AIP-193: Errors</a>
 * @see <a href="https://github.com/googleapis/googleapis/blob/master/google/rpc/error_details.proto">google.rpc.RetryInfo</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7231#section-7.1.3">RFC 7231 Retry-After</a>
 */
@Getter
public non-sealed class RetryInfoDetail implements ErrorDetail {

    /**
     * Минимальная задержка до следующего допустимого повтора запроса.
     * <p>
     * Клиент не должен повторять запрос ранее, чем через этот интервал.
     * Рекомендуется применять exponential backoff на основе этого значения.
     * <p>
     * {@code retryDelay = null} — каноничный маркер «retryable без конкретной задержки»
     * согласно Google AIP-193: сервер сигнализирует, что ошибка временная и запрос
     * можно повторить, но не навязывает конкретное время ожидания.
     */
    @Nullable
    private final Duration retryDelay;

    /**
     * Используется когда сервер не может или не должен рекомендовать конкретное время ожидания
     * (circuit breaker open, bulkhead full, transient storage errors и т.п.).
     */
    public RetryInfoDetail() {
        this.retryDelay = null;
    }

    /**
     * Используется когда сервер знает точное время ожидания (rate limit с {@code Retry-After}).
     *
     * @param retryDelay минимальная задержка до следующего повтора, {@code null} допустим
     *                   (означает «retryable, задержка не определена»).
     * @throws IllegalArgumentException если {@code retryDelay} отрицателен —
     *                                  отрицательная задержка не имеет физического смысла
     *                                  и указывает на ошибку в логике стратегии.
     */
    public RetryInfoDetail(@Nullable Duration retryDelay) {
        if (retryDelay != null && retryDelay.isNegative()) {
            throw new IllegalArgumentException("retryDelay must not be negative, got: " + retryDelay);
        }

        this.retryDelay = retryDelay;
    }
}
