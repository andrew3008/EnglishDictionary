package space.br1440.platform.errorhandling.model.mapping;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.RequestContext;
import space.br1440.platform.errorhandling.model.dto.ErrorEntryDTO;
import space.br1440.platform.errorhandling.model.error_detail.BadRequestDetail;
import space.br1440.platform.errorhandling.model.error_detail.CustomErrorDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.HelpDetail;
import space.br1440.platform.errorhandling.model.error_detail.PreconditionFailureDetail;
import space.br1440.platform.errorhandling.model.error_detail.ResourceInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.dto.BadRequestDetailDTO;
import space.br1440.platform.errorhandling.model.error_detail.dto.CustomErrorDetailDTO;
import space.br1440.platform.errorhandling.model.error_detail.dto.ErrorDetailDTO;
import space.br1440.platform.errorhandling.model.error_detail.dto.ErrorInfoDetailDTO;
import space.br1440.platform.errorhandling.model.error_detail.dto.HelpDetailDTO;
import space.br1440.platform.errorhandling.model.error_detail.dto.PreconditionFailureDetailDTO;
import space.br1440.platform.errorhandling.model.error_detail.dto.ResourceInfoDetailDTO;
import space.br1440.platform.errorhandling.model.error_detail.dto.RetryInfoDetailDTO;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static space.br1440.platform.errorhandling.model.error_detail.dto.ErrorInfoDetailDTO.REASON_UNSPECIFIED;

/**
 * Тесты единого публичного фасада маппинга {@link ErrorEntryToDtoMapper}.
 *
 * <p>Структура копирует pipeline {@link ErrorEntryToDtoMapper#toDto(ErrorEntry)}:
 * <ol>
 *   <li>{@link Invariants} -- шаг 1 (validate);</li>
 *   <li>{@link Scalars} -- шаг 2 (mapScalars);</li>
 *   <li>{@link TopLevelMetadata} -- шаг 3 (mergeTopMetadata);</li>
 *   <li>{@link Details} -- шаг 4 (mapDetails) и подтипы {@link ErrorDetailDTO};</li>
 *   <li>{@link Immutability} -- инвариант о неизменяемости коллекций в DTO.</li>
 * </ol>
 */
@DisplayName("ErrorEntryToDtoMapper")
class ErrorEntryToDtoMapperTest {

    private final ErrorEntryToDtoMapper mapper = new ErrorEntryToDtoMapper();

    @Nested
    @DisplayName("Шаг 1 -- инварианты (validateInvariants)")
    class Invariants {

        @Test
        @DisplayName("toDto(null) -> null")
        void nullEntry_returnsNull() {
            assertThat(mapper.toDto(null)).isNull();
        }

        @Test
        @DisplayName("Без httpStatus -- IllegalStateException из ErrorEntry.getHttpStatus()")
        void toDto_withoutHttpStatus_throwsIllegalState() {
            ErrorEntry entry = ErrorEntry.builder()
                    .errorCode("X-0001")
                    .message("no status set")
                    .build();

            assertThatThrownBy(() -> mapper.toDto(entry))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("httpStatus");
        }
    }

    @Nested
    @DisplayName("Шаг 2 -- скалярные поля")
    class Scalars {

        @Test
        @DisplayName("Все скалярные поля копируются 1-в-1 из ErrorEntry в DTO")
        void populatesAllScalarFields_fromErrorEntry() {
            ErrorEntry entry = ErrorEntry.builder()
                    .httpStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                    .errorCode("CALCULATION_PERSISTENCE")
                    .message("Failed to persist calculation result for calc-42")
                    .reason("External service HarborRegistry returned an error: Unexpected response status: 500")
                    .domain("ballistics-service")
                    .requestURI("/api/calc")
                    .build();

            ErrorEntryDTO dto = mapper.toDto(entry);

            assertThat(dto.getHttpCode()).isEqualTo(500);
            assertThat(dto.getErrorCode()).isEqualTo("CALCULATION_PERSISTENCE");
            assertThat(dto.getMessage())
                    .isEqualTo("Failed to persist calculation result for calc-42")
                    .doesNotContain("[errorCode=");
            assertThat(dto.getReason())
                    .isEqualTo("External service HarborRegistry returned an error: Unexpected response status: 500");
            assertThat(dto.getDomain()).isEqualTo("ballistics-service");
            assertThat(dto.getRequestURI()).isEqualTo("/api/calc");
        }

