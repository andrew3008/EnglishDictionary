package space.br1440.platform.tracing.autoconfigure.servlet;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.client.observation.DefaultClientRequestObservationConvention;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanResult;

public class PlatformClientRequestObservationConvention extends DefaultClientRequestObservationConvention {

    @Override
    public KeyValues getLowCardinalityKeyValues(ClientRequestObservationContext context) {
        return super.getLowCardinalityKeyValues(context).and(
                KeyValue.of(PlatformAttributes.PLATFORM_TYPE, SpanCategory.HTTP_CLIENT.value()),
                KeyValue.of(PlatformAttributes.PLATFORM_RESULT, resolveResult(context).value())
        );
    }

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
                return SpanResult.FAILURE;
            }
        }

        return SpanResult.SUCCESS;
    }
}
