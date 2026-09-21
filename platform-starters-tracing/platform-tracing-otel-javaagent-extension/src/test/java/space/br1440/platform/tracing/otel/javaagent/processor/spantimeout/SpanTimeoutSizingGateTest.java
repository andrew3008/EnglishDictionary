package space.br1440.platform.tracing.otel.javaagent.processor.spantimeout;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.openjdk.jol.info.ClassLayout;
import org.openjdk.jol.info.GraphLayout;

import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults;

import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;

class SpanTimeoutSizingGateTest {

    private static final int SAMPLE_SPANS = 10_000;
    private static final int MAX_TRACKED_TRACES = ExtensionDefaults.DEFAULT_SPAN_TIMEOUT_MAX_TRACKED_TRACES;
    private static final long SCAN_INTERVAL_NANOS = ExtensionDefaults.DEFAULT_SPAN_TIMEOUT_SCAN_INTERVAL.toNanos();
    private static final long SCAN_P99_BUDGET_NANOS = SCAN_INTERVAL_NANOS / 10;
    private static final long ABSOLUTE_HEAP_CEILING_BYTES = 32L * 1024 * 1024;
    private static final int SCAN_WARMUP = 5;
    private static final int SCAN_SAMPLES = 40;
    private static final int HEAP_CAPACITY_SEARCH_CEILING = 5_000_000;

    @Test
    void sizingGateSelectsDefaultFromHeapAndScanCeilings() throws Exception {
        // given
        System.setProperty("jol.magicFieldOffset", "true");
        printObjectLayouts();
        Profile manyTraces = measureProfile("many-traces-few-spans", SAMPLE_SPANS, 1);
        Profile fatTrace = measureProfile("few-traces-many-spans", 1, SAMPLE_SPANS);
        BurstResult burst = measureBurstAdmission();
        CasResult cas = measureCasPermitBurst();

        long bytesPerTracePlusSpan = Math.max(1L, manyTraces.retainedBytes / manyTraces.spanCount);
        long bytesPerExtraSpan = Math.max(1L, fatTrace.retainedBytes / fatTrace.spanCount);
        long scanNanosPerSpan = Math.max(1L, manyTraces.scanP99Nanos / manyTraces.spanCount);

        System.out.println("SIZING model bytesPerTracePlusSpan=" + bytesPerTracePlusSpan
                + " bytesPerExtraSpan=" + bytesPerExtraSpan
                + " scanP99PerSpanNanos=" + scanNanosPerSpan);
        System.out.println("SIZING burst onStartP99Nanos=" + burst.onStartP99Nanos
                + " casFinalCount=" + cas.finalCount
                + " casElapsedMs=" + cas.elapsedMillis);

        // when
        int capacityByScan = (int) Math.min(Integer.MAX_VALUE, SCAN_P99_BUDGET_NANOS / scanNanosPerSpan);
        System.out.println("SIZING capacityByScanCost=" + capacityByScan);

        int chosen = Integer.MAX_VALUE;
        for (int heapMiB : new int[] {512, 1024, 2048, 4096}) {
            long budget = heapBudgetBytes(heapMiB);
            int capacityByHeap = capacityByHeap(budget, bytesPerTracePlusSpan, bytesPerExtraSpan);
            int recommended = Math.min(capacityByHeap, capacityByScan);
            System.out.println("SIZING heapClass=" + heapMiB + "MiB budgetBytes=" + budget
                    + " capacityByHeap=" + capacityByHeap
                    + " recommendedMaxTrackedSpans=" + recommended);
            if (heapMiB == 512) {
                chosen = recommended;
            }
        }

        // then
        assertThat(manyTraces.retainedBytes).isPositive();
        assertThat(fatTrace.retainedBytes).isPositive();
        assertThat(manyTraces.scanP99Nanos).isLessThanOrEqualTo(SCAN_P99_BUDGET_NANOS);
        assertThat(fatTrace.scanP99Nanos).isLessThanOrEqualTo(SCAN_P99_BUDGET_NANOS);
        assertThat(estimatedHeap(chosen, bytesPerTracePlusSpan, bytesPerExtraSpan))
                .isLessThanOrEqualTo(heapBudgetBytes(512));
        assertThat((long) chosen * scanNanosPerSpan).isLessThanOrEqualTo(SCAN_P99_BUDGET_NANOS);
        assertThat(cas.finalCount).isEqualTo(10_000);
        assertThat(cas.elapsedMillis).isLessThan(2_000L);
        assertThat(burst.onStartP99Nanos).isLessThan(TimeUnit.MILLISECONDS.toNanos(50));
        assertThat(chosen).isGreaterThanOrEqualTo(1_024);
        int adopted = (chosen / 1_000) * 1_000;
        System.out.println("SIZING chosenDefaultMaxTrackedSpans=" + chosen
                + " adoptedDefaultMaxTrackedSpans=" + adopted
                + " adoptedPerTrace=1024");
        assertThat(adopted).isGreaterThanOrEqualTo(50_000);
    }

