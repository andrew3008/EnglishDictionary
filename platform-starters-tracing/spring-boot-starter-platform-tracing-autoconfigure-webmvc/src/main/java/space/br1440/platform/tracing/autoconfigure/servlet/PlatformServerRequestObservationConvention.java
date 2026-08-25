package space.br1440.platform.tracing.autoconfigure.servlet;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanResult;

public class PlatformServerRequestObservationConvention extends DefaultServerRequestObservationConvention {

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

        if (context.getResponse() != null && context.getResponse().getStatus() >= 400) {
            return SpanResult.FAILURE;
        }

        return SpanResult.SUCCESS;
    }
}
