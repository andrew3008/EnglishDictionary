package space.br1440.platform.errorhandling.model.mapping;

import lombok.experimental.UtilityClass;
import space.br1440.platform.errorhandling.model.error_detail.dto.ErrorInfoDetailDTO;

@UtilityClass
final class ErrorInfoNormalizer {

    public static String reasonOrUnspecified(String reason) {
        return nonBlank(reason, ErrorInfoDetailDTO.REASON_UNSPECIFIED);
    }

    public static String domainOrUnspecified(String domain) {
        return nonBlank(domain, ErrorInfoDetailDTO.DOMAIN_UNSPECIFIED);
    }

    private static String nonBlank(String value, String fallback) {
        return (value != null && !value.isBlank()) ? value : fallback;
    }
}
