package space.br1440.platform.tracing.otel.extension.processor;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import space.br1440.platform.tracing.otel.extension.configuration.spi.DropOldestExportProcessorDefaults;
import space.br1440.platform.tracing.otel.extension.configuration.ExtensionDefaults;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
public final class PlatformDropOldestExportSpanProcessor implements SpanProcessor {

    private static final long MIN_WORKER_AWAIT_NANOS = TimeUnit.MILLISECONDS.toNanos(1);

    private final SpanExporter exporter;
    private final int maxQueueSize;
    private final int maxExportBatchSize;
    private final long scheduleDelayNanos;
    private final long exportTimeoutNanos;
    private final long shutdownTimeoutNanos;

    private final ArrayDeque<SpanData> queue;
    private final ReentrantLock queueLock = new ReentrantLock();
    private final Condition queueNotEmpty = queueLock.newCondition();

    private final AtomicBoolean shutdownRequested = new AtomicBoolean(false);
    private final CompletableResultCode shutdownResult = new CompletableResultCode();
    private final CountDownLatch workerTerminated = new CountDownLatch(1);
    private final Thread workerThread;

    private final AtomicLong droppedSpansOverflow = new AtomicLong();
    private final AtomicLong droppedSpansAfterShutdown = new AtomicLong();
    private final AtomicLong exportFailures = new AtomicLong();
    private final AtomicLong exportTimeouts = new AtomicLong();

    private final AtomicBoolean exportFailureLogged = new AtomicBoolean();

    private final List<CompletableResultCode> pendingFlushes = new ArrayList<>();

    private PlatformDropOldestExportSpanProcessor(Builder builder) {
        this.exporter = builder.exporter;
        this.maxQueueSize = builder.maxQueueSize;
        this.maxExportBatchSize = builder.maxExportBatchSize;
        this.scheduleDelayNanos = builder.scheduleDelay.toNanos();
        this.exportTimeoutNanos = builder.exportTimeout.toNanos();
        this.shutdownTimeoutNanos = builder.shutdownTimeout.toNanos();
        this.queue = new ArrayDeque<>(maxQueueSize);

        this.workerThread = new Thread(this::workerLoop, "platform-tracing-drop-oldest-exporter");
        this.workerThread.setDaemon(true);
        this.workerThread.start();
    }

    /**
     * No-op: этот процессор работает только на стадии экспорта (по аналогии с {@code BatchSpanProcessor}).
     * Обогащение и валидация выполняются предшествующими делегатами в {@link PlatformCompositeSpanProcessor}.
     */
    @Override
    public void onStart(@NonNull Context parentContext, @NonNull ReadWriteSpan span) {
    }

    @Override
    public boolean isStartRequired() {
        return false;
    }

    @Override
    public void onEnd(ReadableSpan span) {
        if (!span.getSpanContext().isSampled()) {
            return;
        }

        if (shutdownRequested.get()) {
            droppedSpansAfterShutdown.incrementAndGet();
            return;
        }

        SpanData snapshot;
        try {
            snapshot = span.toSpanData();
        } catch (RuntimeException e) {
            logExportFailureOnce("toSpanData() failed: " + e.getMessage());
            return;
        }

        enqueueWithDropOldest(snapshot);
    }

    @Override
    public boolean isEndRequired() {
        return true;
    }

    @Override
    public CompletableResultCode forceFlush() {
        if (shutdownRequested.get()) {
            return CompletableResultCode.ofSuccess();
        }

        CompletableResultCode result = new CompletableResultCode();
        queueLock.lock();
        try {
            pendingFlushes.add(result);
            queueNotEmpty.signalAll();
        } finally {
            queueLock.unlock();
        }

        return result;
    }

