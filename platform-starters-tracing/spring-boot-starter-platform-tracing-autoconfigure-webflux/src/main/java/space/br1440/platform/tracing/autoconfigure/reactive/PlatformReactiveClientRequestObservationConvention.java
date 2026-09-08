package space.br1440.platform.tracing.autoconfigure.reactive;

import org.springframework.http.HttpStatusCode;
import org.springframework.lang.NonNull;
import org.springframework.web.reactive.function.client.ClientRequestObservationContext;
import org.springframework.web.reactive.function.client.DefaultClientRequestObservationConvention;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanResult;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;

public class PlatformReactiveClientRequestObservationConvention extends DefaultClientRequestObservationConvention {

    @Override
    @NonNull
    public KeyValues getLowCardinalityKeyValues(@NonNull ClientRequestObservationContext context) {
        return super.getLowCardinalityKeyValues(context).and(
                KeyValue.of(PlatformAttributes.PLATFORM_TYPE, SpanCategory.HTTP_CLIENT.value()),
                KeyValue.of(PlatformAttributes.PLATFORM_RESULT, resolveResult(context).value())
        );
    }

    static SpanResult resolveResult(ClientRequestObservationContext context) {
        if (context.getError() != null) {
            return SpanResult.FAILURE;
        }

        if (context.getResponse() == null) {
            return SpanResult.SUCCESS;
        }

        HttpStatusCode statusCode = context.getResponse().statusCode();
        return (statusCode.value() >= 400) ? SpanResult.FAILURE : SpanResult.SUCCESS;
    }
}
