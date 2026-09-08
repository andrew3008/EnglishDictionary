package space.br1440.platform.tracing.autoconfigure.reactive;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.lang.NonNull;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanResult;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;

public class PlatformReactiveServerRequestObservationConvention extends DefaultServerRequestObservationConvention {

    @Override
    @NonNull
    public KeyValues getLowCardinalityKeyValues(@NonNull ServerRequestObservationContext context) {
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
        return (statusCode != null && statusCode.value() >= 400) ? SpanResult.FAILURE : SpanResult.SUCCESS;
    }
}