    @Override
    public CompletableResultCode shutdown() {
        if (!shutdownRequested.compareAndSet(false, true)) {
            return shutdownResult;
        }

        queueLock.lock();
        try {
            queueNotEmpty.signalAll();
        } finally {
            queueLock.unlock();
        }

        Thread terminator = new Thread(() -> {
            boolean workerStopped;
            try {
                workerStopped = workerTerminated.await(shutdownTimeoutNanos, TimeUnit.NANOSECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                workerStopped = false;
            }

            if (!workerStopped) {
                log.warn("PlatformDropOldestExportSpanProcessor: worker не завершился за {} мс; принудительно прерываем",
                        TimeUnit.NANOSECONDS.toMillis(shutdownTimeoutNanos));

                workerThread.interrupt();
                queueLock.lock();
                try {
                    droppedSpansAfterShutdown.addAndGet(queue.size());
                    queue.clear();
                } finally {
                    queueLock.unlock();
                }
            }

            CompletableResultCode exporterShutdown;
            try {
                exporterShutdown = exporter.shutdown();
            } catch (RuntimeException e) {
                exportFailures.incrementAndGet();
                logExportFailureOnce("exporter.shutdown() failed: " + e.getMessage());
                shutdownResult.fail();
                return;
            }

            exporterShutdown.whenComplete(() -> {
                if (exporterShutdown.isSuccess()) {
                    shutdownResult.succeed();
                } else {
                    shutdownResult.fail();
                }
            });
        }, "platform-tracing-drop-oldest-shutdown");
        terminator.setDaemon(true);
        terminator.start();
        return shutdownResult;
    }

    private void enqueueWithDropOldest(SpanData snapshot) {
        queueLock.lock();
        try {
            if (shutdownRequested.get()) {
                droppedSpansAfterShutdown.incrementAndGet();
                return;
            }

            if (queue.size() >= maxQueueSize) {
                queue.pollFirst();
                droppedSpansOverflow.incrementAndGet();
            }

            queue.offerLast(snapshot);
            if (queue.size() >= maxExportBatchSize || !pendingFlushes.isEmpty()) {
                queueNotEmpty.signalAll();
            }
        } finally {
            queueLock.unlock();
        }
    }

    private void workerLoop() {
        long lastExportNanos = System.nanoTime();
        try {
            while (true) {
                List<SpanData> batch;
                List<CompletableResultCode> flushPromises;
                boolean shuttingDown;
                queueLock.lock();
                try {
                    long now = System.nanoTime();
                    long elapsedSinceExport = now - lastExportNanos;
                    while (!shouldExportNow(elapsedSinceExport)) {
                        long waitNanos = Math.max(MIN_WORKER_AWAIT_NANOS, scheduleDelayNanos - elapsedSinceExport);
                        try {
                            queueNotEmpty.awaitNanos(waitNanos);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            shuttingDown = true;
                            batch = drainBatchLocked();
                            flushPromises = drainPendingFlushesLocked();
                            queueLock.unlock();
                            tryExport(batch, flushPromises, true);
                            return;
                        }

                        elapsedSinceExport = System.nanoTime() - lastExportNanos;
                        if (shutdownRequested.get()) {
                            break;
                        }
                    }
                    shuttingDown = shutdownRequested.get();
                    batch = drainBatchLocked();
                    flushPromises = drainPendingFlushesLocked();
                } finally {
                    if (queueLock.isHeldByCurrentThread()) {
                        queueLock.unlock();
                    }
                }

                tryExport(batch, flushPromises, shuttingDown);
                lastExportNanos = System.nanoTime();
                if (shuttingDown && queueSizeSafe() == 0) {
                    return;
                }
            }
        } catch (RuntimeException unexpected) {
            exportFailures.incrementAndGet();
            log.error("PlatformDropOldestExportSpanProcessor worker завершился аварийно: {}",
                    unexpected.getMessage(), unexpected);
        } finally {
            workerTerminated.countDown();
        }
    }

    private boolean shouldExportNow(long elapsedSinceExportNanos) {
        if (shutdownRequested.get()) {
            return true;
        }

        if (!pendingFlushes.isEmpty()) {
            return true;
        }

        if (queue.size() >= maxExportBatchSize) {
            return true;
        }

        return !queue.isEmpty() && elapsedSinceExportNanos >= scheduleDelayNanos;
    }

    private List<SpanData> drainBatchLocked() {
        int n = Math.min(queue.size(), maxExportBatchSize);
        if (n == 0) {
            return List.of();
        }

        List<SpanData> batch = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            batch.add(queue.pollFirst());
        }

        return batch;
    }

    /** Должен вызываться под {@link #queueLock}. */
    private List<CompletableResultCode> drainPendingFlushesLocked() {
        if (pendingFlushes.isEmpty()) {
            return List.of();
        }
        List<CompletableResultCode> copy = new ArrayList<>(pendingFlushes);
        pendingFlushes.clear();
        return copy;
    }

