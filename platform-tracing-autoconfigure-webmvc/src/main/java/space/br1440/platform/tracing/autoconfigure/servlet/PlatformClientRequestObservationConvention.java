package space.br1440.platform.tracing.autoconfigure.servlet;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.client.observation.DefaultClientRequestObservationConvention;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanResult;

/**
 * Платформенная конвенция инструментации исходящих HTTP-запросов в Servlet-приложениях
 * ({@code RestClient}, {@code RestTemplate}).
 * <p>
 * Расширяет дефолтную {@link DefaultClientRequestObservationConvention} платформенными атрибутами
 * {@code platform.type=http_client} и {@code platform.result}.
 * <p>
 * Использует Servlet-вариант {@link ClientRequestObservationContext} из пакета
 * {@code org.springframework.http.client.observation}; реактивный аналог
 * (пакет {@code org.springframework.web.reactive.function.client}) реализован отдельно
 * как {@code PlatformReactiveClientRequestObservationConvention} в WebFlux-модуле.
 */
public class PlatformClientRequestObservationConvention extends DefaultClientRequestObservationConvention {

    @Override
    public KeyValues getLowCardinalityKeyValues(ClientRequestObservationContext context) {
        return super.getLowCardinalityKeyValues(context).and(
                KeyValue.of(PlatformAttributes.PLATFORM_TYPE, SpanCategory.HTTP_CLIENT.value()),
                KeyValue.of(PlatformAttributes.PLATFORM_RESULT, resolveResult(context).value())
        );
    }

    /**
     * Определяет финальный платформенный статус исходящего HTTP-запроса.
     * <p>
     * Логика: {@link SpanResult#FAILURE} при наличии исключения, при ответе со статусом &gt;= 400
     * либо при невозможности прочитать ответ (сетевой сбой); иначе {@link SpanResult#SUCCESS}.
     */
    static SpanResult resolveResult(ClientRequestObservationContext context) {
        if (context.getError() != null) {
            return SpanResult.FAILURE;
        }
        if (context.getResponse() != null) {
            try {
                if (context.getResponse().getStatusCode().value() >= 400) {
                    return SpanResult.FAILURE;
                }
            } catch (java.io.IOException e) {
                // Невозможность прочитать статус ответа на стороне HTTP-клиента трактуется как сбой.
                return SpanResult.FAILURE;
            }
        }
        return SpanResult.SUCCESS;
    }
}
