package space.br1440.platform.errorhandling.model;

import lombok.Builder;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Инфраструктурный контекст запроса для ответа об ошибке.
 */
@Builder
public record RequestContext(@Nullable String correlationId,
                             @Nullable String traceId,
                             @Nullable String spanId) {

    public static final String CORRELATION_ID = "correlationId";
    public static final String TRACE_ID = "traceId";
    public static final String SPAN_ID = "spanId";

    public Map<String, String> toMap() {
        Map<String, String> map = new LinkedHashMap<>();

        if (!StringUtils.isBlank(correlationId)) {
            map.put(CORRELATION_ID, correlationId);
        }

        if (!StringUtils.isBlank(traceId)) {
            map.put(TRACE_ID, traceId);
        }

        if (!StringUtils.isBlank(spanId)) {
            map.put(SPAN_ID, spanId);
        }

        return Collections.unmodifiableMap(map);
    }

    public RequestContext withCorrelationIdIfMissing(String correlationId) {
        if (!StringUtils.isBlank(this.correlationId)) {
            return this;
        }

        return new RequestContext(correlationId, traceId, spanId);
    }
}
