package space.br1440.platform.tracing.otel.javaagent.processor.dropoldest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.trace.data.SpanData;

class SpanQueueTest {

    @Test
    void headSequenceTracksTheOldestAdmittedSpan() {
        // given
        SpanQueue queue = new SpanQueue(2, 2);

        // when
        long firstDrop = queue.admit(spanData());
        long secondDrop = queue.admit(spanData());
        long thirdDrop = queue.admit(spanData());

        // then
        assertThat(firstDrop).isEqualTo(SpanQueue.NONE);
        assertThat(secondDrop).isEqualTo(SpanQueue.NONE);
        assertThat(thirdDrop).isEqualTo(1L);
        assertThat(queue.headSequence()).isEqualTo(2L);
        assertThat(queue.lastAdmittedSequence()).isEqualTo(3L);
    }

    @Test
    void drainBatchReportsAContiguousRange() {
        // given
        SpanQueue queue = new SpanQueue(8, 2);
        queue.admit(spanData());
        queue.admit(spanData());
        queue.admit(spanData());

        // when
        SpanQueue.Batch first = Objects.requireNonNull(queue.drainBatch());
        SpanQueue.Batch second = Objects.requireNonNull(queue.drainBatch());

        // then
        assertThat(first.firstSequence()).isEqualTo(1L);
        assertThat(first.lastSequence()).isEqualTo(2L);
        assertThat(first.spans()).hasSize(2);
        assertThat(second.firstSequence()).isEqualTo(3L);
        assertThat(second.spans()).hasSize(1);
    }

    @Test
    void drainAllAfterPartialDrainDiscardsOnlyTheRemainder() {
        // given
        SpanQueue queue = new SpanQueue(8, 2);
        List<SpanData> spans = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            SpanData span = spanData();
            spans.add(span);
            queue.admit(span);
        }
        queue.drainBatch();

        // when
        int discarded = queue.drainAll();

        // then
        assertThat(discarded).isEqualTo(2);
        assertThat(queue.isEmpty()).isTrue();
        assertThat(queue.headSequence()).isEqualTo(SpanQueue.NONE);
        assertThat(queue.lastAdmittedSequence()).isEqualTo(4L);
        assertThat(spans).hasSize(4);
    }

    private static SpanData spanData() {
        SpanData spanData = Mockito.mock(SpanData.class);
        Mockito.when(spanData.getAttributes()).thenReturn(Attributes.empty());
        return spanData;
    }
}