        @Test
        @DisplayName("RFC 9457 §4.1: при отсутствии type в entry, DTO получает 'about:blank'")
        void typeDefaultsToAboutBlank_whenEntryHasNoType() {
            ErrorEntry entry = ErrorEntry.builder()
                    .httpStatus(HttpStatus.BAD_REQUEST)
                    .errorCode("X")
                    .message("m")
                    .build();

            ErrorEntryDTO dto = mapper.toDto(entry);

            assertThat(dto.getType()).isEqualTo(ErrorEntryDTO.TYPE_DEFAULT);
            assertThat(dto.getType()).isEqualTo("about:blank");
        }

        @Test
        @DisplayName("RFC 9457 §3.1: пользовательский type-URI копируется в DTO как есть")
        void customType_copiedToDto() {
            String runbookUri = "https://errors.example.com/oauth2/invalid-token";
            ErrorEntry entry = ErrorEntry.builder()
                    .httpStatus(HttpStatus.UNAUTHORIZED)
                    .errorCode("OAUTH2_INVALID_TOKEN")
                    .type(runbookUri)
                    .message("m")
                    .build();

            ErrorEntryDTO dto = mapper.toDto(entry);

            assertThat(dto.getType()).isEqualTo(runbookUri);
        }

        @Test
        @DisplayName("Минимальный entry -- metadata пустая, details пустой")
        void metadataAndDetails_areEmptyByDefault_forMinimalEntry() {
            ErrorEntry entry = ErrorEntry.builder()
                    .httpStatus(HttpStatus.BAD_REQUEST)
                    .errorCode("X")
                    .message("m")
                    .build();

            ErrorEntryDTO dto = mapper.toDto(entry);

            assertThat(dto.getMetadata()).isEmpty();
            assertThat(dto.getDetails()).isNullOrEmpty();
        }
    }

    @Nested
    @DisplayName("Шаг 3 -- top-level metadata (clientMetadata + RequestContext, putIfAbsent)")
    class TopLevelMetadata {

        @Test
        @DisplayName("merge: clientMetadata top-ErrorInfo + RequestContext")
        void mergesClientMetadata_andRequestContext() {
            Map<String, String> topClientMetadata = new LinkedHashMap<>();
            topClientMetadata.put("violationCount", "1");

            ErrorInfoDetail topInfo = ErrorInfoDetail.builder()
                    .reason("BUSINESS_VALIDATION")
                    .domain("order-service")
                    .clientMetadata(topClientMetadata)
                    .build();

            ErrorEntry entry = ErrorEntry.builder()
                    .httpStatus(HttpStatus.BAD_REQUEST)
                    .errorCode("BUSINESS_VALIDATION")
                    .message("Validation failed")
                    .requestContext(RequestContext.builder()
                            .correlationId("abc-123")
                            .traceId("trace-xyz")
                            .build())
                    .details(List.of(topInfo))
                    .build();

            ErrorEntryDTO dto = mapper.toDto(entry);

            assertThat(dto.getMetadata())
                    .containsEntry("violationCount", "1")
                    .containsEntry("correlationId", "abc-123")
                    .containsEntry("traceId", "trace-xyz");
        }

