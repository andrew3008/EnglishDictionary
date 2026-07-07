package space.br1440.platform.tracing.otel.extension.scrubbing;

import com.google.auto.service.AutoService;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SensitiveDataRule;

/**
 * Пример кастомного правила маскирования PII для микросервисов (test fixture).
 * <p>
 * Иллюстрирует паттерн расширения: правило реализует {@link SensitiveDataRule} и регистрируется
 * через {@link AutoService} в файле {@code META-INF/services/...} автоматически (compile-time).
 * <p>
 * Потребители могут копировать этот шаблон, добавлять его в отдельный JAR (зависящий от
 * {@code platform-tracing-api} как {@code compileOnly}) и подключать к OTel Agent.
 */
@AutoService(SensitiveDataRule.class)
public class ExampleMerchantAccountRule implements SensitiveDataRule {

    @Nonnull
    @Override
    public String name() {
        return "merchant-account-example";
    }

    @Override
    public int priority() {
        // Кастомное значение приоритета, например 2000 (ниже встроенных правил, у которых 10-140)
        return 2000;
    }

    /**
     * Быстрый предикат: правило применимо только к ключу {@code merchant.account.id}.
     * Демонстрирует рекомендуемый паттерн — отсечь нерелевантные ключи до {@link #evaluate}.
     */
    @Override
    public boolean supports(@Nonnull String key) {
        return "merchant.account.id".equals(key);
    }

    @Nonnull
    @Override
    public ScrubbingDecision evaluate(@Nonnull String key, @Nullable Object value) {
        // Key-based mask (ключ нормализован платформой: lower-case, trimmed, разделители . сохранены).
        if ("merchant.account.id".equals(key)) {
            return ScrubbingDecision.mask(name());
        }
        return ScrubbingDecision.keep();
    }
}
