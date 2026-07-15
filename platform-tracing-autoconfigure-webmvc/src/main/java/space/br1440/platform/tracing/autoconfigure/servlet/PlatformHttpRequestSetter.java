package space.br1440.platform.tracing.autoconfigure.servlet;

import io.opentelemetry.context.propagation.TextMapSetter;
import jakarta.annotation.Nonnull;
import org.springframework.http.HttpRequest;

import java.util.Objects;

/**
 * {@link TextMapSetter} для записи платформенных заголовков в исходящий {@link HttpRequest}
 * ({@code RestTemplate} / {@code RestClient}).
 * <p>
 * ВАЖНО (CWE-113): {@code HttpHeaders.set} НЕ гарантирует strip CR/LF. Безопасность обеспечивается
 * тем, что сюда попадают только санитизированные значения ({@code RequestIdSupports}) либо
 * контролируемые литералы ({@code "on"}/{@code "1"}) — не сырой пользовательский ввод.
 */
final class PlatformHttpRequestSetter implements TextMapSetter<HttpRequest> {

    static final PlatformHttpRequestSetter INSTANCE = new PlatformHttpRequestSetter();

    private PlatformHttpRequestSetter() {
    }

    @Override
    public void set(HttpRequest request, @Nonnull String key, @Nonnull String value) {
        Objects.requireNonNull(value, "value");
        if (request != null) {
            request.getHeaders().set(key, value);
        }
    }
}
