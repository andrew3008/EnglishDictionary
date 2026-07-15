package space.br1440.platform.tracing.autoconfigure.reactive;

import io.opentelemetry.context.propagation.TextMapSetter;
import jakarta.annotation.Nonnull;
import org.springframework.web.reactive.function.client.ClientRequest;

/**
 * {@link TextMapSetter} для записи платформенных заголовков в исходящий {@code WebClient}-запрос
 * (через {@link ClientRequest.Builder}).
 * <p>
 * Безопасность (CWE-113): в заголовки попадают только санитизированные значения
 * ({@code RequestIdSupports}) или контролируемые литералы — не сырой пользовательский ввод.
 */
enum PlatformClientRequestBuilderSetter implements TextMapSetter<ClientRequest.Builder> {

    INSTANCE;

    @Override
    public void set(ClientRequest.Builder carrier, @Nonnull String key, @Nonnull String value) {
        if (carrier != null) {
            carrier.headers(httpHeaders -> httpHeaders.set(key, value));
        }
    }
}
