package space.br1440.platform.tracing.otel.javaagent.processor.dropoldest;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

class ProcessorLifecycleThreadOwnershipTest {

    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(2);
    private static final String WORKER_THREAD_NAME = "platform-tracing-drop-oldest-exporter";

    @Test
    void lifecycleDeclaresNoProcessWideExecutor() {
        // given
        Field[] fields = ProcessorLifecycle.class.getDeclaredFields();

        // when
        boolean staticExecutorPresent = false;
        for (Field field : fields) {
            if (Modifier.isStatic(field.getModifiers()) && Executor.class.isAssignableFrom(field.getType())) {
                staticExecutorPresent = true;
                break;
            }
        }

        // then
        assertThat(staticExecutorPresent).isFalse();
    }

    @Test
    void firstShutdownOwnsOneDaemonTerminatorWhichEndsWithCleanup() throws InterruptedException {
        // given
        BlockingShutdownExporter exporter = new BlockingShutdownExporter();
        Set<Thread> preexistingWorkers = threadsNamed(WORKER_THREAD_NAME);
        PlatformDropOldestExportSpanProcessor processor = PlatformDropOldestExportSpanProcessor.builder(exporter)
                .scheduleDelay(Duration.ofMinutes(1))
                .shutdownTimeout(Duration.ofSeconds(5))
                .build();

        Thread worker = awaitOwnWorker(preexistingWorkers);
        assertThat(worker).isNotNull();
        assertThat(worker.isDaemon()).isTrue();
        assertThat(worker.isAlive()).isTrue();

        // when
        CompletableResultCode first = processor.shutdown();
        Thread terminator = null;
        try {
            assertThat(exporter.awaitShutdownEntered(AWAIT_TIMEOUT)).isTrue();
            terminator = exporter.terminatorThread();
            for (int invocation = 0; invocation < 32; invocation++) {
                assertThat(processor.shutdown()).isSameAs(first);
            }

            // then
            assertThat(exporter.shutdownCalls()).isEqualTo(1);
            assertThat(terminator).isNotNull();
            assertThat(terminator.isDaemon()).isTrue();
            assertThat(terminator.getName()).startsWith(ProcessorLifecycle.TERMINATOR_THREAD_NAME_PREFIX);
            assertThat(terminator.isAlive()).isTrue();
            assertThat(worker.isAlive()).isFalse();
        } finally {
            exporter.releaseShutdown();
            first.join(AWAIT_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
            worker.join(AWAIT_TIMEOUT.toMillis());
            if (terminator != null) {
                terminator.join(AWAIT_TIMEOUT.toMillis());
            }
        }

        assertThat(first.isSuccess()).isTrue();
        assertThat(terminator).isNotNull();
        assertThat(terminator.isAlive()).isFalse();
        assertThat(exporter.shutdownCalls()).isEqualTo(1);
    }

    private static Thread awaitOwnWorker(Set<Thread> preexisting) throws InterruptedException {
        long deadline = System.nanoTime() + AWAIT_TIMEOUT.toNanos();
        do {
            for (Thread candidate : threadsNamed(WORKER_THREAD_NAME)) {
                if (!preexisting.contains(candidate)) {
                    return candidate;
                }
            }

            TimeUnit.MILLISECONDS.sleep(10L);
        } while (System.nanoTime() < deadline);

        return null;
    }

    private static Set<Thread> threadsNamed(String name) {
        Set<Thread> matching = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (name.equals(thread.getName())) {
                matching.add(thread);
            }
        }

        return matching;
    }

    private static final class BlockingShutdownExporter implements SpanExporter {

        private final CountDownLatch shutdownEntered = new CountDownLatch(1);
        private final CountDownLatch shutdownRelease = new CountDownLatch(1);
        private final AtomicInteger shutdownCalls = new AtomicInteger();
        private final AtomicReference<Thread> terminatorThread = new AtomicReference<>();

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            shutdownCalls.incrementAndGet();
            terminatorThread.compareAndSet(null, Thread.currentThread());
            shutdownEntered.countDown();

            try {
                if (!shutdownRelease.await(AWAIT_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS)) {
                    return CompletableResultCode.ofFailure();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return CompletableResultCode.ofFailure();
            }

            return CompletableResultCode.ofSuccess();
        }

        boolean awaitShutdownEntered(Duration timeout) throws InterruptedException {
            return shutdownEntered.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
        }

        void releaseShutdown() {
            shutdownRelease.countDown();
        }

        int shutdownCalls() {
            return shutdownCalls.get();
        }

        Thread terminatorThread() {
            return terminatorThread.get();
        }
    }
}
