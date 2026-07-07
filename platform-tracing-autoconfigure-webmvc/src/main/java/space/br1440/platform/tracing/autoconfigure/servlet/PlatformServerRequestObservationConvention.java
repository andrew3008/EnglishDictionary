package space.br1440.platform.tracing.autoconfigure.servlet;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanResult;

/**
 * Платформенная конвенция инструментации входящих HTTP-запросов в Servlet-приложениях.
 * <p>
 * Расширяет дефолтную {@link DefaultServerRequestObservationConvention} двумя обязательными
 * платформенными атрибутами: {@code platform.type=http_server} и {@code platform.result}
 * (success / failure), производным от HTTP-кода ответа и наличия исключения.
 * <p>
 * Регистрируется как Spring-бин и автоматически подхватывается Spring Web для всех серверных
 * обсервейшнов.
 */
public class PlatformServerRequestObservationConvention extends DefaultServerRequestObservationConvention {

    @Override
    public KeyValues getLowCardinalityKeyValues(ServerRequestObservationContext context) {
        return super.getLowCardinalityKeyValues(context).and(
                KeyValue.of(PlatformAttributes.PLATFORM_TYPE, SpanCategory.HTTP_SERVER.value()),
                KeyValue.of(PlatformAttributes.PLATFORM_RESULT, resolveResult(context).value())
        );
    }

    /**
     * Определяет финальный платформенный статус операции.
     * <p>
     * Логика: {@link SpanResult#FAILURE} при наличии исключения либо при HTTP-коде >= 400,
     * иначе {@link SpanResult#SUCCESS}.
     */
    static SpanResult resolveResult(ServerRequestObservationContext context) {
        if (context.getError() != null) {
            return SpanResult.FAILURE;
        }
        if (context.getResponse() != null && context.getResponse().getStatus() >= 400) {
            return SpanResult.FAILURE;
        }
        return SpanResult.SUCCESS;
    }
}
