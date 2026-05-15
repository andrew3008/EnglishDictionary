package space.br1440.platform.errorhandling.model.error_detail.dto;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ErrorDetailDTO — Jackson round-trip (sealed + @JsonTypeInfo)")
class ErrorDetailDtoJacksonContractTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Nested
    @DisplayName("BAD_REQUEST")
    class BadRequest {

        @Test
        void roundTrip_preservesFieldViolations() throws Exception {
            // given
            BadRequestDetailDTO original = BadRequestDetailDTO.builder()
                    .fieldViolation(BadRequestDetailDTO.FieldViolation.builder()
                            .field("email")
                            .reason("INVALID_FORMAT")
                            .description("must be a valid email")
                            .build())
                    .fieldViolation(BadRequestDetailDTO.FieldViolation.builder()
                            .field("age")
                            .reason("OUT_OF_RANGE")
                            .description("must be between 0 and 150")
                            .build())
                    .build();

            // when
            String json = mapper.writeValueAsString(original);
            ErrorDetailDTO deserialized = mapper.readValue(json, ErrorDetailDTO.class);

            // then
            assertThat(json).contains("\"type\":\"BAD_REQUEST\"");
            assertThat(deserialized).isInstanceOf(BadRequestDetailDTO.class);
            BadRequestDetailDTO result = (BadRequestDetailDTO) deserialized;
            assertThat(result.getFieldViolations()).hasSize(2);
            assertThat(result.getFieldViolations().get(0).getField()).isEqualTo("email");
            assertThat(result.getFieldViolations().get(1).getReason()).isEqualTo("OUT_OF_RANGE");
        }
    }

    @Nested
    @DisplayName("PRECONDITION_FAILURE")
    class PreconditionFailure {

        @Test
        void roundTrip_preservesViolations() throws Exception {
            // given
            PreconditionFailureDetailDTO original = PreconditionFailureDetailDTO.builder()
                    .violation(PreconditionFailureDetailDTO.Violation.builder()
                            .type("ACCOUNT_BLOCKED")
                            .subject("account/42")
                            .description("Account is suspended")
                            .build())
                    .build();

            // when
            String json = mapper.writeValueAsString(original);
            ErrorDetailDTO deserialized = mapper.readValue(json, ErrorDetailDTO.class);

            // then
            assertThat(json).contains("\"type\":\"PRECONDITION_FAILURE\"");
            assertThat(deserialized).isInstanceOf(PreconditionFailureDetailDTO.class);
            PreconditionFailureDetailDTO result = (PreconditionFailureDetailDTO) deserialized;
            assertThat(result.getViolations()).hasSize(1);
            assertThat(result.getViolations().getFirst().getType()).isEqualTo("ACCOUNT_BLOCKED");
        }
    }

    @Nested
    @DisplayName("ERROR_INFO")
    class ErrorInfo {

        @Test
        void roundTrip_preservesReasonDomainMetadata() throws Exception {
            // given
            ErrorInfoDetailDTO original = ErrorInfoDetailDTO.builder()
                    .reason("RATE_LIMIT_EXCEEDED")
                    .domain("billing.platform.br1440.space")
                    .metadata(Map.of("quotaType", "API_CALLS", "limit", "1000"))
                    .build();

            // when
            String json = mapper.writeValueAsString(original);
            ErrorDetailDTO deserialized = mapper.readValue(json, ErrorDetailDTO.class);

            // then
            assertThat(json).contains("\"type\":\"ERROR_INFO\"");
            assertThat(deserialized).isInstanceOf(ErrorInfoDetailDTO.class);
            ErrorInfoDetailDTO result = (ErrorInfoDetailDTO) deserialized;
            assertThat(result.getReason()).isEqualTo("RATE_LIMIT_EXCEEDED");
            assertThat(result.getDomain()).isEqualTo("billing.platform.br1440.space");
            assertThat(result.getMetadata())
                    .containsEntry("quotaType", "API_CALLS")
                    .containsEntry("limit", "1000");
        }
    }

    @Nested
    @DisplayName("RESOURCE_INFO")
    class ResourceInfo {

        @Test
        void roundTrip_preservesAllFields() throws Exception {
            // given
            ResourceInfoDetailDTO original = ResourceInfoDetailDTO.builder()
                    .resourceType("order")
                    .resourceName("orders/42")
                    .description("Order not found")
                    .build();

            // when
            String json = mapper.writeValueAsString(original);
            ErrorDetailDTO deserialized = mapper.readValue(json, ErrorDetailDTO.class);

            // then
            assertThat(json).contains("\"type\":\"RESOURCE_INFO\"");
            assertThat(deserialized).isInstanceOf(ResourceInfoDetailDTO.class);
            ResourceInfoDetailDTO result = (ResourceInfoDetailDTO) deserialized;
            assertThat(result.getResourceType()).isEqualTo("order");
            assertThat(result.getResourceName()).isEqualTo("orders/42");
        }
    }

    @Nested
    @DisplayName("HELP")
    class Help {

        @Test
        void roundTrip_preservesLinks() throws Exception {
            // given
            HelpDetailDTO original = HelpDetailDTO.builder()
                    .link(HelpDetailDTO.Link.builder()
                            .description("Error catalog")
                            .url("https://docs.example.com/errors/0001")
                            .build())
                    .build();

            // when
            String json = mapper.writeValueAsString(original);
            ErrorDetailDTO deserialized = mapper.readValue(json, ErrorDetailDTO.class);

            // then
            assertThat(json).contains("\"type\":\"HELP\"");
            assertThat(deserialized).isInstanceOf(HelpDetailDTO.class);
            HelpDetailDTO result = (HelpDetailDTO) deserialized;
            assertThat(result.getLinks()).hasSize(1);
            assertThat(result.getLinks().getFirst().getUrl()).isEqualTo("https://docs.example.com/errors/0001");
        }
    }

    @Nested
    @DisplayName("RETRY_INFO")
    class RetryInfo {

        @Test
        void roundTrip_withDelay() throws Exception {
            // given
            RetryInfoDetailDTO original = RetryInfoDetailDTO.builder()
                    .retryDelay("PT30S")
                    .build();

            // when
            String json = mapper.writeValueAsString(original);
            ErrorDetailDTO deserialized = mapper.readValue(json, ErrorDetailDTO.class);

            // then
            assertThat(json).contains("\"type\":\"RETRY_INFO\"");
            assertThat(deserialized).isInstanceOf(RetryInfoDetailDTO.class);
            assertThat(((RetryInfoDetailDTO) deserialized).getRetryDelay()).isEqualTo("PT30S");
        }

        @Test
        void roundTrip_nullDelay() throws Exception {
            // given
            RetryInfoDetailDTO original = RetryInfoDetailDTO.builder().build();

            // when
            String json = mapper.writeValueAsString(original);
            ErrorDetailDTO deserialized = mapper.readValue(json, ErrorDetailDTO.class);

            // then
            assertThat(deserialized).isInstanceOf(RetryInfoDetailDTO.class);
            assertThat(((RetryInfoDetailDTO) deserialized).getRetryDelay()).isNull();
        }
    }

    @Nested
    @DisplayName("CUSTOM")
    class Custom {

        @Test
        void roundTrip_preservesDetailTypeAndMetadata() throws Exception {
            // given
            CustomErrorDetailDTO original = CustomErrorDetailDTO.builder()
                    .detailType("CHANNEL_SWITCH")
                    .metadata(Map.of("targetChannel", "mobile", "reason", "upgrade"))
                    .build();

            // when
            String json = mapper.writeValueAsString(original);
            ErrorDetailDTO deserialized = mapper.readValue(json, ErrorDetailDTO.class);

            // then
            assertThat(json).contains("\"type\":\"CUSTOM\"");
            assertThat(deserialized).isInstanceOf(CustomErrorDetailDTO.class);
            CustomErrorDetailDTO result = (CustomErrorDetailDTO) deserialized;
            assertThat(result.getDetailType()).isEqualTo("CHANNEL_SWITCH");
            assertThat(result.getMetadata()).containsEntry("targetChannel", "mobile");
        }
    }

    @Nested
    @DisplayName("ErrorEntryDTO с details[]")
    class FullEntry {

        @Test
        void roundTrip_heterogeneousDetailsList() throws Exception {
            // given
            var entryDto = space.br1440.platform.errorhandling.model.dto.ErrorEntryDTO.builder()
                    .httpCode(400)
                    .errorCode("VALIDATION_FAILED")
                    .message("Validation failed")
                    .domain("order-service")
                    .requestURI("/api/orders")
                    .metadata(Map.of("correlationId", "abc-123"))
                    .detail(BadRequestDetailDTO.builder()
                            .fieldViolation(BadRequestDetailDTO.FieldViolation.builder()
                                    .field("email")
                                    .reason("INVALID")
                                    .description("bad email")
                                    .build())
                            .build())
                    .detail(ErrorInfoDetailDTO.builder()
                            .reason("VALIDATION_FAILED")
                            .domain("order-service")
                            .metadata(Map.of("violationCount", "1"))
                            .build())
                    .build();

            // when
            String json = mapper.writeValueAsString(entryDto);
            var deserialized = mapper.readValue(json,
                    space.br1440.platform.errorhandling.model.dto.ErrorEntryDTO.class);

            // then
            assertThat(deserialized.getHttpCode()).isEqualTo(400);
            assertThat(deserialized.getErrorCode()).isEqualTo("VALIDATION_FAILED");
            assertThat(deserialized.getMessage()).isEqualTo("Validation failed");
            assertThat(deserialized.getReason()).isNull();
            assertThat(deserialized.getDomain()).isEqualTo("order-service");
            assertThat(deserialized.getDetails()).hasSize(2);
            assertThat(deserialized.getDetails().get(0)).isInstanceOf(BadRequestDetailDTO.class);
            assertThat(deserialized.getDetails().get(1)).isInstanceOf(ErrorInfoDetailDTO.class);
        }
    }
}
