package space.br1440.platform.tracing.otel.javaagent.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.data.SpanData;
import jakarta.annotation.Nonnull;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntimeTestFixtures;

class ScrubbingProcessorFailClosedTest {

    private static final String FAILED = ScrubbingDecision.SCRUBBING_FAILED_REASON;

    @Test
    void unexpected_nonfatal_failure_collapses_every_value_from_original_snapshot() {
        // given
        AttributeKey<String> firstKey = AttributeKey.stringKey("first.secret");
        AttributeKey<String> secondKey = AttributeKey.stringKey("second.secret");
        Attributes snapshot = Attributes.builder()
                .put(firstKey, "first-raw-secret")
                .put(secondKey, "second-raw-secret")
                .build();
        ReadWriteSpan span = mock(ReadWriteSpan.class);
        SpanData spanData = mock(SpanData.class);
        when(span.toSpanData()).thenReturn(spanData);
        when(spanData.getAttributes()).thenReturn(snapshot);

        AtomicBoolean failFirstMutation = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (failFirstMutation.compareAndSet(true, false)) {
                throw new IllegalStateException("synthetic mutation failure");
            }
            return span;
        }).when(span).setAttribute(ArgumentMatchers.<AttributeKey<String>>any(), anyString());

        ScrubbingRuntime runtime = ScrubbingRuntimeTestFixtures.fromBuiltInRules(
                List.of(new MaskEveryStringRule())
        );

        try (ScrubbingSpanProcessor processor = new ScrubbingSpanProcessor(runtime)) {
            // when
            processor.onEnding(span);

            verify(span).setAttribute(firstKey, FAILED);
            verify(span).setAttribute(secondKey, FAILED);
            // then
            assertThat(snapshot.get(firstKey)).isEqualTo("first-raw-secret");
            assertThat(snapshot.get(secondKey)).isEqualTo("second-raw-secret");
            assertThat(runtime.failures()).isEqualTo(1);
            assertThat(runtime.attributesVerified())
                    .as("частично применённый collapse не даёт гарантии — сессия обязана перейти в "
                            + "collapse-режим на exporter-boundary")
                    .isFalse();
        }
    }

    @Test
    void collapse_that_itself_fails_still_degrades_the_session() {
        // given
        AttributeKey<String> key = AttributeKey.stringKey("only.secret");
        Attributes snapshot = Attributes.builder().put(key, "raw-secret").build();
        ReadWriteSpan span = mock(ReadWriteSpan.class);
        SpanData spanData = mock(SpanData.class);
        when(span.toSpanData()).thenReturn(spanData);
        when(spanData.getAttributes()).thenReturn(snapshot);

        doAnswer(invocation -> {
            throw new IllegalStateException("synthetic mutation failure");
        }).when(span).setAttribute(ArgumentMatchers.<AttributeKey<String>>any(), anyString());

        ScrubbingRuntime runtime = ScrubbingRuntimeTestFixtures.fromBuiltInRules(
                List.of(new MaskEveryStringRule()));

        try (ScrubbingSpanProcessor processor = new ScrubbingSpanProcessor(runtime)) {
            // when
            processor.onEnding(span);

            // then
            assertThat(runtime.attributesVerified())
                    .as("ни один атрибут не удалось схлопнуть: сырое значение осталось в span'е")
                    .isFalse();
            assertThat(runtime.collapseModeActivations())
                    .as("переход режима считается один раз")
                    .isEqualTo(1L);
            assertThat(runtime.failures()).isGreaterThanOrEqualTo(2L);
        }
    }

    @Test
    void unavailable_snapshot_also_degrades_the_session() {
        // given
        ReadWriteSpan span = mock(ReadWriteSpan.class);
        when(span.toSpanData()).thenThrow(new IllegalStateException("snapshot unavailable"));

        ScrubbingRuntime runtime = ScrubbingRuntimeTestFixtures.fromBuiltInRules(
                List.of(new MaskEveryStringRule()));

        try (ScrubbingSpanProcessor processor = new ScrubbingSpanProcessor(runtime)) {
            // when
            processor.onEnding(span);

            // then
            assertThat(runtime.attributesVerified()).isFalse();
            assertThat(runtime.collapseModeActivations()).isEqualTo(1L);
        }
    }

    private static final class MaskEveryStringRule implements SpanAttributeScrubbingRule {

        @Nonnull
        @Override
        public String name() {
            return "mask-every-string";
        }

        @Nonnull
        @Override
        public ScrubbingDecision evaluate(@Nonnull String key, Object value) {
            return ScrubbingDecision.mask("test");
        }
    }
}