    private static void printObjectLayouts() {
        List<Class<?>> layoutTargets = List.of(
                InFlightTraceState.class, InFlightTraceState.Tracked.class, AdmissionBudget.class);
        for (Class<?> target : layoutTargets) {
            try {
                System.out.println("SIZING layout " + target.getSimpleName() + "="
                        + ClassLayout.parseClass(target).instanceSize());
            } catch (RuntimeException exception) {
                System.out.println("SIZING layout " + target.getSimpleName() + " unavailable: "
                        + exception.getMessage());
            }
        }
    }

    private static Profile measureProfile(String name, int traces, int spansPerTrace) throws Exception {
        int maxTraces = Math.max(MAX_TRACKED_TRACES, traces);
        AtomicLong clock = new AtomicLong();
        try (SpanTimeoutProcessor spanTimeout = new SpanTimeoutProcessor(
                Duration.ofHours(1), Duration.ofHours(1), clock::get, maxTraces, Integer.MAX_VALUE, Integer.MAX_VALUE)) {
            Object[] spans = new Object[traces * spansPerTrace];
            int spanOrdinal = 0;
            for (int traceIndex = 0; traceIndex < traces; traceIndex++) {
                for (int spanIndex = 0; spanIndex < spansPerTrace; spanIndex++) {
                    spans[spanOrdinal] = startSpan(spanTimeout, traceIndex + 1, ++spanOrdinal);
                }
            }
            assertThat(spanTimeout.getTrackingEvictedTotal()).isZero();
            assertThat(spanTimeout.getActiveSpanCount()).isEqualTo(traces * spansPerTrace);

            forceGc();
            Object index = activeTraces(spanTimeout);

            long retained = GraphLayout.parseInstance(index)
                    .subtract(GraphLayout.parseInstance(spans))
                    .totalSize();
            long heapUsed = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
            long scanP99 = scanP99(spanTimeout);
            System.out.println("SIZING profile=" + name
                    + " traces=" + traces
                    + " spans=" + (traces * spansPerTrace)
                    + " trackedTraces=" + spanTimeout.getTrackedTraceCount()
                    + " jolRetainedBytes=" + retained
                    + " heapUsedBytes=" + heapUsed
                    + " scanP99Nanos=" + scanP99);
            return new Profile(traces * spansPerTrace, retained, scanP99);
        }
    }

