package space.br1440.platform.errorhandling.model.mapping;

import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.RequestContext;
import space.br1440.platform.errorhandling.model.dto.ErrorEntryDTO;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.dto.ErrorDetailDTO;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ErrorEntryToDtoMapper {

    private final ErrorDetailToDtoMapper errorDetailToDtoMapper = new ErrorDetailToDtoMapper();

    public ErrorEntryDTO toDto(ErrorEntry entry) {
        if (entry == null) {
            return null;
        }

        validateInvariants(entry);

        return mapScalars(entry).toBuilder()
                .metadata(mergeTopMetadata(entry))
                .clearDetails()
                .details(mapDetails(entry))
                .build();
    }

    private static void validateInvariants(ErrorEntry entry) {
        entry.getHttpStatus();
    }

    private static ErrorEntryDTO mapScalars(ErrorEntry entry) {
        ErrorEntryDTO.ErrorEntryDTOBuilder builder = ErrorEntryDTO.builder()
                .httpCode(entry.getHttpStatus().value())
                .errorCode(entry.getErrorCode())
                .message(entry.getMessage())
                .reason(entry.getReason())
                .domain(entry.getDomain())
                .requestURI(entry.getRequestURI());

        // RFC 9457 §3.1: type явно копируем только если задан в entry.
        // Иначе ErrorEntryDTO.Builder.Default подставит "about:blank" (RFC 9457 §4.1).
        if (entry.getType() != null) {
            builder.type(entry.getType());
        }

        return builder.build();
    }

    private static Map<String, String> mergeTopMetadata(ErrorEntry entry) {
        Map<String, String> merged = new LinkedHashMap<>(extractTopClientMetadata(entry));

        RequestContext requestContext = entry.getRequestContext();
        if (requestContext != null) {
            requestContext.toMap().forEach(merged::putIfAbsent);
        }

        return merged.isEmpty() ? Map.of() : Collections.unmodifiableMap(merged);
    }

    private static Map<String, String> extractTopClientMetadata(ErrorEntry entry) {
        for (ErrorDetail detail : entry.getDetails()) {
            if (detail instanceof ErrorInfoDetail info) {
                return info.getClientMetadata();
            }
        }

        return Collections.emptyMap();
    }

    /**
     * Сборка DTO {@code details[]} с пропуском «top header» {@link ErrorInfoDetail}.
     *
     * <p><b>Контракт.</b> По конвенции модели первый {@link ErrorInfoDetail} в
     * {@link ErrorEntry#getDetails()} — это «own/header» текущего сервиса
     * (его {@code reason} == {@code errorCode}, {@code domain} == домен сервиса,
     * {@code clientMetadata} == top-metadata). Эта информация уже доступна в DTO
     * на top-level в полях {@code errorCode} / {@code domain} / {@code metadata},
     * поэтому в {@code details[]} он намеренно <b>не экспортируется</b> —
     * в противном случае один и тот же {@code ErrorInfo} оказывался бы на проводе
     * дважды (см. AIP-193: {@code google.rpc.ErrorInfo} существует только в
     * {@code Status.details}, без top-level дубля).
     *
     * <p>Все остальные элементы (последующие cause-{@code ERROR_INFO},
     * {@code BAD_REQUEST}, {@code RETRY_INFO}, {@code RESOURCE_INFO}, {@code HELP},
     * {@code PRECONDITION_FAILURE}, {@code CUSTOM}) сохраняются в исходном порядке.
     */
    private List<ErrorDetailDTO> mapDetails(ErrorEntry entry) {
        if (entry.getDetails().isEmpty()) {
            return Collections.emptyList();
        }

        List<ErrorDetailDTO> result = new ArrayList<>(entry.getDetails().size());
        boolean topHeaderSkipped = false;
        for (ErrorDetail detail : entry.getDetails()) {
            if (!topHeaderSkipped && detail instanceof ErrorInfoDetail) {
                topHeaderSkipped = true;
                continue;
            }
            ErrorDetailDTO dto = errorDetailToDtoMapper.toDto(detail);
            if (dto != null) {
                result.add(dto);
            }
        }

        return Collections.unmodifiableList(result);
    }
}
