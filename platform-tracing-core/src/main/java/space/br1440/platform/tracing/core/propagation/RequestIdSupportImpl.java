package space.br1440.platform.tracing.core.propagation;

import jakarta.annotation.Nullable;
import space.br1440.platform.tracing.api.propagation.RequestIdSupport;

import java.util.UUID;

/**
 * Обнаруживается call-site'ами ({@code InboundTraceControl.fromHeaders}, response-header filters)
 * единообразно через {@link space.br1440.platform.tracing.api.propagation.RequestIdSupports}
 * и {@link java.util.ServiceLoader} SPI (регистрация в {@code META-INF/services}).
 */
public final class RequestIdSupportImpl implements RequestIdSupport {

    public static final RequestIdSupportImpl INSTANCE = new RequestIdSupportImpl();

    /**
     * Публичный конструктор для {@link java.util.ServiceLoader}.
     */
    public RequestIdSupportImpl() {
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
