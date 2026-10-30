package space.br1440.platform.tracing.otel.javaagent.exporter;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.otel.javaagent.exporter.response.ExportResponseObservation;
import space.br1440.platform.tracing.otel.javaagent.exporter.response.ExportResponseScope;
import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;

import io.opentelemetry.exporter.internal.otlp.traces.LowAllocationTraceRequestMarshaler;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

final class OtlpRequestSplittingSpanExporter implements SpanExporter {

    private static final String NULL_DELEGATE_RESULT = "delegate export result";

    private final SpanExporter delegate;
    private final int maxRequestBytes;
    private final long exportTimeoutNanos;
    private final LongSupplier nanoClock;

    OtlpRequestSplittingSpanExporter(SpanExporter delegate, int maxRequestBytes, long exportTimeoutNanos) {
        this(delegate, maxRequestBytes, exportTimeoutNanos, System::nanoTime);
    }

    OtlpRequestSplittingSpanExporter(SpanExporter delegate,
                                     int maxRequestBytes,
                                     long exportTimeoutNanos,
                                     LongSupplier nanoClock) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");

        if (maxRequestBytes <= 0) {
            throw new IllegalArgumentException("maxRequestBytes must be positive: " + maxRequestBytes);
        }

        if (exportTimeoutNanos <= 0) {
            throw new IllegalArgumentException("exportTimeoutNanos must be positive: " + exportTimeoutNanos);
        }

