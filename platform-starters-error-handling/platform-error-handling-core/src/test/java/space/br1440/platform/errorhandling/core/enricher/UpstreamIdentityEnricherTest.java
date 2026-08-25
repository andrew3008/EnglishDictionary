package space.br1440.platform.errorhandling.core.enricher;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.core.util.http.UpstreamIdentity;
import space.br1440.platform.errorhandling.core.util.http.UpstreamIntegrationDomain;
import space.br1440.platform.errorhandling.core.util.http.UpstreamOutcome;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UpstreamIdentityEnricher")
class UpstreamIdentityEnricherTest {

    private final UpstreamIdentityEnricher enricher = new UpstreamIdentityEnricher();

    private static ErrorHandlingContext contextWith(UpstreamOutcome outcome) {
        ErrorHandlingContext base = ErrorHandlingContext.of(
                new ErrorHandlingProperties(),
                "/api/test",
                new StackHintExtractor(false, List.of())
        );
        return base.withUpstreamOutcome(outcome);
    }

    private static ErrorEntry entryWithHttpIntegrationDetail() {
        ErrorInfoDetail detail = ErrorInfoDetail.builder()
                .reason("upstream_response")
                .domain(UpstreamIntegrationDomain.HTTP_INTEGRATION)
                .build();

        return ErrorEntry.builder()
                .httpStatus(HttpStatus.BAD_GATEWAY)
                .errorCode("UNAVAILABLE")
                .message("The upstream service returned an unexpected error.")
                .details(List.of(detail))
                .build();
    }

    @Nested
    @DisplayName("identity распознан")
    class RecognizedIdentity {

        @Test
        @DisplayName("переносит upstreamErrorCode в clientMetadata detail'а")
        void carries_upstreamErrorCode_into_detail_clientMetadata() {
            // given
            UpstreamOutcome outcome = UpstreamOutcome.ofRecognized(502, UpstreamIdentity.of("ORDER_NOT_FOUND"));
            ErrorEntry entry = entryWithHttpIntegrationDetail();

            // when
            ErrorEntry enriched = enricher.enrich(entry, contextWith(outcome));

            // then
            ErrorInfoDetail detail = (ErrorInfoDetail) enriched.getDetails().get(0);
            assertThat(detail.getClientMetadata()).containsEntry("upstreamErrorCode", "ORDER_NOT_FOUND");
            assertThat(detail.getClientMetadata()).containsEntry("upstreamStatus", "502");
        }

        @Test
        @DisplayName("переносит domain и на detail, и на top-level ErrorEntry (иначе он не виден в JSON-ответе)")
        void carries_domain_onto_entry_level() {
            // given
            UpstreamOutcome outcome = UpstreamOutcome.ofRecognized(502,
                    UpstreamIdentity.of("ORDER_NOT_FOUND", "billing-service"));
            ErrorEntry entry = entryWithHttpIntegrationDetail();

            // when
            ErrorEntry enriched = enricher.enrich(entry, contextWith(outcome));

            // then
            assertThat(enriched.getDomain())
                    .as("top-level domain — единственное поле, которое реально попадает в JSON")
                    .isEqualTo("billing-service");
            ErrorInfoDetail detail = (ErrorInfoDetail) enriched.getDetails().get(0);
            assertThat(detail.getDomain()).isEqualTo("billing-service");
        }

        @Test
        @DisplayName("не перезаписывает уже заданный непустой top-level domain")
        void does_not_overwrite_existing_entry_domain() {
            // given
            UpstreamOutcome outcome = UpstreamOutcome.ofRecognized(502,
                    UpstreamIdentity.of("ORDER_NOT_FOUND", "billing-service"));
            ErrorEntry entry = entryWithHttpIntegrationDetail().toBuilder()
                    .domain("already-set-service")
                    .build();

            // when
            ErrorEntry enriched = enricher.enrich(entry, contextWith(outcome));

            // then
            assertThat(enriched.getDomain()).isEqualTo("already-set-service");
        }

        @Test
        @DisplayName("envelope без domain: identity переносится, top-level domain не трогается")
        void identity_without_domain_leaves_entry_domain_untouched() {
            // given
            UpstreamOutcome outcome = UpstreamOutcome.ofRecognized(502, UpstreamIdentity.of("ORDER_NOT_FOUND"));
            ErrorEntry entry = entryWithHttpIntegrationDetail();

            // when
            ErrorEntry enriched = enricher.enrich(entry, contextWith(outcome));

            // then
            assertThat(enriched.getDomain()).isNull();
        }
    }

    @Nested
    @DisplayName("identity не распознан")
    class UnrecognizedIdentity {

        @Test
        @DisplayName("только upstreamStatus переносится в clientMetadata, upstreamErrorCode отсутствует")
        void carries_only_status_when_envelope_not_recognized() {
            // given
            UpstreamOutcome outcome = UpstreamOutcome.ofStatus(503);
            ErrorEntry entry = entryWithHttpIntegrationDetail();

            // when
            ErrorEntry enriched = enricher.enrich(entry, contextWith(outcome));

            // then
            ErrorInfoDetail detail = (ErrorInfoDetail) enriched.getDetails().get(0);
            assertThat(detail.getClientMetadata()).containsEntry("upstreamStatus", "503");
            assertThat(detail.getClientMetadata()).doesNotContainKey("upstreamErrorCode");
            assertThat(enriched.getDomain()).isNull();
        }
    }

    @Nested
    @DisplayName("no-op условия")
    class NoOp {

        @Test
        @DisplayName("нет UpstreamOutcome в контексте — entry возвращается без изменений")
        void no_outcome_in_context_returns_same_entry() {
            // given
            ErrorEntry entry = entryWithHttpIntegrationDetail();
            ErrorHandlingContext context = ErrorHandlingContext.of(
                    new ErrorHandlingProperties(), "/api/test", new StackHintExtractor(false, List.of()));

            // when
            ErrorEntry enriched = enricher.enrich(entry, context);

            // then
            assertThat(enriched).isSameAs(entry);
        }

        @Test
        @DisplayName("сетевой отказ без HTTP-ответа — entry возвращается без изменений")
        void no_response_returns_same_entry() {
            // given
            ErrorEntry entry = entryWithHttpIntegrationDetail();

            // when
            ErrorEntry enriched = enricher.enrich(entry, contextWith(UpstreamOutcome.noResponse()));

            // then
            assertThat(enriched).isSameAs(entry);
        }

        @Test
        @DisplayName("нет detail с доменом HTTP_INTEGRATION — entry возвращается без изменений")
        void no_http_integration_detail_returns_same_entry() {
            // given
            ErrorEntry entry = ErrorEntry.builder()
                    .httpStatus(HttpStatus.BAD_REQUEST)
                    .errorCode("VALIDATION")
                    .message("invalid")
                    .details(List.of())
                    .build();
            UpstreamOutcome outcome = UpstreamOutcome.ofRecognized(502, UpstreamIdentity.of("X"));

            // when
            ErrorEntry enriched = enricher.enrich(entry, contextWith(outcome));

            // then
            assertThat(enriched).isSameAs(entry);
        }
    }

    @Test
    @DisplayName("order = HIGHEST_PRECEDENCE + 200")
    void order_is_high_precedence() {
        assertThat(enricher.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 200);
    }
}
