package space.br1440.platform.errorhandling.core.enricher;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.HelpDetail;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RemoteServiceDomainEnricher")
class RemoteServiceDomainEnricherTest {

    private static final Set<String> DEFAULT_WHITELIST = new ErrorHandlingProperties.Tracing()
            .getIntegrationDomains();

    private static final ErrorHandlingContext CONTEXT = ErrorHandlingContext.of(
            new ErrorHandlingProperties(),
            "/test",
            new StackHintExtractor(false, List.of()));

    private static ErrorEntry entryWithDetails(List<ErrorDetail> details) {
        return ErrorEntry.builder()
                .httpStatus(HttpStatus.BAD_GATEWAY)
                .errorCode("TEST")
                .message("test")
                .domain("local-service")
                .details(details)
                .build();
    }

    private static ErrorInfoDetail info(String reason, String domain) {
        return ErrorInfoDetail.builder()
                .reason(reason)
                .domain(domain)
                .build();
    }

    private static RemoteServiceDomainEnricher enricher(Supplier<Optional<String>> supplier) {
        return new RemoteServiceDomainEnricher(supplier, DEFAULT_WHITELIST);
    }

    private static RemoteServiceDomainEnricher enricher(Supplier<Optional<String>> supplier,
                                                        Set<String> whitelist) {
        return new RemoteServiceDomainEnricher(supplier, whitelist);
    }

    // -----------------------------------------------------------------
    // 1. Provider пустой -> entry без изменений.
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("supplier даёт пустое значение")
    class EmptyRemote {

        @Test
        @DisplayName("Optional.empty -> entry не изменён")
        void emptyOptional_noChange() {
            ErrorEntry input = entryWithDetails(List.of(info("HTTP_TIMEOUT", "http-integration")));

            ErrorEntry output = enricher(Optional::empty).enrich(input, CONTEXT);

            assertThat(output).isSameAs(input);
        }

        @Test
        @DisplayName("Optional с blank-строкой -> entry не изменён")
        void blankOptional_noChange() {
            ErrorEntry input = entryWithDetails(List.of(info("HTTP_TIMEOUT", "http-integration")));

            ErrorEntry output = enricher(() -> Optional.of("   ")).enrich(input, CONTEXT);

            assertThat(output).isSameAs(input);
        }

        @Test
        @DisplayName("supplier вернул null Optional -> entry не изменён")
        void nullOptional_noChange() {
            ErrorEntry input = entryWithDetails(List.of(info("HTTP_TIMEOUT", "http-integration")));

            ErrorEntry output = enricher(() -> null).enrich(input, CONTEXT);

            assertThat(output).isSameAs(input);
        }
    }

    // -----------------------------------------------------------------
    // 2. Provider даёт "billing", деталь "http-integration" -> заменена.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("integration-detail (http-integration) заменён на имя upstream'а")
    void rewritesIntegrationDomain() {
        ErrorEntry input = entryWithDetails(List.of(info("HTTP_TIMEOUT", "http-integration")));

        ErrorEntry output = enricher(() -> Optional.of("billing")).enrich(input, CONTEXT);

        assertThat(output.getDetails()).hasSize(1);
        ErrorInfoDetail rewritten = (ErrorInfoDetail) output.getDetails().getFirst();
        assertThat(rewritten.getReason()).isEqualTo("HTTP_TIMEOUT");
        assertThat(rewritten.getDomain()).isEqualTo("billing");
    }

    // -----------------------------------------------------------------
    // 3. Provider даёт "billing", деталь "validation" -> не тронута.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("non-whitelist deталь (validation) не тронута")
    void preservesNonIntegrationDomain() {
        ErrorEntry input = entryWithDetails(List.of(info("FIELD_REQUIRED", "validation")));

        ErrorEntry output = enricher(() -> Optional.of("billing")).enrich(input, CONTEXT);

        assertThat(output).isSameAs(input);
    }

    // -----------------------------------------------------------------
    // 4. Несколько ErrorInfoDetail — заменяем только whitelist'ованные.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("смешанный список — переписываются только integration-detail'и")
    void mixedDetails_onlyIntegrationRewritten() {
        ErrorInfoDetail integration = info("HTTP_TIMEOUT", "http-integration");
        ErrorInfoDetail validation = info("FIELD_REQUIRED", "validation");
        ErrorInfoDetail kafka = info("KAFKA_REBALANCE", "kafka");
        HelpDetail help = HelpDetail.of("https://docs.example.com", "0001");

        ErrorEntry input = entryWithDetails(List.of(integration, validation, kafka, help));

        ErrorEntry output = enricher(() -> Optional.of("billing")).enrich(input, CONTEXT);

        List<ErrorDetail> details = output.getDetails();
        assertThat(details).hasSize(4);
        assertThat(((ErrorInfoDetail) details.get(0)).getDomain()).isEqualTo("billing");
        assertThat(((ErrorInfoDetail) details.get(1)).getDomain()).isEqualTo("validation");
        assertThat(((ErrorInfoDetail) details.get(2)).getDomain()).isEqualTo("billing");
        assertThat(details.get(3)).isInstanceOf(HelpDetail.class);
    }

    // -----------------------------------------------------------------
    // 5. preserve-upstream: detail с уже-распознанным именем сервиса не тронут.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("detail с реальным именем upstream'а ('order-service', не в whitelist) не тронут")
    void preservesAlreadyKnownUpstreamName() {
        ErrorInfoDetail upstreamHeader = info("UPSTREAM_VALIDATION_FAILED", "order-service");
        ErrorEntry input = entryWithDetails(List.of(upstreamHeader));

        ErrorEntry output = enricher(() -> Optional.of("billing")).enrich(input, CONTEXT);

        assertThat(output).isSameAs(input);
    }

    // -----------------------------------------------------------------
    // 6. Кастомный whitelist через properties.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("кастомный whitelist (my-internal-bus) — переписывается этот маркер")
    void customWhitelist_rewritesCustomMarker() {
        ErrorEntry input = entryWithDetails(List.of(info("BUS_TIMEOUT", "my-internal-bus")));

        Set<String> custom = Set.of("my-internal-bus");
        ErrorEntry output = enricher(() -> Optional.of("billing"), custom).enrich(input, CONTEXT);

        assertThat(output.getDetails()).hasSize(1);
        assertThat(((ErrorInfoDetail) output.getDetails().getFirst()).getDomain()).isEqualTo("billing");
    }

    // -----------------------------------------------------------------
    // 7. Provider бросает RuntimeException -> entry без изменений.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("supplier бросает RuntimeException — pipeline не падает, entry без изменений")
    void throwingSupplier_doesNotBreakPipeline() {
        ErrorEntry input = entryWithDetails(List.of(info("HTTP_TIMEOUT", "http-integration")));

        Supplier<Optional<String>> faulty = () -> {
            throw new IllegalStateException("MDC bridge failed");
        };

        ErrorEntry output = enricher(faulty).enrich(input, CONTEXT);

        assertThat(output).isSameAs(input);
    }

    // -----------------------------------------------------------------
    // Дополнительно: список деталей пустой — supplier даже не дёргается на изменение.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("пустой список деталей -> entry без изменений")
    void emptyDetails_noChange() {
        ErrorEntry input = entryWithDetails(List.of());

        ErrorEntry output = enricher(() -> Optional.of("billing")).enrich(input, CONTEXT);

        assertThat(output).isSameAs(input);
    }
}
