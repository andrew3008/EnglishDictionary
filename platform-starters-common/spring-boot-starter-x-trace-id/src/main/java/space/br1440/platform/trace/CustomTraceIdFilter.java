package space.br1440.platform.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.regex.Pattern;

@Order(Ordered.HIGHEST_PRECEDENCE)
public class CustomTraceIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CustomTraceIdFilter.class);

    private static final String TRACEPARENT_HEADER = "traceparent";
    private static final String X_TRACE_ID_HEADER = "x-trace-id";

    private static final Pattern TRACE_ID_PATTERN = Pattern.compile("^[0-9a-f]{32}$");
    private static final Pattern SPAN_ID_PATTERN = Pattern.compile("^[0-9a-f]{16}$");
    private static final Pattern TWO_HEX_PATTERN = Pattern.compile("^[0-9a-f]{2}$");

    private static final String ALL_ZERO_TRACE_ID = "00000000000000000000000000000000";
    private static final String ALL_ZERO_SPAN_ID = "0000000000000000";

    private final SecureRandom random = new SecureRandom();

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String traceparent = request.getHeader(TRACEPARENT_HEADER);
        String xTraceId = request.getHeader(X_TRACE_ID_HEADER);

        HttpServletRequest requestToUse = request;

        boolean hasValidTraceparent = isValidTraceparent(traceparent);

        if (!hasValidTraceparent && xTraceId != null && !xTraceId.isBlank()) {
            String normalizedXTraceId = xTraceId.trim().toLowerCase();

            if (isValidTraceId(normalizedXTraceId)) {
                MutableHttpServletRequest wrappedRequest = new MutableHttpServletRequest(request);

                String syntheticSpanId = generateRandomSpanId();
                String syntheticTraceparent = buildTraceparent(normalizedXTraceId, syntheticSpanId);

                wrappedRequest.putHeader(TRACEPARENT_HEADER, syntheticTraceparent);
                requestToUse = wrappedRequest;

                log.debug("Synthetic traceparent injected from x-trace-id");
            } else {
                log.warn(
                        "Invalid x-trace-id received: '{}'. It must contain 32 hex characters and must not be all-zero. Falling back to default trace generation.",
                        xTraceId
                );
            }
        }

        filterChain.doFilter(requestToUse, response);
    }

    private String buildTraceparent(String traceId, String spanId) {
        return "00-" + traceId + "-" + spanId + "-01";
    }

    private boolean isValidTraceparent(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) {
            return false;
        }

        String[] parts = traceparent.trim().toLowerCase().split("-");
        if (parts.length != 4) {
            return false;
        }

        String version = parts[0];
        String traceId = parts[1];
        String parentId = parts[2];
        String traceFlags = parts[3];

        if (!TWO_HEX_PATTERN.matcher(version).matches()) {
            return false;
        }

        if (!isValidTraceId(traceId)) {
            return false;
        }

        if (!isValidSpanId(parentId)) {
            return false;
        }

        return TWO_HEX_PATTERN.matcher(traceFlags).matches();
    }

    private boolean isValidTraceId(String traceId) {
        return traceId != null
                && TRACE_ID_PATTERN.matcher(traceId).matches()
                && !ALL_ZERO_TRACE_ID.equals(traceId);
    }

    private boolean isValidSpanId(String spanId) {
        return spanId != null
                && SPAN_ID_PATTERN.matcher(spanId).matches()
                && !ALL_ZERO_SPAN_ID.equals(spanId);
    }

    private String generateRandomSpanId() {
        byte[] bytes = new byte[8];
        String spanId;

        do {
            random.nextBytes(bytes);
            spanId = toHex(bytes);
        } while (!isValidSpanId(spanId));

        return spanId;
    }

    private String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}