        @Test
        @DisplayName("Коллизия ключей: user (clientMetadata) перекрывает RequestContext (putIfAbsent)")
        void userClientMetadata_takesPrecedence_overRequestContext_onKeyCollision() {
            Map<String, String> topClientMetadata = new LinkedHashMap<>();
            topClientMetadata.put("correlationId", "user-supplied");
            topClientMetadata.put("violationCount", "1");

            ErrorInfoDetail topInfo = ErrorInfoDetail.builder()
                    .reason("BUSINESS_VALIDATION")
                    .domain("order-service")
                    .clientMetadata(topClientMetadata)
                    .build();

            ErrorEntry entry = ErrorEntry.builder()
                    .httpStatus(HttpStatus.BAD_REQUEST)
                    .errorCode("BUSINESS_VALIDATION")
                    .message("Validation failed")
                    .requestContext(RequestContext.builder()
                            .correlationId("system-generated")
                            .traceId("trace-xyz")
                            .build())
                    .details(List.of(topInfo))
                    .build();

            ErrorEntryDTO dto = mapper.toDto(entry);

            assertThat(dto.getMetadata())
                    .containsEntry("correlationId", "user-supplied")
                    .containsEntry("traceId", "trace-xyz")
                    .containsEntry("violationCount", "1");
        }

        @Test
        @DisplayName("Без top-ErrorInfo и без RequestContext -- пустая metadata")
        void noTopErrorInfo_andNoRequestContext_resultInEmptyMetadata() {
            ErrorEntry entry = ErrorEntry.builder()
                    .httpStatus(HttpStatus.BAD_REQUEST)
                    .errorCode("X")
                    .message("m")
                    .build();

            ErrorEntryDTO dto = mapper.toDto(entry);

            assertThat(dto.getMetadata()).isEmpty();
        }

        @Test
        @DisplayName("Только RequestContext -- метаданные содержат только инфра-ключи")
        void noTopErrorInfo_butRequestContext_present_metadataContainsContextOnly() {
            ErrorEntry entry = ErrorEntry.builder()
                    .httpStatus(HttpStatus.BAD_REQUEST)
                    .errorCode("X")
                    .message("m")
                    .requestContext(RequestContext.builder().correlationId("corr-1").build())
                    .build();

            ErrorEntryDTO dto = mapper.toDto(entry);

            assertThat(dto.getMetadata()).containsEntry("correlationId", "corr-1");
        }
    }

    @Nested
    @DisplayName("Шаг 4 -- details[] (cause-chain ERROR_INFO; top header не экспортируется)")
    class Details {

        @Test
        @DisplayName("Top ErrorInfoDetail пропускается; cause ERROR_INFO сохраняется")
        void topErrorInfoIsSkipped_causeErrorInfoIsKept() {
            ErrorInfoDetail topHeader = ErrorInfoDetail.builder()
                    .reason("FIRST")
                    .domain("d1")
                    .build();

            ErrorInfoDetail cause = ErrorInfoDetail.builder()
                    .reason("SECOND")
                    .domain("d2")
                    .build();

            ErrorEntry entry = ErrorEntry.builder()
                    .httpStatus(HttpStatus.BAD_REQUEST)
                    .errorCode("X-001")
                    .message("m")
                    .details(List.of(topHeader, cause))
                    .build();

            ErrorEntryDTO dto = mapper.toDto(entry);

            assertThat(dto.getDetails()).hasSize(1);
            assertThat(((ErrorInfoDetailDTO) dto.getDetails().getFirst()).getReason())
                    .isEqualTo("SECOND");
        }

        @Test
        @DisplayName("Сохраняется исходный порядок остальных деталей: Help + cause-ERROR_INFO (top пропущен)")
        void topErrorInfoSkipped_thenOtherTypes_thenCauseErrorInfo_orderPreserved() {
            ErrorInfoDetail topHeader = ErrorInfoDetail.builder()
                    .reason("top")
                    .domain("dom")
                    .build();

            HelpDetail help = HelpDetail.of("https://docs.example.com", "0001");

            ErrorInfoDetail cause = ErrorInfoDetail.builder()
                    .reason("nested")
                    .domain("x")
                    .build();

            ErrorEntry entry = ErrorEntry.builder()
                    .httpStatus(HttpStatus.BAD_REQUEST)
                    .errorCode("X-002")
                    .message("m")
                    .details(List.of(topHeader, help, cause))
                    .build();

            ErrorEntryDTO dto = mapper.toDto(entry);

            List<ErrorDetailDTO> details = dto.getDetails();
            assertThat(details).hasSize(2);
            assertThat(details.get(0)).isInstanceOf(HelpDetailDTO.class);
            assertThat(details.get(1)).isInstanceOf(ErrorInfoDetailDTO.class);
            assertThat(((ErrorInfoDetailDTO) details.get(1)).getReason()).isEqualTo("nested");
        }

