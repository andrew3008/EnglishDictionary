package space.br1440.platform.errorhandling.model.mapping;

import space.br1440.platform.errorhandling.model.error_detail.*;
import space.br1440.platform.errorhandling.model.error_detail.dto.*;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ErrorDetailToDtoMapper {

    ErrorDetailDTO toDto(ErrorDetail detail) {
        return switch (detail) {
            case null -> null;
            case BadRequestDetail d -> toDto(d);
            case PreconditionFailureDetail d -> toDto(d);
            case ErrorInfoDetail d -> toDto(d);
            case ResourceInfoDetail d -> toDto(d);
            case HelpDetail d -> toDto(d);
            case RetryInfoDetail d -> toDto(d);
            case RequestInfoDetail d -> toDto(d);
            case CustomErrorDetail d -> toDto(d);
        };
    }

    private BadRequestDetailDTO toDto(BadRequestDetail d) {
        List<BadRequestDetailDTO.FieldViolation> fieldViolations =
                d.getFieldViolations()
                        .stream()
                        .map(ErrorDetailToDtoMapper::toFieldViolationDto)
                        .toList();

        return BadRequestDetailDTO.builder()
                .fieldViolations(fieldViolations)
                .build();
    }

    private PreconditionFailureDetailDTO toDto(PreconditionFailureDetail d) {
        List<PreconditionFailureDetailDTO.Violation> violations =
                d.getViolations()
                        .stream()
                        .map(ErrorDetailToDtoMapper::toViolationDto)
                        .toList();

        return PreconditionFailureDetailDTO.builder()
                .violations(violations)
                .build();
    }

    private ErrorInfoDetailDTO toDto(ErrorInfoDetail d) {
        return ErrorInfoDetailDTO.builder()
                .reason(ErrorInfoNormalizer.reasonOrUnspecified(d.getReason()))
                .domain(ErrorInfoNormalizer.domainOrUnspecified(d.getDomain()))
                .metadata(toUnmodifiableMetadata(d.getClientMetadata()))
                .build();
    }

    private ResourceInfoDetailDTO toDto(ResourceInfoDetail d) {
        return ResourceInfoDetailDTO.builder()
                .resourceType(d.getResourceType())
                .resourceName(d.getResourceName())
                .description(d.getDescription())
                .build();
    }

    private HelpDetailDTO toDto(HelpDetail d) {
        List<HelpDetailDTO.Link> links =
                d.getLinks()
                        .stream()
                        .map(ErrorDetailToDtoMapper::toLinkDto)
                        .toList();

        return HelpDetailDTO.builder()
                .links(links)
                .build();
    }

    private RetryInfoDetailDTO toDto(RetryInfoDetail d) {
        return RetryInfoDetailDTO.builder()
                .retryDelay(d.getRetryDelay() == null ? null : d.getRetryDelay().toString())
                .build();
    }

    private RequestInfoDetailDTO toDto(RequestInfoDetail d) {
        return RequestInfoDetailDTO.builder()
                .requestId(d.getRequestId())
                .servingData(d.getServingData())
                .build();
    }

    private CustomErrorDetailDTO toDto(CustomErrorDetail d) {
        return CustomErrorDetailDTO.builder()
                .detailType(d.detailType())
                .metadata(d.wireMetadata())
                .build();
    }

    private static BadRequestDetailDTO.FieldViolation toFieldViolationDto(BadRequestDetail.FieldViolation v) {
        return BadRequestDetailDTO.FieldViolation.builder()
                .field(v.getField())
                .reason(v.getReason())
                .description(v.getDescription())
                .build();
    }

    private static PreconditionFailureDetailDTO.Violation toViolationDto(PreconditionFailureDetail.Violation v) {
        return PreconditionFailureDetailDTO.Violation.builder()
                .type(v.getType())
                .subject(v.getSubject())
                .description(v.getDescription())
                .build();
    }

    private static HelpDetailDTO.Link toLinkDto(HelpDetail.Link l) {
        return HelpDetailDTO.Link.builder()
                .description(l.getDescription())
                .url(l.getUrl())
                .build();
    }

    private static Map<String, String> toUnmodifiableMetadata(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }

        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
