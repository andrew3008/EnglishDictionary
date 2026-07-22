package space.br1440.platform.tracing.otel.javaagent.configuration.spi;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class AutoConfigurationCustomizerOrdering {

    /** Умолчание OTel SPI: провайдеры с {@code order() == 0} — "стандартные". */
    private static final int OTEL_SPI_DEFAULT_ORDER = 0;

    /** Смещение: ставим платформенный провайдер после всех стандартных OTel-провайдеров. */
    private static final int AFTER_STOCK_OTEL_PROVIDERS_OFFSET = 100;

    /** Итоговый порядок платформенного extension-customizer'а. */
    public static final int PLATFORM_EXTENSION_ORDER = OTEL_SPI_DEFAULT_ORDER + AFTER_STOCK_OTEL_PROVIDERS_OFFSET;

}
