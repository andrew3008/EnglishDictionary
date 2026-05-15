package space.br1440.platform.errorhandling.model.factory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.ErrorEntryFactory;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.HelpDetail;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ErrorEntryFactory")
class ErrorEntryFactoryTest {

    /** Имя сервиса, под которым работает фабрика в тестах. */
    private static final String TEST_DOMAIN = "test-service";

    /** Экземпляр фабрики, инжектящий тестовый домен. */
    private final ErrorEntryFactory factory = new ErrorEntryFactory(TEST_DOMAIN);

    // -----------------------------------------------------------------
    // create(status, errorCode, message)
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("create(status, errorCode, message)")
    class Create {

        @Test
        void createsEntry_withStringCode() {
            ErrorEntry entry = factory.create(
                    HttpStatus.NOT_FOUND,
                    "RESOURCE_NOT_FOUND",
                    "Resource not found");

            assertThat(entry.getErrorCode()).isEqualTo("RESOURCE_NOT_FOUND");
            assertThat(entry.getMessage()).isEqualTo("Resource not found");
            assertThat(entry.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("стампит domain текущего сервиса в top-level и в header-detail")
        void stampsDomainOfThisService() {
            ErrorEntry entry = factory.create(
                    HttpStatus.NOT_FOUND,
                    "RESOURCE_NOT_FOUND",
                    "Resource not found");

            // Top-level ErrorEntry.domain — имя локального сервиса (AIP-193: ErrorInfo.domain).
            assertThat(entry.getDomain()).isEqualTo(TEST_DOMAIN);

            // Self-ErrorInfoDetail дублирует это значение для совместимости с обработчиками,
            // читающими только details[].
            assertThat(entry.getDetails()).hasSize(1);
            assertThat(entry.getDetails().getFirst()).isInstanceOf(ErrorInfoDetail.class);
            ErrorInfoDetail info = (ErrorInfoDetail) entry.getDetails().getFirst();
            assertThat(info.getReason()).isEqualTo("RESOURCE_NOT_FOUND");
            assertThat(info.getDomain()).isEqualTo(TEST_DOMAIN);
        }

        @Test
        void nullStatus_throwsNPE() {
            assertThatThrownBy(() -> factory.create(null, "CODE", "msg"))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void nullErrorCode_throwsNPE() {
            assertThatThrownBy(() -> factory.create(HttpStatus.BAD_REQUEST, null, "msg"))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // -----------------------------------------------------------------
    // createWithDetails -- ключевой контракт preserve-upstream
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("createWithDetails (preserve-upstream domain)")
    class CreateWithDetails {

        @Test
        @DisplayName("в списке нет ErrorInfoDetail: фабрика добавляет header со своим domain")
        void noErrorInfoDetail_addsHeaderDetail() {
            HelpDetail help = HelpDetail.of("https://docs.example.com", "0001");

            ErrorEntry entry = factory.createWithDetails(
                    HttpStatus.BAD_REQUEST,
                    "BAD_REQUEST",
                    "bad",
                    List.of(help));

            assertThat(entry.getDetails()).hasSize(2);
            assertThat(entry.getDetails().getFirst()).isInstanceOf(ErrorInfoDetail.class);
            ErrorInfoDetail header = (ErrorInfoDetail) entry.getDetails().getFirst();
            assertThat(header.getReason()).isEqualTo("BAD_REQUEST");
            assertThat(header.getDomain()).isEqualTo(TEST_DOMAIN);
            assertThat(entry.getDetails().get(1)).isInstanceOf(HelpDetail.class);
        }

        @Test
        @DisplayName("upstream прислал свой ErrorInfoDetail: domain не переписывается (preserve-upstream)")
        void preservesUpstreamDomain() {
            // given: апстрим прислал детали со своим domain="upstream-svc"
            ErrorInfoDetail upstreamHeader = ErrorInfoDetail.builder()
                    .reason("UPSTREAM_VALIDATION_FAILED")
                    .domain("upstream-svc")
                    .build();

            // when
            ErrorEntry entry = factory.createWithDetails(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "VALIDATION_FAILED",
                    "validation",
                    List.of(upstreamHeader));

            // then: ровно один ErrorInfoDetail, domain именно upstream-svc -- не переписан
            assertThat(entry.getDetails()).hasSize(1);
            ErrorInfoDetail kept = (ErrorInfoDetail) entry.getDetails().getFirst();
            assertThat(kept.getReason()).isEqualTo("UPSTREAM_VALIDATION_FAILED");
            assertThat(kept.getDomain()).isEqualTo("upstream-svc");
        }

        @Test
        @DisplayName("список из ErrorInfoDetail + HelpDetail: оба сохраняются как есть")
        void mixedDetails_bothPreserved() {
            ErrorInfoDetail upstreamHeader = ErrorInfoDetail.builder()
                    .reason("UPSTREAM_REASON")
                    .domain("upstream-svc")
                    .build();
            HelpDetail help = HelpDetail.of("https://docs.example.com", "0001");

            ErrorEntry entry = factory.createWithDetails(
                    HttpStatus.BAD_REQUEST,
                    "BAD_REQUEST",
                    "bad",
                    List.of(upstreamHeader, help));

            assertThat(entry.getDetails()).hasSize(2);
            assertThat(((ErrorInfoDetail) entry.getDetails().get(0)).getDomain()).isEqualTo("upstream-svc");
            assertThat(entry.getDetails().get(1)).isInstanceOf(HelpDetail.class);
        }

        @Test
        @DisplayName("null details: добавляется header-detail со стампом domain")
        void nullDetails_addsHeaderWithDomain() {
            ErrorEntry entry = factory.createWithDetails(
                    HttpStatus.BAD_REQUEST,
                    "BAD_REQUEST",
                    "bad",
                    null);

            assertThat(entry.getDetails()).hasSize(1);
            ErrorInfoDetail header = (ErrorInfoDetail) entry.getDetails().getFirst();
            assertThat(header.getReason()).isEqualTo("BAD_REQUEST");
            assertThat(header.getDomain()).isEqualTo(TEST_DOMAIN);
        }

        @Test
        @DisplayName("пустой список: добавляется header-detail со стампом domain")
        void emptyDetails_addsHeaderWithDomain() {
            ErrorEntry entry = factory.createWithDetails(
                    HttpStatus.BAD_REQUEST,
                    "BAD_REQUEST",
                    "bad",
                    List.of());

            assertThat(entry.getDetails()).hasSize(1);
            ErrorInfoDetail header = (ErrorInfoDetail) entry.getDetails().getFirst();
            assertThat(header.getDomain()).isEqualTo(TEST_DOMAIN);
        }

        @Test
        @DisplayName("caller строит header с clientMetadata сам — фабрика сохраняет его как есть")
        void callerProvidedHeaderWithClientMetadata_isKeptAsIs() {
            // Заменяет прежний createWithExtra: фабрика не имеет специального API под clientMetadata,
            // вызывающий код собирает ErrorInfoDetail напрямую и передаёт через createWithDetails.
            ErrorInfoDetail header = ErrorInfoDetail.builder()
                    .reason("INVALID_ARGUMENT")
                    .domain(TEST_DOMAIN)
                    .clientMetadata(Map.of("field", "email"))
                    .build();

            ErrorEntry entry = factory.createWithDetails(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_ARGUMENT",
                    "Invalid request",
                    List.of(header));

            assertThat(entry.getDetails()).hasSize(1);
            ErrorInfoDetail kept = (ErrorInfoDetail) entry.getDetails().getFirst();
            assertThat(kept.getReason()).isEqualTo("INVALID_ARGUMENT");
            assertThat(kept.getDomain()).isEqualTo(TEST_DOMAIN);
            assertThat(kept.getClientMetadata()).containsEntry("field", "email");
        }
    }

    // -----------------------------------------------------------------
    // Конструктор фабрики
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("constructor (String domain)")
    class Constructor {

        @Test
        @DisplayName("null domain нормализуется в DOMAIN_UNSPECIFIED")
        void nullDomain_normalizedToUnspecified() {
            ErrorEntryFactory f = new ErrorEntryFactory((String) null);
            assertThat(f.getDomain()).isEmpty();

            ErrorEntry entry = f.create(HttpStatus.NOT_FOUND, "X", "m");
            ErrorInfoDetail info = (ErrorInfoDetail) entry.getDetails().getFirst();
            assertThat(info.getDomain()).isEmpty();
        }

        @Test
        @DisplayName("непустой domain сохраняется как есть")
        void nonEmptyDomain_kept() {
            ErrorEntryFactory f = new ErrorEntryFactory("svc-a");
            assertThat(f.getDomain()).isEqualTo("svc-a");

            ErrorEntry entry = f.create(HttpStatus.NOT_FOUND, "X", "m");
            ErrorInfoDetail info = (ErrorInfoDetail) entry.getDetails().getFirst();
            assertThat(info.getDomain()).isEqualTo("svc-a");
        }

        @Test
        @DisplayName("пустая строка нормализуется в DOMAIN_UNSPECIFIED")
        void emptyDomain_normalizedToUnspecified() {
            // Раньше пустая строка сохранялась как есть, что было эквивалентно DOMAIN_UNSPECIFIED;
            // теперь resolveDomain() явно нормализует blank в DOMAIN_UNSPECIFIED — поведение
            // на стыке (пустая строка) семантически совпадает.
            ErrorEntryFactory f = new ErrorEntryFactory("");
            assertThat(f.getDomain()).isEmpty();
        }
    }

    // -----------------------------------------------------------------
    // Supplier-конструктор: Tracing-стартер поставляет local service name
    // через Supplier<String>; фабрика обязана корректно резолвить его на
    // каждом create() с защитой от null/blank/exception.
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("constructor (Supplier<String> domain)")
    class SupplierConstructor {

        @Test
        @DisplayName("supplier с динамическим значением — domain берётся свежим на каждом create()")
        void dynamicSupplier_resolvedOnEachCall() {
            AtomicReference<String> currentName = new AtomicReference<>("svc-initial");
            ErrorEntryFactory f = new ErrorEntryFactory(currentName::get);

            ErrorEntry first = f.create(HttpStatus.NOT_FOUND, "X", "m");
            assertThat(first.getDomain()).isEqualTo("svc-initial");

            currentName.set("svc-updated");
            ErrorEntry second = f.create(HttpStatus.NOT_FOUND, "X", "m");
            assertThat(second.getDomain()).isEqualTo("svc-updated");
        }

        @Test
        @DisplayName("supplier бросает RuntimeException — domain становится DOMAIN_UNSPECIFIED")
        void throwingSupplier_fallsBackToUnspecified() {
            Supplier<String> faulty = () -> {
                throw new IllegalStateException("tracing-стартер ещё не готов");
            };
            ErrorEntryFactory f = new ErrorEntryFactory(faulty);

            ErrorEntry entry = f.create(HttpStatus.INTERNAL_SERVER_ERROR, "X", "m");

            assertThat(entry.getDomain()).isEmpty();
            ErrorInfoDetail info = (ErrorInfoDetail) entry.getDetails().getFirst();
            assertThat(info.getDomain()).isEmpty();
        }

        @Test
        @DisplayName("supplier вернул null — domain становится DOMAIN_UNSPECIFIED")
        void nullReturningSupplier_fallsBackToUnspecified() {
            ErrorEntryFactory f = new ErrorEntryFactory(() -> null);

            ErrorEntry entry = f.create(HttpStatus.INTERNAL_SERVER_ERROR, "X", "m");

            assertThat(entry.getDomain()).isEmpty();
            ErrorInfoDetail info = (ErrorInfoDetail) entry.getDetails().getFirst();
            assertThat(info.getDomain()).isEmpty();
        }

        @Test
        @DisplayName("supplier вернул blank-строку — domain становится DOMAIN_UNSPECIFIED")
        void blankReturningSupplier_fallsBackToUnspecified() {
            ErrorEntryFactory f = new ErrorEntryFactory(() -> "   \t  ");

            ErrorEntry entry = f.create(HttpStatus.INTERNAL_SERVER_ERROR, "X", "m");

            assertThat(entry.getDomain()).isEmpty();
        }

        @Test
        @DisplayName("ErrorEntry.domain заполняется именем сервиса (раньше всегда было null)")
        void errorEntryDomain_isPopulated() {
            ErrorEntryFactory f = new ErrorEntryFactory(() -> "order-service");

            ErrorEntry entry = f.create(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "m");

            assertThat(entry.getDomain()).isEqualTo("order-service");
        }

        @Test
        @DisplayName("ErrorEntry.domain совпадает с self-ErrorInfoDetail.domain")
        void errorEntryDomain_matchesSelfDetailDomain() {
            ErrorEntryFactory f = new ErrorEntryFactory(() -> "order-service");

            ErrorEntry entry = f.create(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "m");

            ErrorInfoDetail self = (ErrorInfoDetail) entry.getDetails().getFirst();
            assertThat(entry.getDomain()).isEqualTo(self.getDomain()).isEqualTo("order-service");
        }

        @Test
        @DisplayName("null supplier — конструктор бросает NPE (fail-fast)")
        void nullSupplier_throwsNPE() {
            assertThatThrownBy(() -> new ErrorEntryFactory((Supplier<String>) null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // -----------------------------------------------------------------
    // Отдельная проверка: список ErrorDetail приводится к List<ErrorDetail>
    // (контрактные тесты типизации)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("createWithDetails возвращает иммутабельный список деталей")
    void detailsListIsImmutable() {
        ErrorInfoDetail upstream = ErrorInfoDetail.builder()
                .reason("R")
                .domain("upstream")
                .build();

        ErrorEntry entry = factory.createWithDetails(
                HttpStatus.BAD_REQUEST,
                "BAD",
                "bad",
                List.of(upstream));

        List<ErrorDetail> details = entry.getDetails();
        assertThatThrownBy(() -> details.add(upstream))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
