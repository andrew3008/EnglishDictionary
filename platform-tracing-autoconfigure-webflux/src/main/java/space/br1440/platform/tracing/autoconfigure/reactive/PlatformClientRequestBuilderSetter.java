package space.br1440.platform.tracing.autoconfigure.reactive;

import io.opentelemetry.context.propagation.TextMapSetter;
import org.springframework.web.reactive.function.client.ClientRequest;

/**
 * {@link TextMapSetter} для записи платформенных заголовков в исходящий {@code WebClient}-запрос
 * (через {@link ClientRequest.Builder}).
 * <p>
 * Безопасность (CWE-113): в заголовки попадают только санитизированные значения
 * ({@code RequestIdSupport}) или контролируемые литералы — не сырой пользовательский ввод.
 */
enum PlatformClientRequestBuilderSetter implements TextMapSetter<ClientRequest.Builder> {

    INSTANCE;

    @Override
    public void set(ClientRequest.Builder carrier, String key, String value) {
        if (carrier != null && value != null) {
            carrier.headers(httpHeaders -> httpHeaders.set(key, value));
        }
    }
}
