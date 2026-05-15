package space.br1440.platform.errorhandling.model;

import org.springframework.http.HttpStatus;
import org.springframework.util.CollectionUtils;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.dto.ErrorInfoDetailDTO;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Фабрика {@link ErrorEntry}, штампующая имя домена (сервиса) на каждом вызове.
 *
 * <h2>Источник имени домена</h2>
 * <p>Имя сервиса резолвится через {@link Supplier Supplier&lt;String&gt;}, переданный в
 * конструктор. На каждый вызов {@code create*()} фабрика однократно вызывает
 * {@link Supplier#get()} и подставляет полученное значение:
 * <ul>
 *   <li>в поле {@link ErrorEntry#getDomain() ErrorEntry.domain} (top-level — имя текущего сервиса);</li>
 *   <li>в поле {@code domain} self-{@link ErrorInfoDetail}, который добавляется первым,
 *       если caller не передал собственный {@link ErrorInfoDetail}.</li>
 * </ul>
 *
 * <p>В стартере supplier поставляется модулем {@code platform-tracing-spring-boot-autoconfigure}
 * ({@code platformLocalServiceNameProvider}) и резолвит {@code spring.application.name}
 * либо имя из OpenTelemetry-конфигурации. Контракт supplier'а — стандартный
 * {@link java.util.function.Supplier}; web-error-model не зависит от Tracing-стартера на этапе
 * компиляции.
 *
 * <h2>Обработка ошибок supplier'а</h2>
 * <p>{@link #resolveDomain()} устойчив к любым нештатным исходам supplier'а:
 * {@code null}, blank-строка или брошенное исключение приводят к подстановке
 * {@link ErrorInfoDetailDTO#DOMAIN_UNSPECIFIED} (пустая строка). Такая семантика
 * не позволяет багу tracing-стартера повалить error-handling pipeline.
 *
 * <h2>preserve-upstream domain</h2>
 * <p>{@link #createWithDetails(HttpStatus, String, String, List)} <b>не переписывает</b>
 * существующий {@link ErrorInfoDetail} в переданном списке: если caller (например, парсер
 * upstream-ответа) уже подставил {@code domain} реального источника ошибки —
 * фабрика сохраняет его как есть и не добавляет header со своим именем.
 */
public final class ErrorEntryFactory {

    /**
     * Поставщик имени локального сервиса для top-level {@link ErrorEntry#getDomain()}
     * и self-{@link ErrorInfoDetail}.
     */
    private final Supplier<String> domainSupplier;

    /**
     * Конструктор для прямых пользователей фабрики (CLI/batch/tests без Spring DI).
     * Внутри оборачивается в {@code () -> domain}, поэтому семантика идентична
     * {@link #ErrorEntryFactory(Supplier)} с константным supplier'ом.
     *
     * @param domain имя сервиса; {@code null} нормализуется в {@link ErrorInfoDetailDTO#DOMAIN_UNSPECIFIED}
     */
    public ErrorEntryFactory(String domain) {
        // Делегируем в Supplier-конструктор: resolveDomain() сам обработает null/blank
        // через DOMAIN_UNSPECIFIED, поэтому здесь дополнительная нормализация не нужна.
        this(() -> domain);
    }

    /**
     * Основной конструктор: фабрика берёт имя домена из supplier'а на каждом вызове.
     *
     * @param domainSupplier поставщик имени сервиса; не {@code null} (но supplier
     *                       сам может возвращать {@code null}/blank или бросать
     *                       исключение — это безопасно обрабатывается)
     */
    public ErrorEntryFactory(Supplier<String> domainSupplier) {
        this.domainSupplier = Objects.requireNonNull(domainSupplier,
                "domainSupplier must not be null — use the String-constructor for static values");
    }

    public ErrorEntry create(HttpStatus status,
                             String errorCode,
                             String message) {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(errorCode, "errorCode must not be null");

        String resolved = resolveDomain();
        return ErrorEntry.builder()
                .httpStatus(status)
                .errorCode(errorCode)
                .message(message)
                .domain(resolved)
                .details(List.of(buildSelfErrorInfoDetail(errorCode, resolved)))
                .build();
    }

    public ErrorEntry createWithDetails(HttpStatus status,
                                        String errorCode,
                                        String message,
                                        List<ErrorDetail> details) {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(errorCode, "errorCode must not be null");

        String resolved = resolveDomain();

        List<ErrorDetail> result;
        if (CollectionUtils.isEmpty(details)) {
            result = List.of(buildSelfErrorInfoDetail(errorCode, resolved));
        } else if (containsErrorInfoDetail(details)) {
            // preserve-upstream: caller уже подставил свой ErrorInfoDetail (например,
            // распарсенный из тела upstream-ответа) — не перезаписываем его и не добавляем
            // собственный header.
            result = List.copyOf(details);
        } else {
            List<ErrorDetail> tmp = new ArrayList<>(details.size() + 1);
            tmp.add(buildSelfErrorInfoDetail(errorCode, resolved));
            tmp.addAll(details);

            result = Collections.unmodifiableList(tmp);
        }

        return ErrorEntry.builder()
                .httpStatus(status)
                .errorCode(errorCode)
                .message(message)
                .domain(resolved)
                .details(result)
                .build();
    }

    /**
     * Возвращает текущее имя домена (для совместимости с прежним публичным API).
     *
     * <p>Каждый вызов резолвит supplier заново, поэтому значение отражает текущее
     * состояние источника. Эквивалентно {@link #resolveDomain()}.
     */
    public String getDomain() {
        return resolveDomain();
    }

    /**
     * Безопасное разрешение имени домена.
     *
     * <ul>
     *   <li>supplier бросил исключение → {@link ErrorInfoDetailDTO#DOMAIN_UNSPECIFIED};</li>
     *   <li>supplier вернул {@code null} → {@link ErrorInfoDetailDTO#DOMAIN_UNSPECIFIED};</li>
     *   <li>supplier вернул blank-строку → {@link ErrorInfoDetailDTO#DOMAIN_UNSPECIFIED};</li>
     *   <li>иначе — значение supplier'а как есть.</li>
     * </ul>
     */
    private String resolveDomain() {
        String value;
        try {
            value = domainSupplier.get();
        } catch (RuntimeException ignored) {
            // Намеренно проглатываем: supplier — внешняя зависимость, и его баг не должен
            // ломать pipeline error-handling. SLF4J в web-error-model не подключён как
            // обязательная зависимость; адаптер из error-handling-core при необходимости
            // может логировать на уровне DEBUG со своей стороны.
            return ErrorInfoDetailDTO.DOMAIN_UNSPECIFIED;
        }
        if (value == null || value.isBlank()) {
            return ErrorInfoDetailDTO.DOMAIN_UNSPECIFIED;
        }
        return value;
    }

    private static ErrorInfoDetail buildSelfErrorInfoDetail(String errorCode, String resolvedDomain) {
        return ErrorInfoDetail.builder()
                .reason(errorCode)
                .domain(resolvedDomain)
                .build();
    }

    private static boolean containsErrorInfoDetail(List<ErrorDetail> details) {
        for (ErrorDetail d : details) {
            if (d instanceof ErrorInfoDetail) {
                return true;
            }
        }

        return false;
    }
}
