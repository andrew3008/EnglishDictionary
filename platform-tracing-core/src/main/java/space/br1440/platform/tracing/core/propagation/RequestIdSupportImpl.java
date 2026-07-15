package space.br1440.platform.tracing.core.propagation;

import jakarta.annotation.Nullable;
import space.br1440.platform.tracing.api.propagation.RequestIdSupport;

import java.util.UUID;

/**
 * Реализация {@link RequestIdSupport} без аллокаций на hot path.
 * <p>
 * Располагается в {@code platform-tracing-core} и обнаруживается через
 * {@link java.util.ServiceLoader} SPI (регистрация в {@code META-INF/services}).
 * Единственный публичный доступ — через
 * {@link space.br1440.platform.tracing.api.propagation.RequestIdSupports#get()}.
 * <p>
 * Прямое использование вне тестов запрещено
 * (enforced ArchUnit-правилом
 * {@code ModuleTaxonomyArchRules.REQUEST_ID_SUPPORT_IMPL_ACCESS_RESTRICTED}).
 */
public final class RequestIdSupportImpl implements RequestIdSupport {

    /**
     * Пакетный конструктор: {@link java.util.ServiceLoader} находит класс через
     * {@code META-INF/services} и не требует public конструктора.
     * Пакетный модификатор запрещает внешним модулям создавать экземпляр напрямую.
     */
    RequestIdSupportImpl() {
    }

    @Override
    public String resolve(@Nullable String incoming) {
        String sanitized = sanitizeOrNull(incoming);
        return (sanitized != null) ? sanitized : UUID.randomUUID().toString();
    }

    @Override
    @Nullable
    public String sanitizeOrNull(@Nullable String raw) {
        if (raw == null) {
            return null;
        }

        String t = raw.trim();
        if (t.isEmpty() || (t.length() > MAX_LENGTH)) {
            return null;
        }

        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '_' || c == '-';
            if (!ok) {
                return null;
            }
        }

        return t;
    }
}
