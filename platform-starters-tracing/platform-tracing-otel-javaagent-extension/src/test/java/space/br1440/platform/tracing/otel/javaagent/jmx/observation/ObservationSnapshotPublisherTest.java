package space.br1440.platform.tracing.otel.javaagent.jmx.observation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.management.openmbean.CompositeData;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import space.br1440.platform.tracing.otel.javaagent.jmx.validation.ValidationControl;
import space.br1440.platform.tracing.otel.javaagent.processor.ValidatingSpanProcessor;

class ObservationSnapshotPublisherTest {

    private static final Duration TEST_REFRESH_INTERVAL = Duration.ofMillis(10L);

    @Test
    @Timeout(value = 5L, unit = TimeUnit.SECONDS)
    void hangingRefreshDoesNotBlockPublishedSnapshotGetter() throws Exception {
        // given
        CountDownLatch refreshEntered = new CountDownLatch(1);
        CountDownLatch releaseRefresh = new CountDownLatch(1);
        AtomicInteger captures = new AtomicInteger();
        OwnerRegistrationState.Absent<ValidatingSpanProcessor> absent = new OwnerRegistrationState.Absent<>(0L);
        @SuppressWarnings("unchecked")
        OwnerRegistrationSlot<ValidatingSpanProcessor> slot = mock(OwnerRegistrationSlot.class);
        when(slot.capture()).thenAnswer(invocation -> {
            if (captures.incrementAndGet() == 1) {
                return absent;
            }
            refreshEntered.countDown();
            releaseRefresh.await();
            return absent;
        });
        ValidationControl control = new ValidationControl(slot, UUID.randomUUID());
        CompositeData stable = control.getValidationObservation();
        ObservationSnapshotPublisher publisher = new ObservationSnapshotPublisher(
                List.of(control),
                TEST_REFRESH_INTERVAL);

        try {
            // when
            publisher.start();
            assertThat(refreshEntered.await(2L, TimeUnit.SECONDS)).isTrue();

            // then
            assertTimeoutPreemptively(
                    Duration.ofMillis(100L),
                    () -> assertThat(control.getValidationObservation()).isSameAs(stable));
        } finally {
            releaseRefresh.countDown();
            publisher.close();
        }
    }

    @Test
    @Timeout(value = 5L, unit = TimeUnit.SECONDS)
    void closeInterruptsAndJoinsPublisherThread() throws Exception {
        // given
        CountDownLatch publicationEntered = new CountDownLatch(1);
        CountDownLatch publicationInterrupted = new CountDownLatch(1);
        AtomicReference<Thread> publisherThread = new AtomicReference<>();
        ObservationSnapshotControl blockingControl = () -> {
            publisherThread.set(Thread.currentThread());
            publicationEntered.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException interruption) {
                publicationInterrupted.countDown();
                Thread.currentThread().interrupt();
            }
        };
        ObservationSnapshotPublisher publisher = new ObservationSnapshotPublisher(
                List.of(blockingControl),
                TEST_REFRESH_INTERVAL);

        // when
        publisher.start();
        assertThat(publicationEntered.await(2L, TimeUnit.SECONDS)).isTrue();
        publisher.close();

        // then
        assertThat(publicationInterrupted.await(100L, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(publisherThread.get()).isNotNull();
        assertThat(publisherThread.get().isDaemon()).isTrue();
        assertThat(publisherThread.get().isAlive()).isFalse();
    }

    @Test
    @Timeout(value = 5L, unit = TimeUnit.SECONDS)
    void eventDrivenPublicationRequestsAreCoalescedWhileRefreshIsRunning() throws Exception {
        // given
        CountDownLatch firstPublicationEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstPublication = new CountDownLatch(1);
        CountDownLatch secondPublicationEntered = new CountDownLatch(1);
        AtomicInteger publications = new AtomicInteger();
        ObservationSnapshotControl blockingControl = () -> {
            if (publications.incrementAndGet() == 1) {
                firstPublicationEntered.countDown();
                try {
                    releaseFirstPublication.await();
                } catch (InterruptedException interruption) {
                    Thread.currentThread().interrupt();
                }
                return;
            }
            secondPublicationEntered.countDown();
        };
        ObservationSnapshotPublisher publisher = new ObservationSnapshotPublisher(
                List.of(blockingControl),
                Duration.ofHours(1L));

        try {
            // when
            publisher.start();
            publisher.requestPublish();
            assertThat(firstPublicationEntered.await(2L, TimeUnit.SECONDS)).isTrue();
            publisher.requestPublish();
            publisher.requestPublish();
            releaseFirstPublication.countDown();

            // then
            assertThat(secondPublicationEntered.await(500L, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(publications).hasValue(1);
        } finally {
            releaseFirstPublication.countDown();
            publisher.close();
        }
    }
}