        this.maxRequestBytes = maxRequestBytes;
        this.exportTimeoutNanos = exportTimeoutNanos;
        try {
            new RequestSizeProbe().fits(List.of());
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to initialize OTLP request measurement", failure);
        }
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        Deadline deadline = new Deadline();
        try {
            List<SpanData> batch = List.copyOf(spans);
            RequestSizeProbe probe = new RequestSizeProbe();
            if (probe.fits(batch)) {
                if (deadline.expired()) {
                    return CompletableResultCode.ofExceptionalFailure(deadline.failure());
                }

                return Objects.requireNonNull(delegate.export(batch), NULL_DELEGATE_RESULT);
            }

            return new SplitExport(batch, probe, deadline, ExportResponseScope.current()).run();
        } catch (Throwable failure) {
            PlatformThrowables.propagateIfFatal(failure);
            return CompletableResultCode.ofExceptionalFailure(failure);
        }
    }

    @Override
    public CompletableResultCode flush() {
        return delegate.flush();
    }

    @Override
    public CompletableResultCode shutdown() {
        return delegate.shutdown();
    }

    private final class SplitExport {

        private final RequestSizeProbe probe;
        private final Deadline deadline;
        @Nullable
        private final ExportResponseObservation parent;
        private final Deque<List<SpanData>> pending = new ArrayDeque<>();
        private final ExportOutcomeTally tally;
        private final CompletableResultCode result = new CompletableResultCode();

        private final AtomicInteger drainRequests = new AtomicInteger();
        @Nullable
        private volatile Leaf completedLeaf;
        private boolean leafInFlight;
        private boolean finished;

        SplitExport(List<SpanData> batch,
                    RequestSizeProbe probe,
                    Deadline deadline,
                    @Nullable ExportResponseObservation parent) {
            this.probe = probe;
            this.deadline = deadline;
            this.parent = parent;
            this.tally = new ExportOutcomeTally(batch.size());

            splitOrDrop(batch);
        }

        CompletableResultCode run() {
            drain();
            return result;
        }

        private void drain() {
            if (drainRequests.getAndIncrement() != 0) {
                return;
            }

            do {
                try {
                    step();
                } catch (Throwable failure) {
                    try {
                        failRemaining(failure);
                    } finally {
                        PlatformThrowables.propagateIfFatal(failure);
                    }
                }
            } while (drainRequests.decrementAndGet() != 0);
        }

        private void step() throws IOException {
            if (finished) {
                return;
            }

            Leaf leaf = completedLeaf;
            if (leaf != null) {
                completedLeaf = null;
                leafInFlight = false;

                if (!leaf.result().isSuccess()) {
                    failRemaining(leaf.result().getFailureThrowable());
                    return;
                }

                tally.fold(leaf.observation());
            }

            if (!leafInFlight) {
                sendNextLeaf();
            }
        }

        private void sendNextLeaf() throws IOException {
            while (!pending.isEmpty()) {
                if (deadline.expired()) {
                    failRemaining(deadline.failure());
                    return;
                }

                List<SpanData> candidate = pending.pop();
                if (!probe.fits(candidate)) {
                    splitOrDrop(candidate);
                    continue;
                }

                if (deadline.expired()) {
                    failRemaining(deadline.failure());
                    return;
                }

                ExportResponseObservation observation = new ExportResponseObservation(candidate.size());
                CompletableResultCode leafResult;
                try (ExportResponseScope.Handle ignored = ExportResponseScope.open(observation)) {
                    leafResult = Objects.requireNonNull(delegate.export(candidate), NULL_DELEGATE_RESULT);
                }

                leafInFlight = true;
                Leaf leaf = new Leaf(leafResult, observation);
                leafResult.whenComplete(() -> {
                    completedLeaf = leaf;
                    drain();
                });

                return;
            }

            finish(null);
        }

        private void splitOrDrop(List<SpanData> candidate) {
            if (candidate.size() == 1) {
                tally.dropOversized();
                return;
            }

            int middle = candidate.size() / 2;
            pending.push(candidate.subList(middle, candidate.size()));
            pending.push(candidate.subList(0, middle));
        }

        private void failRemaining(@Nullable Throwable failure) {
            if (finished) {
                return;
            }

            pending.clear();
            tally.dropRemainingAsTransportFailure();
            finish(failure);
        }

        private void finish(@Nullable Throwable failure) {
            finished = true;

            try {
                if (parent != null) {
                    parent.recordAggregate(tally.toAggregate());
                }
            } catch (Throwable publicationFailure) {
                completeResult(false, publicationFailure);
                PlatformThrowables.propagateIfFatal(publicationFailure);
                return;
            }

            completeResult(tally.transportDropped() == 0, failure);
        }

        private void completeResult(boolean successful, @Nullable Throwable failure) {
            try {
                if (successful) {
                    result.succeed();
                } else {
                    result.failExceptionally(failure);
                }
            } catch (Throwable completionFailure) {
                PlatformThrowables.propagateIfFatal(completionFailure);
            }
        }
    }

    private record Leaf(CompletableResultCode result, ExportResponseObservation observation) {
    }

    private final class RequestSizeProbe {

        private final LowAllocationTraceRequestMarshaler marshaler = new LowAllocationTraceRequestMarshaler();
        private final BoundedSink sink = new BoundedSink();

        boolean fits(Collection<SpanData> spans) throws IOException {
            try {
                marshaler.initialize(spans);
                int estimate = marshaler.getBinarySerializedSize();
                if (estimate < 0 || estimate > maxRequestBytes) {
                    return false;
                }

                sink.count = 0;
                marshaler.writeBinaryTo(sink);
                return true;
            } catch (IOException failure) {
                if (failure instanceof RequestLimitExceededException
                        || failure.getCause() instanceof RequestLimitExceededException) {
                    return false;
                }

                throw failure;
            } finally {
                marshaler.reset();
            }
        }
    }

    private final class BoundedSink extends OutputStream {

        private long count;

        @Override
        public void write(int value) throws IOException {
            add(1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            add(length);
        }

        private void add(int length) throws RequestLimitExceededException {
            count += length;

            if (count > maxRequestBytes) {
                throw new RequestLimitExceededException();
            }
        }
    }

    private static final class RequestLimitExceededException extends IOException {

        private RequestLimitExceededException() {
            super("OTLP request size exceeds the configured limit");
        }
    }

    private final class Deadline {

        private final long startedNanos = nanoClock.getAsLong();

        boolean expired() {
            return nanoClock.getAsLong() - startedNanos >= exportTimeoutNanos;
        }

        TimeoutException failure() {
            return new TimeoutException("OTLP export admission deadline exceeded");
        }
    }
}
