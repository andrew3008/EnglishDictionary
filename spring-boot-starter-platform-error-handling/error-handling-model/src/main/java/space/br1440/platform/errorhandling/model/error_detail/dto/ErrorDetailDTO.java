package space.br1440.platform.errorhandling.model.error_detail.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Полиморфный контракт элементов {@code details[]} в теле ошибки (wire layer).
 *
 * <p><b>Jackson:</b> дискриминатор {@code type} задаётся явно через {@link JsonTypeInfo} / {@link JsonSubTypes}
 * (вариант A из руководства платформы). Значения строк — часть публичного контракта API; переименование —
 * согласованное breaking change.
 *
 * <p><b>{@code sealed}:</b> замкнутый набор подтипов в этом модуле; компилятор проверяет исчерпывающую
 * обработку в {@code switch} по {@link ErrorDetailDTO} там, где это применимо.
 *
 * <p>React UI выполняет десериализацию на основе поля {@code type}:
 * <pre>{@code
 * switch (detail.type) {
 *   case 'BAD_REQUEST':
 *   case 'PRECONDITION_FAILURE':
 *   case 'ERROR_INFO':
 *   case 'RESOURCE_INFO':
 *   case 'HELP':
 *   case 'RETRY_INFO':
 *   case 'REQUEST_INFO':
 *   case 'CUSTOM':
 * }
 * }</pre>
 *
 * <p><b>Retry-информация</b> передаётся отдельным detail-типом {@code RETRY_INFO}
 * (см. {@link RetryInfoDetailDTO}). HTTP-заголовок {@code Retry-After} может дополнительно
 * использоваться транспортным уровнем.
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        property = "type",
        visible = true
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = BadRequestDetailDTO.class, name = "BAD_REQUEST"),
        @JsonSubTypes.Type(value = PreconditionFailureDetailDTO.class, name = "PRECONDITION_FAILURE"),
        @JsonSubTypes.Type(value = ErrorInfoDetailDTO.class, name = "ERROR_INFO"),
        @JsonSubTypes.Type(value = ResourceInfoDetailDTO.class, name = "RESOURCE_INFO"),
        @JsonSubTypes.Type(value = HelpDetailDTO.class, name = "HELP"),
        @JsonSubTypes.Type(value = RetryInfoDetailDTO.class, name = "RETRY_INFO"),
        @JsonSubTypes.Type(value = RequestInfoDetailDTO.class, name = "REQUEST_INFO"),
        @JsonSubTypes.Type(value = CustomErrorDetailDTO.class, name = "CUSTOM"),
})
public sealed interface ErrorDetailDTO permits BadRequestDetailDTO,
                                               PreconditionFailureDetailDTO,
                                               ErrorInfoDetailDTO,
                                               ResourceInfoDetailDTO,
                                               HelpDetailDTO,
                                               RetryInfoDetailDTO,
                                               RequestInfoDetailDTO,
                                               CustomErrorDetailDTO {
}
