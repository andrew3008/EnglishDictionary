package space.br1440.platform.tracing.autoconfigure.reactive;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.ClientRequestObservationContext;
import org.springframework.web.reactive.function.client.DefaultClientRequestObservationConvention;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanResult;

/**
 * Платформенная конвенция инструментации исходящих HTTP-запросов в реактивных приложениях
 * ({@code WebClient}).
 * <p>
 * Расширяет реактивную {@link DefaultClientRequestObservationConvention} (пакет
 * {@code org.springframework.web.reactive.function.client} из артефакта {@code spring-webflux})
 * платформенными атрибутами {@code platform.type=http_client} и {@code platform.result}.
 * <p>
 * <b>Важно:</b> ровно тот же {@code DefaultClientRequestObservationConvention} существует и в
 * Servlet-flavor (пакет {@code org.springframework.http.client.observation} из {@code spring-web}).
 * Конвенции для разных стэков несовместимы по контексту: {@code supportsContext()}
 * базового класса проверяет {@code instanceof} соответствующего {@code ObservationContext}.
 * Поэтому для {@code WebClient} требуется именно реактивный родитель — иначе convention будет
 * молча игнорироваться при инструментации реактивных запросов.
 */
public class PlatformReactiveClientRequestObservationConvention extends DefaultClientRequestObservationConvention {

    @Override
    public KeyValues getLowCardinalityKeyValues(ClientRequestObservationContext context) {
        return super.getLowCardinalityKeyValues(context).and(
                KeyValue.of(PlatformAttributes.PLATFORM_TYPE, SpanCategory.HTTP_CLIENT.value()),
                KeyValue.of(PlatformAttributes.PLATFORM_RESULT, resolveResult(context).value())
        );
    }

    /**
     * Определяет финальный платформенный статус исходящего реактивного HTTP-запроса.
     * <p>
     * Логика: {@link SpanResult#FAILURE} при наличии исключения либо при ответе со статусом
     * &gt;= 400; иначе {@link SpanResult#SUCCESS}. В отличие от Servlet-варианта
     * чтение статуса не бросает {@code IOException} — реактивный {@code ClientResponse}
     * раскрывает статус через memoized {@link HttpStatusCode}.
     */
    static SpanResult resolveResult(ClientRequestObservationContext context) {
        if (context.getError() != null) {
            return SpanResult.FAILURE;
        }
        if (context.getResponse() == null) {
            return SpanResult.SUCCESS;
        }
        HttpStatusCode statusCode = context.getResponse().statusCode();
        return statusCode != null && statusCode.value() >= 400 ? SpanResult.FAILURE : SpanResult.SUCCESS;
    }
}