        @Test
        @DisplayName("Одиночное исключение без cause -- top header пропущен, details[] пустой")
        void singleException_withoutCause_exposesEmptyDetails() {
            ErrorInfoDetail topHeader = ErrorInfoDetail.builder()
                    .reason("BUSINESS_VALIDATION")
                    .domain("order-service")
                    .build();

            ErrorEntry entry = ErrorEntry.builder()
                    .httpStatus(HttpStatus.BAD_REQUEST)
                    .errorCode("BUSINESS_VALIDATION")
                    .message("Validation failed")
                    .details(List.of(topHeader))
                    .build();

            ErrorEntryDTO dto = mapper.toDto(entry);

            assertThat(dto.getReason()).isNull();
            assertThat(dto.getDetails()).isEmpty();
        }

        @Test
        @DisplayName("BadRequestDetail -> BadRequestDetailDTO")
        void badRequestDetail_isMapped() {
            BadRequestDetail d = BadRequestDetail.builder()
                    .addFieldViolation("email", "INVALID", "must be email")
                    .build();

            ErrorEntryDTO dto = mapper.toDto(entryWithDetail(d));

            assertThat(dto.getDetails()).hasSize(1);
            BadRequestDetailDTO mapped = (BadRequestDetailDTO) dto.getDetails().getFirst();
            assertThat(mapped.getFieldViolations()).hasSize(1);
            BadRequestDetailDTO.FieldViolation mappedFv = mapped.getFieldViolations().getFirst();
            assertThat(mappedFv.getField()).isEqualTo("email");
            assertThat(mappedFv.getReason()).isEqualTo("INVALID");
            assertThat(mappedFv.getDescription()).isEqualTo("must be email");
        }

        @Test
        @DisplayName("PreconditionFailureDetail -> PreconditionFailureDetailDTO")
        void preconditionFailureDetail_isMapped() {
            PreconditionFailureDetail d = PreconditionFailureDetail.builder()
                    .addViolation("ACCOUNT_BLOCKED", "account/42", "Account is suspended")
                    .build();

            ErrorEntryDTO dto = mapper.toDto(entryWithDetail(d));

            PreconditionFailureDetailDTO mapped = (PreconditionFailureDetailDTO) dto.getDetails().getFirst();
            assertThat(mapped.getViolations()).hasSize(1);
            PreconditionFailureDetailDTO.Violation v = mapped.getViolations().getFirst();
            assertThat(v.getType()).isEqualTo("ACCOUNT_BLOCKED");
            assertThat(v.getSubject()).isEqualTo("account/42");
            assertThat(v.getDescription()).isEqualTo("Account is suspended");
        }

        @Test
        @DisplayName("Cause-ErrorInfoDetail с blank reason -- подменяется на REASON_UNSPECIFIED")
        void errorInfoDetail_defaultsReasonWhenBlank_usesMetadata() {
            ErrorInfoDetail cause = ErrorInfoDetail.builder()
                    .reason("   ")
                    .domain("my.domain")
                    .clientMetadata(Map.of("k", "v"))
                    .build();

            ErrorEntryDTO dto = mapper.toDto(entryWithTopHeaderAndCauseInfo(cause));

            ErrorInfoDetailDTO mapped = (ErrorInfoDetailDTO) dto.getDetails().getFirst();
            assertThat(mapped.getReason()).isEqualTo(REASON_UNSPECIFIED);
            assertThat(mapped.getDomain()).isEqualTo("my.domain");
            assertThat(mapped.getMetadata()).containsEntry("k", "v");
        }

