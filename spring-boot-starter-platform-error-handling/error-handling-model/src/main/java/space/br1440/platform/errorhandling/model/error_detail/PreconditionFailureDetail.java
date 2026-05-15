package space.br1440.platform.errorhandling.model.error_detail;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Доменная модель детали ошибки {@code PRECONDITION_FAILURE}
 * (см. <a href="https://cloud.google.com/apis/design/errors#standard_error_payloads">google.rpc.PreconditionFailure</a>).
 *
 * <h2>Дизайн</h2>
 * Иммутабельный value-объект: после {@link #builder()}{@code .build()} ни список нарушений,
 * ни сами {@link Violation} не могут быть изменены. Защита от случайной мутации
 * через {@link Singular @Singular} (Lombok оборачивает {@code violations} в
 * {@link java.util.Collections#unmodifiableList(List) unmodifiableList}) и {@link Value @Value}
 * (все поля {@code final}, без сеттеров).
 *
 * <h2>Эргономика</h2>
 * Кастомный shortcut {@code addViolation(type, subject, description)} в
 * {@link PreconditionFailureDetailBuilder} позволяет писать триплеты
 * без явного создания {@link Violation}:
 *
 * <pre>{@code
 * throw new SomeBusinessException(MY_ERROR_CODE, extra,
 *     List.of(PreconditionFailureDetail.builder()
 *         .addViolation("ACCOUNT_BLOCKED", "account/42", "Account is suspended")
 *         .addViolation("TERMS_NOT_ACCEPTED", "terms/v3", "Terms of service not accepted")
 *         .build()));
 * }</pre>
 */
@Value
@Builder(toBuilder = true)
public class PreconditionFailureDetail implements ErrorDetail {

    @NotNull
    @Singular
    List<Violation> violations;

    /**
     * Расширение Lombok-сгенерированного builder-а: shortcut для добавления
     * {@link Violation} тремя строковыми аргументами без явного построения объекта.
     *
     * <p>Lombok видит уже объявленный inner-класс {@code PreconditionFailureDetailBuilder}
     * и дописывает в него все остальные сгенерированные методы (включая {@code violation(Violation)},
     * {@code violations(Collection)}, {@code clearViolations()}, {@code build()}).
     */
    public static class PreconditionFailureDetailBuilder {

        /**
         * Добавляет {@link Violation} тремя строковыми аргументами.
         *
         * @param type        тип предусловия в {@code UPPER_SNAKE_CASE}, например {@code "ACCOUNT_BLOCKED"}
         * @param subject     субъект нарушения, например {@code "account/42"} или {@code "order/99"}
         * @param description описание для разработчика, например {@code "Account is suspended"}
         * @return тот же builder для fluent-цепочек
         */
        public PreconditionFailureDetailBuilder addViolation(String type, String subject, String description) {
            return violation(new Violation(type, subject, description));
        }
    }

    @Value
    public static class Violation {

        /**
         * Тип нарушенного предусловия в {@code UPPER_SNAKE_CASE}.
         * Например: {@code "TERMS_OF_SERVICE"}, {@code "STATUS_TRANSITION"}, {@code "QUOTA"}.
         */
        @NotNull
        String type;

        /**
         * Субъект, относительно которого нарушено предусловие.
         * Например: {@code "order/42"}, {@code "user/123"}.
         */
        @NotNull
        String subject;

        /**
         * Человеко-читаемое описание нарушения.
         */
        @NotNull
        String description;
    }
}
