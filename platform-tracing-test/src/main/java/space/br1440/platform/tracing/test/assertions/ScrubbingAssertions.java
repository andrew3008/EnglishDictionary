package space.br1440.platform.tracing.test.assertions;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Утверждения для characterization scrubbing-тестов.
 */
public final class ScrubbingAssertions {

    private ScrubbingAssertions() {
    }

    public static void assertStringAttribute(SpanData span, String key, String expected) {
        assertThat(span.getAttributes().get(AttributeKey.stringKey(key))).isEqualTo(expected);
    }

    public static void assertStringAttributeEmpty(SpanData span, String key) {
        assertThat(span.getAttributes().get(AttributeKey.stringKey(key))).isEmpty();
    }

    public static void assertStringAttributePreserved(SpanData span, String key, String original) {
        assertThat(span.getAttributes().get(AttributeKey.stringKey(key))).isEqualTo(original);
    }

    public static void assertExportAlive(SpanData span) {
        assertThat(span).isNotNull();
        assertThat(span.getName()).isNotBlank();
    }
}