        @Test
        @DisplayName("Cause-ErrorInfoDetail с непустым reason -- сохраняется")
        void errorInfoDetail_preservesExplicitReason() {
            ErrorInfoDetail cause = ErrorInfoDetail.builder()
                    .reason("RATE_LIMIT_EXCEEDED")
                    .domain("billing.platform.br1440.space")
                    .build();

            ErrorEntryDTO dto = mapper.toDto(entryWithTopHeaderAndCauseInfo(cause));

            ErrorInfoDetailDTO mapped = (ErrorInfoDetailDTO) dto.getDetails().getFirst();
            assertThat(mapped.getReason()).isEqualTo("RATE_LIMIT_EXCEEDED");
            assertThat(mapped.getDomain()).isEqualTo("billing.platform.br1440.space");
        }

        @Test
        @DisplayName("Cause-ErrorInfoDetail.clientMetadata -> ErrorInfoDetailDTO.metadata")
        void errorInfoDetail_preservesClientMetadata_inDto() {
            LinkedHashMap<String, String> meta = new LinkedHashMap<>();
            meta.put("serviceName", "HarborRegistry");
            meta.put("errorDescription", "Unexpected response status: 500");
            ErrorInfoDetail cause = ErrorInfoDetail.builder()
                    .reason("EXTERNAL_SERVICE")
                    .domain("ballistics-service")
                    .clientMetadata(meta)
                    .build();

            ErrorEntryDTO dto = mapper.toDto(entryWithTopHeaderAndCauseInfo(cause));

            ErrorInfoDetailDTO mapped = (ErrorInfoDetailDTO) dto.getDetails().getFirst();
            assertThat(mapped.getMetadata())
                    .containsEntry("serviceName", "HarborRegistry")
                    .containsEntry("errorDescription", "Unexpected response status: 500");
        }

        @Test
        @DisplayName("ResourceInfoDetail -> ResourceInfoDetailDTO")
        void resourceInfoDetail_isMapped() {
            ResourceInfoDetail d = ResourceInfoDetail.of("order", "orders/1", "gone");

            ErrorEntryDTO dto = mapper.toDto(entryWithDetail(d));

            ResourceInfoDetailDTO mapped = (ResourceInfoDetailDTO) dto.getDetails().getFirst();
            assertThat(mapped.getResourceType()).isEqualTo("order");
            assertThat(mapped.getResourceName()).isEqualTo("orders/1");
            assertThat(mapped.getDescription()).isEqualTo("gone");
        }

        @Test
        @DisplayName("HelpDetail.of(url, code) -> HelpDetailDTO с одной ссылкой")
        void helpDetail_isMapped() {
            HelpDetail d = HelpDetail.of("https://docs.example.com/errors", "0420");

            ErrorEntryDTO dto = mapper.toDto(entryWithDetail(d));

            HelpDetailDTO mapped = (HelpDetailDTO) dto.getDetails().getFirst();
            assertThat(mapped.getLinks()).hasSize(1);
            HelpDetailDTO.Link link = mapped.getLinks().getFirst();
            assertThat(link.getUrl()).endsWith("/0420");
            assertThat(link.getDescription()).isNotBlank();
        }

        @Test
        @DisplayName("HelpDetail.addLink(desc, url) -- description и url сохраняются")
        void helpDetail_addLink_preservesDescriptionAndUrl() {
            HelpDetail d = HelpDetail.builder()
                    .addLink("Migration guide", "https://docs.example.com/migration/v2")
                    .build();

            ErrorEntryDTO dto = mapper.toDto(entryWithDetail(d));

            HelpDetailDTO mapped = (HelpDetailDTO) dto.getDetails().getFirst();
            assertThat(mapped.getLinks()).hasSize(1);
            HelpDetailDTO.Link link = mapped.getLinks().getFirst();
            assertThat(link.getDescription()).isEqualTo("Migration guide");
            assertThat(link.getUrl()).isEqualTo("https://docs.example.com/migration/v2");
        }

