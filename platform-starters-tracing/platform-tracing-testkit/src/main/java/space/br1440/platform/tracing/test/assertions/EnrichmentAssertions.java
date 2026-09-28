package space.br1440.platform.tracing.test.assertions;

import static org.assertj.core.api.Assertions.assertThat;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;
import lombok.experimental.UtilityClass;

@UtilityClass
public final class EnrichmentAssertions {

    public static void assertPlatformType(SpanData span, String expected) {
        assertThat(span.getAttributes().get(AttributeKey.stringKey(PlatformAttributes.PLATFORM_TYPE)))
                .isEqualTo(expected);
    }

    public static void assertPlatformResult(SpanData span, String expected) {
        assertThat(span.getAttributes().get(AttributeKey.stringKey(PlatformAttributes.PLATFORM_RESULT)))
                .isEqualTo(expected);
    }

    public static void assertRemoteServiceAbsent(SpanData span) {
        assertThat(span.getAttributes().get(AttributeKey.stringKey(PlatformAttributes.PLATFORM_REMOTE_SERVICE)))
                .isNull();
    }

    public static void assertRemoteService(SpanData span, String expected) {
        assertThat(span.getAttributes().get(AttributeKey.stringKey(PlatformAttributes.PLATFORM_REMOTE_SERVICE)))
                .isEqualTo(expected);
    }
}