    /**
     * Ограниченный по времени вызов {@link SpanExporter#export} с раздельным учётом
     * timeout / failure / success и завершением ожидающих {@code forceFlush}-промисов.
     * <p>
     * Если {@code shuttingDown} — продолжаем дренаж в цикле {@link #workerLoop}, пока очередь
     * не пуста (с тем же per-batch таймаутом).
     */
    private void tryExport(List<SpanData> batch, List<CompletableResultCode> flushPromises, boolean shuttingDown) {
        boolean success;
        if (batch.isEmpty()) {
            success = true;
        } else {
            success = exportBatch(batch);
        }
        if (shuttingDown) {
            // При shutdown дожимаем остаток очереди, чтобы выполнить контракт «дренаж до пустоты
            // или до shutdownTimeout». Каждая итерация ограничена per-batch exportTimeout.
            while (true) {
                List<SpanData> next;
                queueLock.lock();
                try {
                    if (queue.isEmpty()) {
                        break;
                    }
                    next = drainBatchLocked();
                } finally {
                    queueLock.unlock();
                }
                boolean ok = exportBatch(next);
                success = success && ok;
            }
        }
        // Завершаем все ожидающие forceFlush-промисы единым результатом.
        for (CompletableResultCode promise : flushPromises) {
            if (success) {
                promise.succeed();
            } else {
                promise.fail();
            }
        }
    }

    /**
     * Один цикл экспорта с ограничением по времени. Возвращает {@code true} при успешном
     * завершении в пределах {@code exportTimeout}, иначе {@code false}.
     */
    private boolean exportBatch(List<SpanData> batch) {
        CompletableResultCode resultCode;
        try {
            resultCode = exporter.export(batch);
        } catch (RuntimeException e) {
            exportFailures.incrementAndGet();
            logExportFailureOnce("exporter.export() threw: " + e.getMessage());
            return false;
        }
        boolean completed = resultCode.join(exportTimeoutNanos, TimeUnit.NANOSECONDS).isDone();
        if (!completed) {
            exportTimeouts.incrementAndGet();
            exportFailures.incrementAndGet();
            logExportFailureOnce("exporter.export() timed out after "
                    + TimeUnit.NANOSECONDS.toMillis(exportTimeoutNanos) + " ms");
            return false;
        }
        if (!resultCode.isSuccess()) {
            exportFailures.incrementAndGet();
            logExportFailureOnce("exporter.export() returned failure");
            return false;
        }
        return true;
    }

    private int queueSizeSafe() {
        queueLock.lock();
        try {
            return queue.size();
        } finally {
            queueLock.unlock();
        }
    }

