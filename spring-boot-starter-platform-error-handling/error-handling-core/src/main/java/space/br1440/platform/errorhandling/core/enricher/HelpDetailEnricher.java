package space.br1440.platform.errorhandling.core.enricher;

import org.springframework.core.Ordered;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import space.br1440.platform.errorhandling.core.util.StringUtils;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.HelpDetail;
import space.br1440.platform.errorhandling.extension.ErrorEntryEnricher;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;

/**
 * Добавляет {@link HelpDetail} со ссылкой на каталог ошибок, если её ещё нет в {@code details}.
 * <p>Если {@code errorCatalogBaseUrl} не задан (null/blank), enricher является no-op.
 */
public final class HelpDetailEnricher implements ErrorEntryEnricher {

    @Nullable
    private final String errorCatalogBaseUrl;

    public HelpDetailEnricher(@Nullable String errorCatalogBaseUrl) {
        this.errorCatalogBaseUrl = errorCatalogBaseUrl;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public @NonNull ErrorEntry enrich(@NonNull ErrorEntry entry, @NonNull ErrorHandlingContext context) {
        if (!StringUtils.hasText(errorCatalogBaseUrl)) {
            return entry;
        }

        if (!StringUtils.hasText(entry.getErrorCode())) {
            return entry;
        }

        if (entry.getDetails().stream().anyMatch(HelpDetail.class::isInstance)) {
            return entry;
        }

        HelpDetail help = HelpDetail.of(errorCatalogBaseUrl, entry.getErrorCode());

        // Sprint 4 (bug-fix): используем .detail(item) — single-add через Lombok
        // @Singular. Предыдущий код через .details(List.copyOf(merged)) вызывал
        // дублирование: Lombok .details(Collection) имеет add-семантику, а после
        // toBuilder() аккумулятор уже содержит entry.getDetails(). Подробно
        // см. Javadoc ErrorEntry.ErrorEntryBuilder.
        return entry.toBuilder()
                .detail(help)
                .build();
    }
}