        @Test
        @DisplayName("RetryInfoDetail с Duration -- ISO-8601 в DTO")
        void retryInfoDetail_withDuration() {
            RetryInfoDetail d = new RetryInfoDetail(Duration.ofSeconds(30));

            ErrorEntryDTO dto = mapper.toDto(entryWithDetail(d));

            RetryInfoDetailDTO mapped = (RetryInfoDetailDTO) dto.getDetails().getFirst();
            assertThat(mapped.getRetryDelay()).isEqualTo("PT30S");
        }

        @Test
        @DisplayName("RetryInfoDetail без Duration -- retryDelay==null")
        void retryInfoDetail_nullDuration() {
            RetryInfoDetail d = new RetryInfoDetail();

            ErrorEntryDTO dto = mapper.toDto(entryWithDetail(d));

            RetryInfoDetailDTO mapped = (RetryInfoDetailDTO) dto.getDetails().getFirst();
            assertThat(mapped.getRetryDelay()).isNull();
        }

        @Test
        @DisplayName("CustomErrorDetail -- detailType + wireMetadata в DTO")
        void customErrorDetail_isMapped() {
            CustomErrorDetail d = new CustomErrorDetail() {
                @Override
                public String detailType() {
                    return "MY_VENDOR_HINT";
                }

                @Override
                public Map<String, String> wireMetadata() {
                    return Map.of("hint", "retryLater");
                }
            };

            ErrorEntryDTO dto = mapper.toDto(entryWithDetail(d));

            CustomErrorDetailDTO mapped = (CustomErrorDetailDTO) dto.getDetails().getFirst();
            assertThat(mapped.getDetailType()).isEqualTo("MY_VENDOR_HINT");
            assertThat(mapped.getMetadata()).containsEntry("hint", "retryLater");
        }
    }

    @Nested
    @DisplayName("Контракт неизменяемости коллекций в DTO")
    class Immutability {

        @Test
        @DisplayName("details[] неизменяем после assemble")
        void details_areUnmodifiable() {
            ErrorEntry entry = ErrorEntry.builder()
                    .httpStatus(HttpStatus.BAD_REQUEST)
                    .errorCode("X")
                    .message("m")
                    .details(List.of(ErrorInfoDetail.builder().build()))
                    .build();

            ErrorEntryDTO dto = mapper.toDto(entry);

            assertThatThrownBy(() -> dto.getDetails().add(null))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("metadata неизменяем при наличии содержимого")
        void metadata_isUnmodifiable_whenNonEmpty() {
            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("k", "v");
            ErrorInfoDetail topInfo = ErrorInfoDetail.builder()
                    .reason("X")
                    .domain("d")
                    .clientMetadata(meta)
                    .build();
            ErrorEntry entry = ErrorEntry.builder()
                    .httpStatus(HttpStatus.BAD_REQUEST)
                    .errorCode("X")
                    .message("m")
                    .details(List.of(topInfo))
                    .build();

            ErrorEntryDTO dto = mapper.toDto(entry);

            assertThatThrownBy(() -> dto.getMetadata().put("new", "x"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    /**
     * Хелпер для тестов cause-{@link ErrorInfoDetail}: добавляет фиктивный top-header
     * первой позицией (он будет пропущен маппером согласно конвенции «own/header»),
     * и тестируемый cause-detail — второй позицией.
     */
    private static ErrorEntry entryWithTopHeaderAndCauseInfo(ErrorInfoDetail cause) {
        ErrorInfoDetail topHeader = ErrorInfoDetail.builder()
                .reason("X")
                .domain("d")
                .build();
        return ErrorEntry.builder()
                .httpStatus(HttpStatus.BAD_REQUEST)
                .errorCode("X")
                .message("m")
                .details(List.of(topHeader, cause))
                .build();
    }

    private static ErrorEntry entryWithDetail(space.br1440.platform.errorhandling.model.error_detail.ErrorDetail detail) {
        return ErrorEntry.builder()
                .httpStatus(HttpStatus.BAD_REQUEST)
                .errorCode("X")
                .message("m")
                .details(List.of(detail))
                .build();
    }
}