    private void logExportFailureOnce(String message) {
        if (exportFailureLogged.compareAndSet(false, true)) {
            log.warn("PlatformDropOldestExportSpanProcessor: {} (subsequent failures throttled, см. exportFailures)",
                    message);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Геттеры наблюдаемости
    // ---------------------------------------------------------------------------------------------

    /** Количество спанов, вытесненных при переполнении очереди (drop-oldest). */
    public long getDroppedSpansOverflow() {
        return droppedSpansOverflow.get();
    }

    /** Количество спанов, отброшенных после {@link #shutdown()} (отдельная категория от overflow). */
    public long getDroppedSpansAfterShutdown() {
        return droppedSpansAfterShutdown.get();
    }

    /** Количество неуспешных {@code exporter.export()} (любая причина: throw / failure / timeout). */
    public long getExportFailures() {
        return exportFailures.get();
    }

    /** Подкатегория {@link #getExportFailures()}: превышение {@code exportTimeout}. */
    public long getExportTimeouts() {
        return exportTimeouts.get();
    }

    /** Configured максимальный размер очереди (immutable). */
    public int getQueueCapacity() {
        return maxQueueSize;
    }

    /** Текущий размер очереди (читается под lock'ом). */
    public int getQueueSize() {
        return queueSizeSafe();
    }

    public static Builder builder(SpanExporter exporter) {
        return new Builder(exporter);
    }

    public static final class Builder {
        private final SpanExporter exporter;
        private int maxQueueSize = DropOldestExportProcessorDefaults.defaultMaxQueueSize();
        private int maxExportBatchSize = DropOldestExportProcessorDefaults.defaultMaxExportBatchSize();
        private Duration scheduleDelay = DropOldestExportProcessorDefaults.defaultScheduleDelay();
        private Duration exportTimeout = DropOldestExportProcessorDefaults.defaultExportTimeout();
        private Duration shutdownTimeout = ExtensionDefaults.DEFAULT_DROP_OLDEST_SHUTDOWN_TIMEOUT;

        private Builder(SpanExporter exporter) {
            this.exporter = Objects.requireNonNull(exporter, "exporter");
        }

        public Builder maxQueueSize(int maxQueueSize) {
            this.maxQueueSize = maxQueueSize;
            return this;
        }

        public Builder maxExportBatchSize(int maxExportBatchSize) {
            this.maxExportBatchSize = maxExportBatchSize;
            return this;
        }

        public Builder scheduleDelay(Duration scheduleDelay) {
            this.scheduleDelay = scheduleDelay;
            return this;
        }

        public Builder exportTimeout(Duration exportTimeout) {
            this.exportTimeout = exportTimeout;
            return this;
        }

        public Builder shutdownTimeout(Duration shutdownTimeout) {
            this.shutdownTimeout = shutdownTimeout;
            return this;
        }

        public Builder readBspConfigFrom(ConfigProperties config) {
            Objects.requireNonNull(config, "config");

            Integer mqs = config.getInt("otel.bsp.max.queue.size");
            if (mqs != null) {
                this.maxQueueSize = mqs;
            }

            Integer mebs = config.getInt("otel.bsp.max.export.batch.size");
            if (mebs != null) {
                this.maxExportBatchSize = mebs;
            }

            Duration sd = config.getDuration("otel.bsp.schedule.delay");
            if (sd != null) {
                this.scheduleDelay = sd;
            }

            Duration et = config.getDuration("otel.bsp.export.timeout");
            if (et != null) {
                this.exportTimeout = et;
            }

            return this;
        }

        public PlatformDropOldestExportSpanProcessor build() {
            applyValidationWithSafeFallback();
            return new PlatformDropOldestExportSpanProcessor(this);
        }

        private void applyValidationWithSafeFallback() {
            if (maxQueueSize <= 0) {
                log.warn("PlatformDropOldestExportSpanProcessor.builder: maxQueueSize={} некорректен, fallback={}",
                        maxQueueSize, DropOldestExportProcessorDefaults.defaultMaxQueueSize());
                maxQueueSize = DropOldestExportProcessorDefaults.defaultMaxQueueSize();
            }

            if (maxExportBatchSize <= 0) {
                log.warn("PlatformDropOldestExportSpanProcessor.builder: maxExportBatchSize={} некорректен, fallback={}",
                        maxExportBatchSize, DropOldestExportProcessorDefaults.defaultMaxExportBatchSize());
                maxExportBatchSize = DropOldestExportProcessorDefaults.defaultMaxExportBatchSize();
            }

            if (maxExportBatchSize > maxQueueSize) {
                log.warn("PlatformDropOldestExportSpanProcessor.builder: maxExportBatchSize={} > maxQueueSize={}, "
                                + "fallback maxExportBatchSize={}",
                        maxExportBatchSize, maxQueueSize, maxQueueSize);
                maxExportBatchSize = maxQueueSize;
            }

            if (scheduleDelay == null || scheduleDelay.isNegative() || scheduleDelay.isZero()) {
                log.warn("PlatformDropOldestExportSpanProcessor.builder: scheduleDelay={} некорректен, fallback={}",
                        scheduleDelay, DropOldestExportProcessorDefaults.defaultScheduleDelay());
                scheduleDelay = DropOldestExportProcessorDefaults.defaultScheduleDelay();
            }

            if (exportTimeout == null || exportTimeout.isNegative() || exportTimeout.isZero()) {
                log.warn("PlatformDropOldestExportSpanProcessor.builder: exportTimeout={} некорректен, fallback={}",
                        exportTimeout, DropOldestExportProcessorDefaults.defaultExportTimeout());
                exportTimeout = DropOldestExportProcessorDefaults.defaultExportTimeout();
            }

            if (shutdownTimeout == null || shutdownTimeout.isNegative() || shutdownTimeout.isZero()) {
                log.warn("PlatformDropOldestExportSpanProcessor.builder: shutdownTimeout={} некорректен, fallback={}",
                        shutdownTimeout, ExtensionDefaults.DEFAULT_DROP_OLDEST_SHUTDOWN_TIMEOUT);
                shutdownTimeout = ExtensionDefaults.DEFAULT_DROP_OLDEST_SHUTDOWN_TIMEOUT;
            }
        }
    }
}
