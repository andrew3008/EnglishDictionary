package space.br1440.platform.errorhandling.model;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.Singular;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.utils.NullSafeToStringStyle;

import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Протокол-независимая доменная модель ошибки (внутреннее представление).
 *
 * <h2>Связь с HTTP API (имена полей)</h2>
 * <p>
 * В теле JSON-ответа клиенту используется {@link space.br1440.platform.errorhandling.model.dto.ErrorEntryDTO},
 * где числовой статус называется {@code httpCode} (тип {@code int}). В доменной модели то же значение
 * хранится как {@link org.springframework.http.HttpStatus} в поле {@link #httpStatus} — это одна и та же
 * семантика, различие только в имени и типе представления (enum снаружи сериализуется в число).
 *
 * <h2>Дизайн</h2>
 * <p>
 * Гибридная модель, совмещающая:
 * <ul>
 *   <li><a href="https://cloud.google.com/apis/design/errors#error_model">Google API Error Model</a>
 *       ({@code google.rpc.Status}) — поля {@code httpStatus}, {@code message},
 *       {@code details}, {@code domain}, {@code reason}.</li>
 *   <li><a href="https://www.rfc-editor.org/rfc/rfc9457">RFC 9457 Problem Details</a>
 *       — поле {@code requestURI} (сериализуется как {@code "instance"} в JSON),
 *       Content-Type {@code application/problem+json}.</li>
 * </ul>
 *
 * <h2>Lifecycle поля {@code httpStatus}</h2>
 * <p>
 * Внутреннее поле может быть не задано ({@code null}) на промежуточных шагах pipeline.
 * Для чтения используйте {@link #getHttpStatus()} — он выбрасывает {@link IllegalStateException},
 * если статус ещё не установлен (fail-fast). Для диагностики без исключения — {@link #httpStatusOrNull()}.
 * <p>
 * После стратегий и обогащения (цепочка enricher в стартере) перед сериализацией в ответ клиенту
 * статус обязан быть задан.
 *
 * <h2>Поле {@code httpStatus}</h2>
 * <p>
 * Числовой HTTP-статус ответа (например, {@code 404}, {@code 503}).
 * Является единственным источником правды для HTTP-классификации ошибки
 * <em>после завершения pipeline обработки</em> (до этого может быть {@code null}).
 */
@Getter
@Builder(toBuilder = true)
public class ErrorEntry {

    /**
     * Канонический семантический http-статус ошибки.
     *
     * @see <a href="https://grpc.github.io/grpc/core/md_doc_http-grpc-status-mapping.html">gRPC → HTTP Status Mapping</a>
     * @see #getHttpStatus()
     */
    @Getter(AccessLevel.NONE)
    @Nullable
    private final HttpStatus httpStatus;

    /**
     * Машиночитаемый код ошибки top-exception (бизнес/wrapper).
     * <p>
     * <b>Формирование:</b> автоматически из simple name concrete-исключения —
     * отбрасывается суффикс {@code Exception}, оставшаяся часть переводится из
     * {@code CamelCase} в {@code SCREAMING_SNAKE_CASE}.
     * Пример: {@code CalculationPersistenceException} → {@code "CALCULATION_PERSISTENCE"}.
     * <p>
     * Стабилен между релизами (пока не переименовано исключение); используется клиентом
     * для классификации ошибки и роутинга в UI. Соответствует {@code errorCode} в
     * {@link space.br1440.platform.errorhandling.model.dto.ErrorEntryDTO}.
     * <p>
     * Машиночитаемый код root-cause (если есть) — в {@code details[].ERROR_INFO.reason}, не здесь.
     */
    @NotNull
    private final String errorCode;

    /**
     * RFC 9457 §3.1 — URI идентификации problem type.
     *
     * <p>Может быть {@code null} на уровне внутренней доменной модели — в этом случае
     * маппер выставит дефолт {@code "about:blank"} (RFC 9457 §4.1) при сериализации
     * в DTO. Сервисы могут заполнить это поле в стратегии для линковки на runbook
     * или error-каталог UI.
     *
     * @see <a href="https://www.rfc-editor.org/rfc/rfc9457#section-3.1">RFC 9457 §3.1</a>
     */
    @Nullable
    private final String type;

    /**
     * Описание ошибки для разработчика и UI (RFC 9457 §3.4).
     * <p>
     * Соответствует {@code google.rpc.Status.message}. Должно помогать
     * клиенту понять и исправить проблему.
     *
     * @see <a href="https://cloud.google.com/apis/design/errors#error_model">google.rpc.Status.message</a>
     */
    @NotNull
    private final String message;

    /**
     * Человеко-читаемое сообщение root-cause (для DTO {@code reason}).
     * <p>
     * Заполняется top-strategy при наличии cause: приоритетно из
     * {@code RootCauseResolution.message()} (санитизировано стратегией), либо
     * fallback на {@code rootCause.getMessage()}. Если cause отсутствует -- {@code null}.
     * <p>
     * <b>Машинный код root-cause</b> (например {@code "EXTERNAL_SERVICE"},
     * {@code "DEADLINE_EXCEEDED"}) -- в {@code details[].ErrorInfoDetail.reason}.
     * Здесь -- именно человеко-читаемая строка для отображения клиенту.
     */
    @Nullable
    private final String reason;

    /**
     * Домен ошибки — логическая подсистема или сервис.
     * <p>
     * Соответствует {@code google.rpc.ErrorInfo.domain}.
     * Например: {@code "billing"}, {@code "user-service"}.
     *
     * @see <a href="https://cloud.google.com/apis/design/errors#error_info">google.rpc.ErrorInfo.domain</a>
     */
    @Nullable
    private final String domain;

    /**
     * URI HTTP-запроса, при котором возникла ошибка.
     * Например: {@code "/api/orders/42"}, {@code "/api/users"}.
     * <p>
     * Позволяет коррелировать ошибку с access-логами по {@code requestURI} + {@code correlationId}.
     *
     * <p>Вдохновлено RFC 9457 {@code instance}, но сознательно использует имя
     * {@code requestURI}: поле содержит URI запроса (контекст), а не ссылку
     * на конкретный инцидент ошибки, как определяет RFC 9457 §3.5.
     *
     * @see <a href="https://www.rfc-editor.org/rfc/rfc9457#section-3.5">RFC 9457 §3.5 (reference)</a>
     */
    @Nullable
    private final String requestURI;

    /**
     * Инфраструктурный контекст запроса (correlationId, traceId, spanId).
     */
    @Nullable
    private final RequestContext requestContext;

    /**
     * Структурированные детали ошибки ({@code google.rpc.Status.details}).
     * <p>
     * Полиморфный список — каждый элемент описывает конкретный аспект ошибки:
     * нарушения полей, политику retry, информацию о ресурсе и т.д.
     *
     * <h3>Конвенция «own/header» — первый {@link space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail}</h3>
     * <p>По соглашению, синтезированному фабрикой {@link ErrorEntryFactory},
     * первый элемент типа {@code ErrorInfoDetail} — это «header» текущего сервиса:
     * {@code reason}=={@link #errorCode}, {@code domain}=={@link #domain},
     * {@code clientMetadata} соответствует top-level метаданным ошибки.
     * <p>
     * При маппинге в {@link space.br1440.platform.errorhandling.model.dto.ErrorEntryDTO}
     * этот header <b>не дублируется</b> в DTO {@code details[]} — он целиком представлен
     * top-level полями DTO ({@code errorCode}/{@code domain}/{@code metadata}),
     * что соответствует контракту {@code google.rpc.ErrorInfo} (см.
     * <a href="https://google.aip.dev/193">AIP-193</a>). Остальные элементы (cause-цепочка
     * {@code ERROR_INFO} и не-{@code ErrorInfo} детали) сохраняются в порядке вставки.
     *
     * @see ErrorEntryFactory
     */
    @Getter(AccessLevel.NONE)
    @Singular
    private final List<ErrorDetail> details;

    @Nullable
    private final String stackTrace;

    @NotNull
    public List<ErrorDetail> getDetails() {
        return details;
    }

    public @NotNull HttpStatus getHttpStatus() {
        if (httpStatus == null) {
            throw new IllegalStateException("ErrorEntry.httpStatus is not set (errorCode=" + errorCode + ")");
        }

        return httpStatus;
    }

    @Nullable
    public HttpStatus httpStatusOrNull() {
        return httpStatus;
    }

    public boolean isRetryable() {
        return !details.isEmpty()
                && details.stream().anyMatch(RetryInfoDetail.class::isInstance);
    }

    @Nullable
    public Duration getRetryDelay() {
        if (details.isEmpty()) {
            return null;
        }

        return details.stream()
                .filter(RetryInfoDetail.class::isInstance)
                .map(RetryInfoDetail.class::cast)
                .findFirst()
                .map(RetryInfoDetail::getRetryDelay)
                .orElse(null);
    }

    /**
     * Кастомный Lombok builder — переопределяет только {@code build()} для fail-fast
     * валидации обязательных полей {@code errorCode} и {@code message}.
     *
     * <h2>⚠️ Ловушка {@code @Singular} + {@code toBuilder()}</h2>
     * <p>Поле {@link ErrorEntry#details} аннотировано {@link Singular}, из-за
     * чего Lombok генерирует {@code details(Collection)} с <b>add-семантикой</b>:
     * коллекция <i>добавляется</i> к уже накопленному content-у builder-а, а
     * не заменяет его. В сочетании с {@code @Builder(toBuilder = true)} это
     * создаёт неинтуитивное поведение:
     *
     * <pre>{@code
     *   ErrorEntry original = ErrorEntry.builder()
     *           .details(List.of(existingDetail))
     *           .build();
     *   // aккумулятор ErrorEntryBuilder после toBuilder() содержит [existingDetail]
     *   ErrorEntry enriched = original.toBuilder()
     *           .details(List.of(existingDetail, helpDetail))  // APPEND, не replace!
     *           .build();
     *   // enriched.getDetails() == [existingDetail, existingDetail, helpDetail]  ← ДУБЛИРОВАНИЕ
     * }</pre>
     *
     * <p>Переопределить {@code details(Collection)} с replace-семантикой
     * <b>нельзя</b>: Lombok запрещает ручное переопределение методов,
     * генерируемых для {@code @Singular}-полей (см.
     * <a href="https://projectlombok.org/features/Singular">Lombok docs</a>).
     * Удаление {@code @Singular} — breaking change для всех модулей стартера,
     * активно использующих {@code .detail(item)} и {@code .clearDetails()}
     * через SPI-стратегии.
     *
     * <h3>Правильный паттерн обогащения</h3>
     * <p>Для добавления новых элементов поверх существующих при
     * {@code toBuilder()} используйте {@code .detail(item)} (single add)
     * для каждого нового элемента:
     * <pre>{@code
     *   // НЕПРАВИЛЬНО (дублирование existing):
     *   return entry.toBuilder().details(List.of(existing, help)).build();
     *
     *   // ПРАВИЛЬНО — добавление одного элемента через single-add:
     *   return entry.toBuilder().detail(help).build();
     *
     *   // ПРАВИЛЬНО — полная замена через явный clear:
     *   return entry.toBuilder().clearDetails().details(newList).build();
     * }</pre>
     */
    public static class ErrorEntryBuilder {
        public ErrorEntry build() {
            Objects.requireNonNull(errorCode, "ErrorEntry.errorCode must not be null — set a valid error code before calling build()");
            Objects.requireNonNull(message, "ErrorEntry.message must not be null — set a descriptive message before calling build()");

            List<ErrorDetail> safeDetails = (this.details == null)
                    ? List.of()
                    : List.copyOf(this.details);

            return new ErrorEntry(
                    httpStatus, errorCode, type, message, reason, domain, requestURI,
                    requestContext, safeDetails, stackTrace
            );
        }
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this, NullSafeToStringStyle.SKIP_NULL)
                .append("httpStatus", httpStatus)
                .append("errorCode", errorCode)
                .append("message", message)
                .append("reason", reason)
                .append("domain", domain)
                .append("requestURI", requestURI)
                .append("requestContext", requestContext)
                .append("details", details)
                .append("stackTrace", stackTrace)
                .toString();
    }
}
