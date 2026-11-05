package space.br1440.platform.tracing.e2e;

import java.util.Collections;
import java.util.List;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;

final class PackagedScrubbingHmacScenario {

    static final String SPAN_NAME = "packaged-scrubbing-hmac";
    static final String IDENTITY = "packaged-identity-canary";
    static final String SECOND_IDENTITY = "packaged-secondary-canary";
    static final String HMAC_KEY = "packaged-contract-hmac-key";
    static final String SCALAR_KEY = "user.preferred_username";
    static final String ARRAY_KEY = "user.preferred_usernames";
    static final String OVERSIZED_KEY = "user.preferred_username.oversized";
    static final String NUMERIC_KEY = "user.preferred_username.numeric";

    private PackagedScrubbingHmacScenario() {
    }

    static void record() {
        Attributes attributes = Attributes.builder()
                .put(SCALAR_KEY, IDENTITY)
                .put(AttributeKey.stringArrayKey(ARRAY_KEY), List.of(IDENTITY, SECOND_IDENTITY))
                .put(AttributeKey.stringArrayKey(OVERSIZED_KEY), Collections.nCopies(33, IDENTITY))
                .put(NUMERIC_KEY, 7L)
                .put("safe.count", 73L)
                .build();
        SpanContext linked = SpanContext.create(
                "0123456789abcdef0123456789abcdef", "000000000000abcd",
                TraceFlags.getSampled(), TraceState.getDefault());
        Span span = GlobalOpenTelemetry.getTracer("packaged-scrubbing-hmac")
                .spanBuilder(SPAN_NAME).setAllAttributes(attributes).addLink(linked, attributes).startSpan();
        try {
            if (!span.isRecording()) {
                throw new IllegalStateException("Packaged HMAC scenario did not create a recording span");
            }
            span.addEvent("identity", attributes);
        } finally {
            span.end();
        }
    }
}
