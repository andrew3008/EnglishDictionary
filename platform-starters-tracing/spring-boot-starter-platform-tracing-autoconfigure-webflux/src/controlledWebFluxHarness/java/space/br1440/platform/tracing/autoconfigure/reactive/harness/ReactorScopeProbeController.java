package space.br1440.platform.tracing.autoconfigure.reactive.harness;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

@RestController
public class ReactorScopeProbeController {

    static final String WRAPPED_TRACE_ID = "0123456789abcdef0123456789abcdef";
    static final String WRAPPED_SPAN_ID = "0123456789abcdef";
    private static final ContextKey<String> WRAPPED_OWNER = ContextKey.named("platform.tracing.r1.wrapped");
    private static final Scheduler WORKER = Schedulers.newSingle("r1-worker");
    private static final Scheduler WRAP_WORKER = Schedulers.newSingle("r1-wrap");

    @GetMapping("/hop")
    Mono<String> hop() {
        Span caller = Span.current();
        String callerTraceId = caller.getSpanContext().getTraceId();
        boolean callerValid = caller.getSpanContext().isValid();
        boolean callerRecording = caller.isRecording();
        return Mono.just(callerTraceId)
                .publishOn(WORKER)
                .map(traceId -> {
                    Span worker = Span.current();
                    return String.join("|",
                            traceId,
                            Boolean.toString(callerValid),
                            Boolean.toString(callerRecording),
                            worker.getSpanContext().getTraceId(),
                            Boolean.toString(worker.getSpanContext().isValid()),
                            Boolean.toString(worker.isRecording()),
                            Thread.currentThread().getName());
                });
    }

    @GetMapping("/wrap")
    Mono<String> wrap() {
        SpanContext remote = SpanContext.create(
                WRAPPED_TRACE_ID,
                WRAPPED_SPAN_ID,
                TraceFlags.getSampled(),
                TraceState.getDefault()
        );
        Span wrapped = Span.wrap(remote);

        return Mono.defer(() -> {
            Context captured = Context.current().with(wrapped).with(WRAPPED_OWNER, "outer");
            AtomicReference<String> result = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            Scope scope = captured.makeCurrent();
            String callerTraceId = Span.current().getSpanContext().getTraceId();

            //noinspection CallingSubscribeInNonBlockingScope
            Mono.just(callerTraceId)
                    .publishOn(WRAP_WORKER)
                    .map(traceId -> {
                        Span worker = Span.current();
                        return String.join("|",
                                traceId,
                                Boolean.toString(worker.getSpanContext().isValid()),
                                Boolean.toString(worker.isRecording()),
                                worker.getSpanContext().getTraceId(),
                                String.valueOf(Context.current().get(WRAPPED_OWNER)),
                                Thread.currentThread().getName());
                    })
                    .doOnNext(result::set)
                    .doFinally(signal -> done.countDown())
                    .subscribe();
            scope.close();
            return Mono.fromCallable(() -> {
                if (!done.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Wrap worker timed out");
                }

                return result.get();
            }).subscribeOn(Schedulers.boundedElastic());
        });
    }

    @GetMapping("/cancel")
    Mono<String> cancel() {
        Span caller = Span.current();
        String callerTraceId = caller.getSpanContext().getTraceId();
        CountDownLatch onWorker = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> cancelledTraceId = new AtomicReference<>("absent");

        //noinspection CallingSubscribeInNonBlockingScope
        Disposable subscription = Mono.fromRunnable(() -> {
            cancelledTraceId.set(Span.current().getSpanContext().getTraceId());
            onWorker.countDown();
            await(release);
        }).publishOn(WORKER).subscribe();

        return Mono.fromCallable(() -> {
            if (!onWorker.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Cancelled worker did not start");
            }

            subscription.dispose();
            release.countDown();
            return callerTraceId + "|" + cancelledTraceId.get() + "|" + Thread.currentThread().getName();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out while releasing the cancelled worker");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while releasing the cancelled worker", interrupted);
        }
    }
}