    private static BurstResult measureBurstAdmission() throws Exception {
        AtomicLong clock = new AtomicLong();
        int threads = 8;
        int perThread = 400;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        long[] samples = new long[threads * perThread];
        try (SpanTimeoutProcessor spanTimeout = new SpanTimeoutProcessor(
                Duration.ofHours(1), Duration.ofHours(1), clock::get,
                MAX_TRACKED_TRACES, Integer.MAX_VALUE, Integer.MAX_VALUE);
             ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            for (int thread = 0; thread < threads; thread++) {
                int threadIndex = thread;
                executor.execute(() -> {
                    try {
                        start.await();
                        for (int index = 0; index < perThread; index++) {
                            int ordinal = threadIndex * perThread + index + 1;
                            long began = System.nanoTime();
                            startSpan(spanTimeout, ordinal, ordinal);
                            samples[ordinal - 1] = System.nanoTime() - began;
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        }
        return new BurstResult(p99(samples));
    }

    private static CasResult measureCasPermitBurst() throws Exception {
        AtomicInteger acquired = new AtomicInteger();
        int cap = 10_000;
        int threads = 8;
        int attempts = 20_000;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        long began = System.nanoTime();
        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            for (int thread = 0; thread < threads; thread++) {
                executor.execute(() -> {
                    try {
                        start.await();
                        for (int index = 0; index < attempts; index++) {
                            tryAcquire(acquired, cap);
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        }
        return new CasResult(acquired.get(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began));
    }

    private static void tryAcquire(AtomicInteger acquired, int cap) {
        for (;;) {
            int current = acquired.get();
            if (current >= cap) {
                return;
            }
            if (acquired.compareAndSet(current, current + 1)) {
                return;
            }
        }
    }

    private static ReadWriteSpan startSpan(SpanTimeoutProcessor spanTimeout, int traceOrdinal, int spanOrdinal) {
        SpanContext context = SpanContext.create(
                String.format("%032x", traceOrdinal),
                String.format("%016x", spanOrdinal),
                TraceFlags.getSampled(),
                TraceState.getDefault());
        ReadWriteSpan span = liveSpan(context);
        spanTimeout.onStart(Context.root(), span);
        return span;
    }

    private static ReadWriteSpan liveSpan(SpanContext context) {
        return (ReadWriteSpan) Proxy.newProxyInstance(
                ReadWriteSpan.class.getClassLoader(),
                new Class<?>[] {ReadWriteSpan.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getSpanContext" -> context;
                    case "hasEnded" -> Boolean.FALSE;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "liveSpan" + context.getSpanId();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static long scanP99(SpanTimeoutProcessor spanTimeout) {
        for (int index = 0; index < SCAN_WARMUP; index++) {
            spanTimeout.forceFlush();
        }
        long[] samples = new long[SCAN_SAMPLES];
        for (int index = 0; index < SCAN_SAMPLES; index++) {
            long began = System.nanoTime();
            spanTimeout.forceFlush();
            samples[index] = System.nanoTime() - began;
        }
        return p99(samples);
    }

    private static long p99(long[] samples) {
        long[] copy = Arrays.copyOf(samples, samples.length);
        Arrays.sort(copy);
        int rank = (int) Math.ceil((99 / 100.0d) * copy.length) - 1;
        return copy[Math.max(0, rank)];
    }

    private static int capacityByHeap(long budgetBytes, long bytesPerTracePlusSpan, long bytesPerExtraSpan) {
        int capacity = 1;
        while (capacity < HEAP_CAPACITY_SEARCH_CEILING) {
            int next = capacity + 1;
            if (estimatedHeap(next, bytesPerTracePlusSpan, bytesPerExtraSpan) > budgetBytes) {
                return capacity;
            }
            capacity = next;
        }
        return capacity;
    }

    private static long estimatedHeap(int spans, long bytesPerTracePlusSpan, long bytesPerExtraSpan) {
        if (spans <= MAX_TRACKED_TRACES) {
            return (long) spans * bytesPerTracePlusSpan;
        }
        return (long) MAX_TRACKED_TRACES * bytesPerTracePlusSpan
                + (long) (spans - MAX_TRACKED_TRACES) * bytesPerExtraSpan;
    }

    private static long heapBudgetBytes(int heapMiB) {
        long twoPercent = (heapMiB * 1024L * 1024L) / 50L;
        return Math.min(ABSOLUTE_HEAP_CEILING_BYTES, twoPercent);
    }

    private static Object activeTraces(SpanTimeoutProcessor spanTimeout) throws Exception {
        InFlightTraceIndex index = index(spanTimeout);
        Field field = InFlightTraceIndex.class.getDeclaredField("traces");
        field.setAccessible(true);
        Object value = field.get(index);
        assertThat(value).isInstanceOf(ConcurrentHashMap.class);
        return value;
    }

    private static InFlightTraceIndex index(SpanTimeoutProcessor spanTimeout) throws Exception {
        Field field = SpanTimeoutProcessor.class.getDeclaredField("index");
        field.setAccessible(true);
        return (InFlightTraceIndex) field.get(spanTimeout);
    }

    private static void forceGc() throws InterruptedException {
        System.gc();
        TimeUnit.MILLISECONDS.sleep(50);
        System.gc();
        TimeUnit.MILLISECONDS.sleep(50);
    }

    private record Profile(int spanCount, long retainedBytes, long scanP99Nanos) {
    }

    private record BurstResult(long onStartP99Nanos) {
    }

    private record CasResult(int finalCount, long elapsedMillis) {
    }
}
