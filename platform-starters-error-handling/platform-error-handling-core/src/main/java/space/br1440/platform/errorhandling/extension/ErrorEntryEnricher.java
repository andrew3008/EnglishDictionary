package space.br1440.platform.errorhandling.extension;

import org.springframework.core.Ordered;
import org.springframework.lang.NonNull;
import space.br1440.platform.errorhandling.model.ErrorEntry;

/**
 * SPI пост-обработки {@link ErrorEntry} после стратегий и до метрик/сериализации.
 */
@FunctionalInterface
public interface ErrorEntryEnricher extends Ordered {

    @NonNull
    ErrorEntry enrich(@NonNull ErrorEntry entry, @NonNull ErrorHandlingContext context);

    /**
     * Порядок в цепочке (меньше — раньше). Справочные ссылки каталога обычно последними.
     */
    @Override
    default int getOrder() {
        return 0;
    }

}
