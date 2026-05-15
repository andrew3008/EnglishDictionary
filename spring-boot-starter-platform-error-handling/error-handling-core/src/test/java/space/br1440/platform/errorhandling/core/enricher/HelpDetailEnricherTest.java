package space.br1440.platform.errorhandling.core.enricher;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.HelpDetail;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HelpDetailEnricher")
class HelpDetailEnricherTest {

    private static final String CATALOG_URL = "https://docs.example.com/errors";

    private final ErrorHandlingContext context = ErrorHandlingContext.of(
            new ErrorHandlingProperties(), "/api/test", new StackHintExtractor(false, List.of())
    );

    private static HelpDetailEnricher enricherWith(String catalogUrl) {
        return new HelpDetailEnricher(catalogUrl);
    }

    @Nested
    @DisplayName("добавление HelpDetail")
    class AddHelpDetail {

        @Test
        @DisplayName("добавляет HelpDetail, когда errorCode задан и каталог сконфигурирован")
        void adds_HelpDetail_when_errorCode_present_and_catalog_configured() {
            // given
            HelpDetailEnricher enricher = enricherWith(CATALOG_URL);
            ErrorEntry entry = ErrorEntry.builder()
                    .httpStatus(HttpStatus.BAD_REQUEST)
                    .errorCode("SVC-0420")
                    .message("Validation failed")
                    .details(List.of())
                    .build();

            // when
            ErrorEntry enriched = enricher.enrich(entry, context);

            // then
            assertThat(enriched.getDetails()).hasSize(1);
            HelpDetail help = findDetail(enriched.getDetails(), HelpDetail.class);
            assertThat(help).isNotNull();
            assertThat(help.getLinks()).hasSize(1);
            assertThat(help.getLinks().getFirst().getUrl()).contains("SVC-0420");
        }

        @Test
        @DisplayName("сохраняет существующие details при добавлении HelpDetail")
        void preserves_existing_details() {
            // given
            HelpDetailEnricher enricher = enricherWith(CATALOG_URL);
            ErrorInfoDetail existing = ErrorInfoDetail.builder()
                    .reason("TEST")
                    .domain("test-domain")
                    .build();
            ErrorEntry entry = ErrorEntry.builder()
                    .httpStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                    .errorCode("SVC-0500")
                    .message("Internal error")
                    .details(List.of(existing))
                    .build();

            // when
            ErrorEntry enriched = enricher.enrich(entry, context);

            // then
            assertThat(enriched.getDetails()).hasSize(2);
            assertThat(findDetail(enriched.getDetails(), ErrorInfoDetail.class)).isNotNull();
            assertThat(findDetail(enriched.getDetails(), HelpDetail.class)).isNotNull();
        }

        @Test
        @DisplayName("URL каталога без trailing slash формирует корректную ссылку")
        void catalog_url_without_trailing_slash() {
            // given
            HelpDetailEnricher enricher = enricherWith("https://docs.example.com/errors");
            ErrorEntry entry = ErrorEntry.builder()
                    .httpStatus(HttpStatus.NOT_FOUND)
                    .errorCode("NOT_FOUND")
                    .message("not found")
                    .details(List.of())
                    .build();

            // when
            ErrorEntry enriched = enricher.enrich(entry, context);

            // then
            HelpDetail help = findDetail(enriched.getDetails(), HelpDetail.class);
            assertThat(help.getLinks().getFirst().getUrl())
                    .isEqualTo("https://docs.example.com/errors/NOT_FOUND");
        }

        @Test
        @DisplayName("URL каталога с trailing slash формирует корректную ссылку (без двойного slash)")
        void catalog_url_with_trailing_slash() {
            // given
            HelpDetailEnricher enricher = enricherWith("https://docs.example.com/errors/");
            ErrorEntry entry = ErrorEntry.builder()
                    .httpStatus(HttpStatus.NOT_FOUND)
                    .errorCode("NOT_FOUND")
                    .message("not found")
                    .details(List.of())
                    .build();

            // when
            ErrorEntry enriched = enricher.enrich(entry, context);

            // then
            HelpDetail help = findDetail(enriched.getDetails(), HelpDetail.class);
            assertThat(help.getLinks().getFirst().getUrl())
                    .isEqualTo("https://docs.example.com/errors/NOT_FOUND");
        }
    }

    @Nested
    @DisplayName("идемпотентность")
    class Idempotency {

        @Test
        @DisplayName("не добавляет второй HelpDetail, если уже есть")
        void does_not_duplicate_HelpDetail() {
            // given
            HelpDetailEnricher enricher = enricherWith(CATALOG_URL);
            HelpDetail existingHelp = HelpDetail.of("https://existing.example.com", "001");
            ErrorEntry entry = ErrorEntry.builder()
                    .httpStatus(HttpStatus.BAD_REQUEST)
                    .errorCode("SVC-0420")
                    .message("error")
                    .details(List.of(existingHelp))
                    .build();

            // when
            ErrorEntry enriched = enricher.enrich(entry, context);

            // then
            assertThat(enriched.getDetails().stream().filter(HelpDetail.class::isInstance).count())
                    .isEqualTo(1);
            assertThat(enriched).isSameAs(entry);
        }
    }

    @Nested
    @DisplayName("no-op условия")
    class NoOp {

        @Test
        @DisplayName("не добавляет HelpDetail, когда errorCode пуст")
        void no_HelpDetail_when_errorCode_blank() {
            // given
            HelpDetailEnricher enricher = enricherWith(CATALOG_URL);
            ErrorEntry entry = ErrorEntry.builder()
                    .httpStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                    .errorCode("")
                    .message("unknown")
                    .details(List.of())
                    .build();

            // when
            ErrorEntry enriched = enricher.enrich(entry, context);

            // then
            assertThat(enriched.getDetails()).isEmpty();
            assertThat(enriched).isSameAs(entry);
        }

        @Test
        @DisplayName("не добавляет HelpDetail, когда URL каталога null")
        void no_HelpDetail_when_catalog_url_null() {
            // given
            HelpDetailEnricher enricher = enricherWith(null);
            ErrorEntry entry = ErrorEntry.builder()
                    .httpStatus(HttpStatus.BAD_REQUEST)
                    .errorCode("SVC-0420")
                    .message("error")
                    .details(List.of())
                    .build();

            // when
            ErrorEntry enriched = enricher.enrich(entry, context);

            // then
            assertThat(enriched.getDetails()).isEmpty();
            assertThat(enriched).isSameAs(entry);
        }

        @Test
        @DisplayName("не добавляет HelpDetail, когда URL каталога blank")
        void no_HelpDetail_when_catalog_url_blank() {
            // given
            HelpDetailEnricher enricher = enricherWith("   ");
            ErrorEntry entry = ErrorEntry.builder()
                    .httpStatus(HttpStatus.BAD_REQUEST)
                    .errorCode("SVC-0420")
                    .message("error")
                    .details(List.of())
                    .build();

            // when
            ErrorEntry enriched = enricher.enrich(entry, context);

            // then
            assertThat(enriched.getDetails()).isEmpty();
            assertThat(enriched).isSameAs(entry);
        }
    }

    @Test
    @DisplayName("order = LOWEST_PRECEDENCE")
    void order_is_lowest_precedence() {
        assertThat(enricherWith(CATALOG_URL).getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
    }

    @SuppressWarnings("unchecked")
    private static <T extends ErrorDetail> T findDetail(List<ErrorDetail> details, Class<T> type) {
        return (T) details.stream()
                .filter(type::isInstance)
                .findFirst()
                .orElse(null);
    }

}
