package space.br1440.platform.errorhandling.model.mapping;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import space.br1440.platform.errorhandling.model.error_detail.dto.ErrorInfoDetailDTO;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ErrorInfoNormalizer")
class ErrorInfoNormalizerTest {

    @Nested
    @DisplayName("reasonOrUnspecified")
    class ReasonOrUnspecified {

        @Test
        void nullValue_returnsUnspecified() {
            assertThat(ErrorInfoNormalizer.reasonOrUnspecified(null))
                    .isEqualTo(ErrorInfoDetailDTO.REASON_UNSPECIFIED);
        }

        @Test
        void emptyValue_returnsUnspecified() {
            assertThat(ErrorInfoNormalizer.reasonOrUnspecified(""))
                    .isEqualTo(ErrorInfoDetailDTO.REASON_UNSPECIFIED);
        }

        @Test
        void blankValue_returnsUnspecified() {
            assertThat(ErrorInfoNormalizer.reasonOrUnspecified("   "))
                    .isEqualTo(ErrorInfoDetailDTO.REASON_UNSPECIFIED);
        }

        @Test
        void nonBlankValue_returnedAsIs() {
            assertThat(ErrorInfoNormalizer.reasonOrUnspecified("RATE_LIMIT_EXCEEDED"))
                    .isEqualTo("RATE_LIMIT_EXCEEDED");
        }

        @Test
        void valueWithLeadingTrailingSpaces_returnedAsIs() {
            assertThat(ErrorInfoNormalizer.reasonOrUnspecified(" KEEP_ME "))
                    .isEqualTo(" KEEP_ME ");
        }
    }

    @Nested
    @DisplayName("domainOrUnspecified")
    class DomainOrUnspecified {

        @Test
        void nullValue_returnsUnspecified() {
            assertThat(ErrorInfoNormalizer.domainOrUnspecified(null))
                    .isEqualTo(ErrorInfoDetailDTO.DOMAIN_UNSPECIFIED);
        }

        @Test
        void blankValue_returnsUnspecified() {
            assertThat(ErrorInfoNormalizer.domainOrUnspecified("   "))
                    .isEqualTo(ErrorInfoDetailDTO.DOMAIN_UNSPECIFIED);
        }

        @Test
        void nonBlankValue_returnedAsIs() {
            assertThat(ErrorInfoNormalizer.domainOrUnspecified("billing.platform.br1440.space"))
                    .isEqualTo("billing.platform.br1440.space");
        }
    }
}
