package space.br1440.platform.tracing.otel.javaagent.jmx.observation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.management.openmbean.CompositeData;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.validation.ValidationObservationSource;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.processor.ValidatingSpanProcessor;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
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

        AgentInstanceId agentInstanceId = AgentInstanceId.random();
        ValidationObservationSource source = new ValidationObservationSource(
                slot,
                TracingSettingsSourceTestFixtures.boundDefaults(agentInstanceId),
                agentInstanceId
        );
        CompositeData stable = source.getValidationObservation();

        try (ObservationSnapshotPublisher publisher = new ObservationSnapshotPublisher(
                List.of(source::publishObservationSnapshot),
                TEST_REFRESH_INTERVAL)) {
            // when
            publisher.start();
            assertThat(refreshEntered.await(2L, TimeUnit.SECONDS)).isTrue();

            // then
            assertTimeoutPreemptively(
                    Duration.ofMillis(100L),
                    () -> assertThat(source.getValidationObservation()).isSameAs(stable));
        } finally {
            releaseRefresh.countDown();
        }
    }

    @Test
    @Timeout(value = 5L, unit = TimeUnit.SECONDS)
    void closeInterruptsAndJoinsPublisherThread() throws Exception {
        // given
        CountDownLatch publicationEntered = new CountDownLatch(1);
        CountDownLatch publicationInterrupted = new CountDownLatch(1);
        AtomicReference<Thread> publisherThread = new AtomicReference<>();
        ObservationSnapshotPublisher publisher = getObservationSnapshotPublisher(publisherThread, publicationEntered, publicationInterrupted);
        assertThat(publicationEntered.await(2L, TimeUnit.SECONDS)).isTrue();
        publisher.close();

        // then
        assertThat(publicationInterrupted.await(100L, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(publisherThread.get()).isNotNull();
        assertThat(publisherThread.get().isDaemon()).isTrue();
        assertThat(publisherThread.get().isAlive()).isFalse();
    }

    private static ObservationSnapshotPublisher getObservationSnapshotPublisher(AtomicReference<Thread> publisherThread,
                                                                                CountDownLatch publicationEntered,
                                                                                CountDownLatch publicationInterrupted) {
        Runnable blockingSource = () -> {
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
                List.of(blockingSource),
                TEST_REFRESH_INTERVAL
        );

        // when
        publisher.start();
        return publisher;
    }

    @Test
    @Timeout(value = 5L, unit = TimeUnit.SECONDS)
    void eventDrivenPublicationRequestsAreCoalescedWhileRefreshIsRunning() throws Exception {
        // given
        CountDownLatch firstPublicationEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstPublication = new CountDownLatch(1);
        CountDownLatch secondPublicationEntered = new CountDownLatch(1);
        AtomicInteger publications = new AtomicInteger();
        Runnable blockingSource = () -> {
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

        try (ObservationSnapshotPublisher publisher = new ObservationSnapshotPublisher(
                List.of(blockingSource),
                Duration.ofHours(1L))) {
            // when
            publisher.start();
            publisher.requestSnapshotPublication();
            assertThat(firstPublicationEntered.await(2L, TimeUnit.SECONDS)).isTrue();
            publisher.requestSnapshotPublication();
            publisher.requestSnapshotPublication();
            releaseFirstPublication.countDown();

            // then
            assertThat(secondPublicationEntered.await(500L, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(publications).hasValue(1);
        } finally {
            releaseFirstPublication.countDown();
        }
    }

    @Test
    @Timeout(value = 5L, unit = TimeUnit.SECONDS)
    void publicationRequestBeforeStartIsIgnored() {
        // given
        AtomicInteger publications = new AtomicInteger();
        try (ObservationSnapshotPublisher publisher = new ObservationSnapshotPublisher(
                List.of(publications::incrementAndGet),
                Duration.ofHours(1L))
        ) {
            // when
            publisher.requestSnapshotPublication();

            // then
            assertThat(publications).hasValue(0);
        }
    }

    @Test
    @Timeout(value = 5L, unit = TimeUnit.SECONDS)
    void closedPublisherRejectsStartAndIgnoresPublication() {
        // given
        AtomicInteger publications = new AtomicInteger();
        ObservationSnapshotPublisher publisher = new ObservationSnapshotPublisher(
                List.of(publications::incrementAndGet),
                Duration.ofHours(1L));
        publisher.start();
        publisher.close();

        // when
        publisher.close();
        publisher.requestSnapshotPublication();
        publisher.publishSnapshot();

        // then
        assertThatThrownBy(publisher::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Observation snapshot publisher is closed");
        assertThat(publications).hasValue(0);
    }

    @Test
    @Timeout(value = 5L, unit = TimeUnit.SECONDS)
    void repeatedStartKeepsTheSinglePublisherThread() throws Exception {
        // given
        CountDownLatch published = new CountDownLatch(2);
        AtomicReference<Thread> publisherThread = new AtomicReference<>();
        try (ObservationSnapshotPublisher publisher = new ObservationSnapshotPublisher(
                List.of(() -> {
                    publisherThread.compareAndSet(null, Thread.currentThread());
                    published.countDown();
                }),
                TEST_REFRESH_INTERVAL)) {
            // when
            publisher.start();
            publisher.start();

            // then
            assertThat(published.await(2L, TimeUnit.SECONDS)).isTrue();
            assertThat(publisherThread.get().getName())
                    .isEqualTo("platform-tracing-jmx-observation-publisher");
        }
    }

    @Test
    void repeatedPublicationFailuresAreRateLimited() {
        // given
        AtomicInteger attempts = new AtomicInteger();
        ObservationSnapshotPublisher publisher = new ObservationSnapshotPublisher(
                List.of(() -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("simulated publication failure");
                }),
                Duration.ofHours(1L)
        );

        try (AgentLogCapture logs = AgentLogCapture.installed(); publisher) {
            // when
            for (int attempt = 0; attempt < 20; attempt++) {
                publisher.publishSnapshot();
            }

            // then
            assertThat(attempts).hasValue(20);
            assertThat(logs.events())
                    .filteredOn(event -> "JMX_OBSERVATION_SNAPSHOT_PUBLICATION_FAILED".equals(event.eventName()))
                    .hasSizeLessThan(20);
        }
    }
}
