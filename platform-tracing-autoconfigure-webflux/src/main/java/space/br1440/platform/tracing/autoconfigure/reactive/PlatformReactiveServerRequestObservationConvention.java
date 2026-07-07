package space.br1440.platform.tracing.autoconfigure.reactive;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanResult;

/**
 * Платформенная конвенция инструментации входящих HTTP-запросов в реактивных приложениях (WebFlux).
 * <p>
 * Аналог Servlet-варианта в подпакете {@code servlet/}; использует пакет
 * {@code org.springframework.http.server.reactive.observation}.
 */
public class PlatformReactiveServerRequestObservationConvention extends DefaultServerRequestObservationConvention {

    @Override
    public KeyValues getLowCardinalityKeyValues(ServerRequestObservationContext context) {
        return super.getLowCardinalityKeyValues(context).and(
                KeyValue.of(PlatformAttributes.PLATFORM_TYPE, SpanCategory.HTTP_SERVER.value()),
                KeyValue.of(PlatformAttributes.PLATFORM_RESULT, resolveResult(context).value())
        );
    }

    static SpanResult resolveResult(ServerRequestObservationContext context) {
        if (context.getError() != null) {
            return SpanResult.FAILURE;
        }
        if (context.getResponse() == null) {
            return SpanResult.SUCCESS;
        }
        HttpStatusCode statusCode = context.getResponse().getStatusCode();
        // Любой статус >= 400 интерпретируется как failure: ветви is4xxClientError/is5xxServerError
        // эквивалентны одному условию value() >= 400 (стандартные коды Spring).
        return statusCode != null && statusCode.value() >= 400 ? SpanResult.FAILURE : SpanResult.SUCCESS;
    }
}
