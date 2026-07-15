package space.br1440.platform.tracing.core.propagation;

import jakarta.annotation.Nullable;
import space.br1440.platform.tracing.api.propagation.RequestIdSupport;

import java.util.UUID;

/**
 * OTel-независимая реализация {@link RequestIdSupport}.
 * <p>
 * Располагается в {@code platform-tracing-core}, чтобы бизнес-логика
 * (trim, char-цикл, UUID-генерация) не находилась в api-модуле.
 * <p>
 * Реализация {@link #sanitizeOrNull(String)} — zero-allocation на hot path
 * (ручной char-цикл вместо {@code Pattern.matcher()}), что уменьшает нагрузку
 * на CPU при потоке мусорных заголовков.
 * <p>
 * Прямое использование вне {@code RequestIdSupports} и тестов запрещено
 * (enforced ArchUnit-правилом {@code REQUEST_ID_SUPPORT_IMPL_ACCESS_RESTRICTED}).
 */
public final class RequestIdSupportImpl implements RequestIdSupport {

    /** Singleton-экземпляр, предоставляемый через {@code RequestIdSupports.get()}. */
    public static final RequestIdSupportImpl INSTANCE = new RequestIdSupportImpl();

    /** Приватный конструктор — используйте {@link #INSTANCE}. */
    private RequestIdSupportImpl() {
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
