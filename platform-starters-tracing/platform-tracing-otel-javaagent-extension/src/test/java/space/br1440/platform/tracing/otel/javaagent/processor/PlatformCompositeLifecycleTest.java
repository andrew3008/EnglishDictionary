package space.br1440.platform.tracing.otel.javaagent.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.ResourceLock;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.logging.CapturedLogEvent;
import space.br1440.platform.tracing.otel.javaagent.processor.composite.PlatformCompositeSpanProcessor;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class PlatformCompositeLifecycleTest {

    @RegisterExtension
    final AgentLogCapture logs = new AgentLogCapture();

    @Test
    void repeatedAndConcurrentShutdownUsesOneSharedResultAndInvokesDelegateOnce() throws Exception {
        // given
        AtomicInteger shutdownCalls = new AtomicInteger();
        CompletableResultCode delegateShutdown = new CompletableResultCode();
        TestProcessor delegate = processor("one_shot", null, () -> {
            shutdownCalls.incrementAndGet();
            return delegateShutdown;
        });
        int callerCount = 12;
        CountDownLatch start = new CountDownLatch(1);
        try (PlatformCompositeSpanProcessor composite = new PlatformCompositeSpanProcessor(List.of(delegate));
             ExecutorService callers = Executors.newFixedThreadPool(callerCount)) {
            List<Future<CompletableResultCode>> futures = new ArrayList<>(callerCount);
            for (int index = 0; index < callerCount; index++) {
                futures.add(callers.submit(() -> {
                    start.await();
                    return composite.shutdown();
                }));
            }
            start.countDown();

            List<CompletableResultCode> results = new ArrayList<>(callerCount);
            for (Future<CompletableResultCode> future : futures) {
                // when
                results.add(future.get(5, TimeUnit.SECONDS));
            }

            // then
            assertThat(results).allMatch(result -> result == results.getFirst());
            assertThat(shutdownCalls).hasValue(1);
            assertThat(results.getFirst().isDone()).isFalse();

            delegateShutdown.succeed();
            assertThat(results.getFirst().join(5, TimeUnit.SECONDS).isSuccess()).isTrue();
            assertThat(composite.shutdown()).isSameAs(results.getFirst());
        }
    }

    @Test
    void shutdownWaitsForRegisteredFlushBeforeInvokingDelegates() {
        // given
        AtomicInteger flushCalls = new AtomicInteger();
        AtomicInteger shutdownCalls = new AtomicInteger();
        CompletableResultCode delegateFlush = new CompletableResultCode();
        CompletableResultCode delegateShutdown = new CompletableResultCode();
        TestProcessor delegate = processor("ordered", () -> {
            flushCalls.incrementAndGet();
            return delegateFlush;
        }, () -> {
            shutdownCalls.incrementAndGet();
            return delegateShutdown;
        });
        try (PlatformCompositeSpanProcessor composite = new PlatformCompositeSpanProcessor(List.of(delegate))) {
            CompletableResultCode flush = composite.forceFlush();
            // when
            CompletableResultCode shutdown = composite.shutdown();

            // then
            assertThat(flushCalls).hasValue(1);
            assertThat(shutdownCalls).hasValue(0);
            assertThat(shutdown.isDone()).isFalse();

            delegateFlush.succeed();
            assertThat(flush.join(5, TimeUnit.SECONDS).isSuccess()).isTrue();
            assertThat(shutdownCalls).hasValue(1);
            assertThat(shutdown.isDone()).isFalse();

            delegateShutdown.succeed();
            assertThat(shutdown.join(5, TimeUnit.SECONDS).isSuccess()).isTrue();
        }
    }

    @Test
    void flushIsRegisteredBeforeDelegateInvocationCompletes() throws Exception {
        // given
        CountDownLatch flushEntered = new CountDownLatch(1);
        CountDownLatch allowFlushReturn = new CountDownLatch(1);
        AtomicInteger shutdownCalls = new AtomicInteger();
        CompletableResultCode delegateFlush = new CompletableResultCode();
        TestProcessor delegate = processor("flush_barrier", () -> {
            flushEntered.countDown();
            await(allowFlushReturn);
            return delegateFlush;
        }, () -> {
            shutdownCalls.incrementAndGet();
            return CompletableResultCode.ofSuccess();
        });
        try (PlatformCompositeSpanProcessor composite = new PlatformCompositeSpanProcessor(List.of(delegate));
             ExecutorService caller = Executors.newSingleThreadExecutor()) {
            // when
            Future<CompletableResultCode> flushFuture = caller.submit(composite::forceFlush);
            // then
            assertThat(flushEntered.await(5, TimeUnit.SECONDS)).isTrue();

            CompletableResultCode shutdown = composite.shutdown();
            assertThat(shutdownCalls).hasValue(0);
            assertThat(shutdown.isDone()).isFalse();

            allowFlushReturn.countDown();
            CompletableResultCode flush = flushFuture.get(5, TimeUnit.SECONDS);
            delegateFlush.succeed();

            assertThat(flush.join(5, TimeUnit.SECONDS).isSuccess()).isTrue();
            assertThat(shutdown.join(5, TimeUnit.SECONDS).isSuccess()).isTrue();
            assertThat(shutdownCalls).hasValue(1);
        }
    }

    @Test
    void forceFlushAfterShutdownReturnsSharedResultWithoutCallingDelegate() {
        // given
        AtomicInteger flushCalls = new AtomicInteger();
        CompletableResultCode delegateShutdown = new CompletableResultCode();
        TestProcessor delegate = processor("shutdown_first", () -> {
            flushCalls.incrementAndGet();
            return CompletableResultCode.ofSuccess();
        }, () -> delegateShutdown);
        try (PlatformCompositeSpanProcessor composite = new PlatformCompositeSpanProcessor(List.of(delegate))) {
            CompletableResultCode shutdown = composite.shutdown();
            // when
            CompletableResultCode flushAfterShutdown = composite.forceFlush();

            // then
            assertThat(flushAfterShutdown).isSameAs(shutdown);
            assertThat(flushCalls).hasValue(0);

            delegateShutdown.succeed();
            assertThat(shutdown.join(5, TimeUnit.SECONDS).isSuccess()).isTrue();
        }
    }

    @Test
    void shutdownPublishesSharedResultBeforeDelegateInvocationCompletes() throws Exception {
        // given
        CountDownLatch shutdownEntered = new CountDownLatch(1);
        CountDownLatch allowShutdownReturn = new CountDownLatch(1);
        AtomicInteger flushCalls = new AtomicInteger();
        TestProcessor delegate = processor("shutdown_barrier", () -> {
            flushCalls.incrementAndGet();
            return CompletableResultCode.ofSuccess();
        }, () -> {
            shutdownEntered.countDown();
            await(allowShutdownReturn);
            return CompletableResultCode.ofSuccess();
        });
        try (PlatformCompositeSpanProcessor composite = new PlatformCompositeSpanProcessor(List.of(delegate));
             ExecutorService caller = Executors.newSingleThreadExecutor()) {
            // when
            Future<CompletableResultCode> shutdownFuture = caller.submit(composite::shutdown);
            // then
            assertThat(shutdownEntered.await(5, TimeUnit.SECONDS)).isTrue();

            CompletableResultCode shared = composite.shutdown();
            CompletableResultCode flushAfterPublication = composite.forceFlush();

            assertThat(flushAfterPublication).isSameAs(shared);
            assertThat(flushCalls).hasValue(0);

            allowShutdownReturn.countDown();
            assertThat(shutdownFuture.get(5, TimeUnit.SECONDS)).isSameAs(shared);
            assertThat(shared.join(5, TimeUnit.SECONDS).isSuccess()).isTrue();
        }
    }

    @Test
    void reentrantShutdownFailsClosedWithoutDeadlock() {
        // given
        AtomicReference<PlatformCompositeSpanProcessor> compositeRef = new AtomicReference<>();
        AtomicInteger shutdownCalls = new AtomicInteger();
        TestProcessor delegate = processor("reentrant", null, () -> {
            shutdownCalls.incrementAndGet();
            return compositeRef.get().shutdown();
        });
        try (PlatformCompositeSpanProcessor composite = new PlatformCompositeSpanProcessor(List.of(delegate))) {
            compositeRef.set(composite);

            // when
            CompletableResultCode result = composite.shutdown();

            // then
            assertThat(result.isDone()).isTrue();
            assertThat(result.isSuccess()).isFalse();
            assertThat(shutdownCalls).hasValue(1);
            assertThat(composite.shutdown()).isSameAs(result);
            assertThat(composite.getProcessorErrorCounts()).containsEntry("reentrant", 1L);
        }
    }

    @Test
    void malformedAndFailedResultsDoNotPreventLaterDelegateShutdown() {
        // given
        AtomicInteger laterCalls = new AtomicInteger();
        TestProcessor nullResult = processor("null_result", null, () -> null);
        TestProcessor throwing = processor("throwing", null, () -> {
            throw new IllegalStateException("test failure");
        });
        TestProcessor failed = processor("failed", null, CompletableResultCode::ofFailure);
        TestProcessor later = processor("later", null, () -> {
            laterCalls.incrementAndGet();
            return CompletableResultCode.ofSuccess();
        });
        try (PlatformCompositeSpanProcessor composite =
                     new PlatformCompositeSpanProcessor(List.of(nullResult, throwing, failed, later))) {
            // when
            CompletableResultCode result = composite.shutdown();

            // then
            assertThat(result.isDone()).isTrue();
            assertThat(result.isSuccess()).isFalse();
            assertThat(laterCalls).hasValue(1);
            assertThat(composite.getProcessorErrorCounts())
                    .containsEntry("null_result", 1L)
                    .containsEntry("throwing", 1L)
                    .containsEntry("failed", 1L)
                    .containsEntry("later", 0L);
            List<CapturedLogEvent> lifecycleFailures = logs.events().stream()
                    .filter(event -> "COMPOSITE_PROCESSOR_LIFECYCLE_FAILED".equals(event.eventName()))
                    .toList();
            assertThat(lifecycleFailures)
                    .extracting(event -> event.fields().get("kind"))
                    .containsExactly("RETURNED_NULL", "THREW", "ASYNC_FAILURE");
            assertThat(lifecycleFailures)
                    .extracting(event -> event.fields().get("delegate"))
                    .containsExactly("null_result", "throwing", "failed");
            assertThat(lifecycleFailures.get(1).errorType())
                    .isEqualTo(IllegalStateException.class.getName());
            assertThat(lifecycleFailures.get(1).throwableDigest())
                    .contains(IllegalStateException.class.getName());
            assertThat(logs.rendered()).doesNotContain("test failure");
        }
    }

    @Test
    void pendingDelegateFailureIsObservedAndCounted() {
        // given
        CompletableResultCode delegateShutdown = new CompletableResultCode();
        try (PlatformCompositeSpanProcessor composite = new PlatformCompositeSpanProcessor(List.of(
                processor("pending_failure", null, () -> delegateShutdown)
        ))) {
            // when
            CompletableResultCode result = composite.shutdown();
            // then
            assertThat(result.isDone()).isFalse();

            delegateShutdown.fail();

            assertThat(result.join(5, TimeUnit.SECONDS).isSuccess()).isFalse();
            assertThat(composite.getProcessorErrorCounts()).containsEntry("pending_failure", 1L);
        }
    }

    @Test
    void fatalFailureCompletesSharedResultBeforePropagation() {
        // given
        try (PlatformCompositeSpanProcessor composite = new PlatformCompositeSpanProcessor(List.of(
                processor("fatal", null, () -> {
                    throw new LinkageError("fatal test failure");
                })
        ))) {
            // given/when
            assertThatThrownBy(composite::shutdown).isInstanceOf(LinkageError.class);

            // then
            CompletableResultCode sharedResult = composite.shutdown();
            assertThat(sharedResult.isDone()).isTrue();
            assertThat(sharedResult.isSuccess()).isFalse();
        }
    }

    @Test
    void closeUsesOtelDefaultImplementation() throws NoSuchMethodException {
        // given/when/then
        assertThat(PlatformCompositeSpanProcessor.class.getMethod("close").getDeclaringClass())
                .isEqualTo(SpanProcessor.class);
    }

    private static TestProcessor processor(
            String diagnosticId,
            LifecycleCall forceFlush,
            LifecycleCall shutdown
    ) {
        return new TestProcessor(diagnosticId) {
            @Override
            public CompletableResultCode forceFlush() {
                return forceFlush == null ? CompletableResultCode.ofSuccess() : forceFlush.invoke();
            }

            @Override
            public CompletableResultCode shutdown() {
                return shutdown == null ? CompletableResultCode.ofSuccess() : shutdown.invoke();
            }
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Lifecycle barrier timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Lifecycle barrier was interrupted", interrupted);
        }
    }

    @FunctionalInterface
    private interface LifecycleCall {
        CompletableResultCode invoke();
    }

    private abstract static class TestProcessor implements SpanProcessor, ProcessorDiagnosticIdentity {

        private final String diagnosticId;

        private TestProcessor(String diagnosticId) {
            this.diagnosticId = diagnosticId;
        }

        @Override
        public String diagnosticId() {
            return diagnosticId;
        }

        @Override
        public void onStart(Context parentContext, ReadWriteSpan span) {
        }

        @Override
        public boolean isStartRequired() {
            return false;
        }

        @Override
        public void onEnd(ReadableSpan span) {
        }

        @Override
        public boolean isEndRequired() {
            return false;
        }
    }
}
