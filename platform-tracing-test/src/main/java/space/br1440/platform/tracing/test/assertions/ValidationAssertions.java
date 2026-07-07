package space.br1440.platform.tracing.test.assertions;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Утверждения для characterization validation-тестов.
 */
public final class ValidationAssertions {

    private static final AttributeKey<String> MISSING_KEY =
            AttributeKey.stringKey("platform.validation.missing");

    private ValidationAssertions() {
    }

    public static void assertMissingRecorded(SpanData span, String... expectedKeys) {
        String missing = span.getAttributes().get(MISSING_KEY);
        assertThat(missing).isNotBlank();
        for (String key : expectedKeys) {
            assertThat(missing).contains(key);
        }
    }

    public static void assertMissingAbsent(SpanData span) {
        assertThat(span.getAttributes().get(MISSING_KEY)).isNull();
    }

    public static void assertPlatformAttributesPresent(SpanData span) {
        assertThat(span.getAttributes().get(AttributeKey.stringKey(PlatformAttributes.PLATFORM_TYPE))).isNotBlank();
        assertThat(span.getAttributes().get(AttributeKey.stringKey(PlatformAttributes.PLATFORM_RESULT))).isNotBlank();
    }
}
