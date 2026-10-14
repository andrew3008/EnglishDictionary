package space.br1440.platform.tracing.autoconfigure.mdc;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.context.integration.Slf4jThreadLocalAccessor;

class MdcPropagationCompletableFutureTest {

    private static final String TRACE_ID_KEY = "traceId";
    private static final String TRACE_ID = "fedcba9876543210fedcba9876543210";
    private static final long TIMEOUT_SECONDS = 5;

    private final ContextSnapshotFactory snapshotFactory = ContextSnapshotFactory.builder()
            .contextRegistry(new ContextRegistry().registerThreadLocalAccessor(new Slf4jThreadLocalAccessor()))
            .build();

    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("mdc-cf-worker").daemon(true).factory()
    );

    @AfterEach
    void tearDown() {
        MDC.clear();
        worker.shutdownNow();
    }

    @Test
    void unwrappedTaskDoesNotSeeCallerMdc() {
        // given
        MDC.put(TRACE_ID_KEY, TRACE_ID);

        // when
        String workerTraceId = traceIdOnWorker(UnaryOperator.identity());

        // then
        assertThat(workerTraceId)
                .as("negative control: executor itself must not propagate MDC")
                .isNull();
    }

    @Test
    void wrappedTaskSeesCallerTraceId() {
        // given
        MDC.put(TRACE_ID_KEY, TRACE_ID);
        ContextSnapshot snapshot = snapshotFactory.captureAll();

        // when
        String workerTraceId = traceIdOnWorker(snapshot::wrap);

        // then
        assertThat(workerTraceId)
                .as("ContextSnapshot.wrap must restore caller MDC on the worker thread")
                .isEqualTo(TRACE_ID);
    }

    @Test
    void wrappedTaskRestoresWorkerMdcAfterCompletion() {
        // given
        MDC.put(TRACE_ID_KEY, TRACE_ID);
        traceIdOnWorker(snapshotFactory.captureAll()::wrap);

        // when
        String nextTaskTraceId = traceIdOnWorker(UnaryOperator.identity());

        // then
        assertThat(nextTaskTraceId)
                .as("caller MDC must not leak into the next task on the same worker thread")
                .isNull();
    }

    private String traceIdOnWorker(UnaryOperator<Runnable> decorator) {
        AtomicReference<String> seen = new AtomicReference<>();
        CompletableFuture.runAsync(decorator.apply(() -> seen.set(MDC.get(TRACE_ID_KEY))), worker)
                .orTimeout(TIMEOUT_SECONDS, SECONDS)
                .join();
        return seen.get();
    }
}
