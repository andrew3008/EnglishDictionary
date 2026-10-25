package space.br1440.platform.tracing.otel.javaagent.processor.spantimeout;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.event.SpanTimeoutLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;
import space.br1440.platform.tracing.otel.javaagent.processor.ProcessorDiagnosticIdentity;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;

/**
 * {@link SpanProcessor}, принудительно завершающий span'ы, которые не были закрыты в отведённое время.
 * <p>
 * Контролируются два лимита:
 * <ul>
 *   <li><b>span</b> — максимальное время от {@code startNanos} span'а до его завершения
 *       ({@link ExtensionPropertyNames#SPAN_TIMEOUT_MAX_SPAN_AGE});</li>
 *   <li><b>trace</b> — максимальное время отслеживания {@code traceId} в текущем процессе
 *       ({@link ExtensionPropertyNames#SPAN_TIMEOUT_MAX_TRACE_AGE}); при превышении
 *       принудительно завершаются все незакрытые span'ы этого trace.</li>
 * </ul>
 * При принудительном завершении процессор:
 * <ol>
 *   <li>устанавливает {@link PlatformAttributes#PLATFORM_TIMEOUT} = {@code span} | {@code trace}
 *       и {@link PlatformAttributes#PLATFORM_RESULT} = {@code timeout};</li>
 *   <li>не изменяет статус span'а;</li>
 *   <li>вызывает {@code span.end()}, после чего span проходит обычную цепочку процессоров и экспорт.</li>
 * </ol>
 * Лимиты проверяются периодически ({@link ExtensionPropertyNames#SPAN_TIMEOUT_SCAN_INTERVAL}),
 * поэтому span может быть завершён позже лимита, но не более чем на один интервал.
 */
public final class SpanTimeoutProcessor implements SpanProcessor, ProcessorDiagnosticIdentity {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(SpanTimeoutProcessor.class);

    private static final String DIAGNOSTIC_ID = JmxWireContract.KEY_PROCESSOR_ERRORS_BY_NAME_SPAN_TIMEOUT;

    private final LongSupplier nanoTime;
    private final InFlightTraceIndex index;
    private final SpanTimeoutScan scan;
    private final SpanTimeoutScanner scanner;
    private final AtomicBoolean admissionOpen = new AtomicBoolean(true);

    public static SpanTimeoutProcessor start(Duration spanTimeout,
                                             Duration traceTimeout,
                                             Duration scanInterval,
                                             int maxTrackedTraces,
                                             int maxTrackedSpansPerTrace,
                                             int maxTrackedSpans) {
        return start(spanTimeout, traceTimeout, scanInterval, System::nanoTime, System::currentTimeMillis,
                maxTrackedTraces, maxTrackedSpansPerTrace, maxTrackedSpans);
    }

    static SpanTimeoutProcessor start(Duration spanTimeout,
                                      Duration traceTimeout,
                                      Duration scanInterval,
                                      LongSupplier nanoTime,
                                      LongSupplier epochMillis,
                                      int maxTrackedTraces,
                                      int maxTrackedSpansPerTrace,
                                      int maxTrackedSpans) {
        SpanTimeoutProcessor processor = new SpanTimeoutProcessor(
                spanTimeout, traceTimeout, nanoTime, epochMillis,
                maxTrackedTraces, maxTrackedSpansPerTrace, maxTrackedSpans
        );

        processor.scanner.start(scanInterval);
        return processor;
    }

    SpanTimeoutProcessor(Duration spanTimeout,
                         Duration traceTimeout,
                         LongSupplier nanoTime,
                         int maxTrackedTraces,
                         int maxTrackedSpansPerTrace,
                         int maxTrackedSpans) {
        this(spanTimeout, traceTimeout, nanoTime, System::currentTimeMillis,
                maxTrackedTraces, maxTrackedSpansPerTrace, maxTrackedSpans);
    }

    SpanTimeoutProcessor(Duration spanTimeout,
                         Duration traceTimeout,
                         LongSupplier nanoTime,
                         LongSupplier epochMillis,
                         int maxTrackedTraces,
                         int maxTrackedSpansPerTrace,
                         int maxTrackedSpans) {
        int trueMaxTrackedTraces = requirePositiveCapacity(maxTrackedTraces, "maxTrackedTraces");
        int trueMaxTrackedSpansPerTrace = requirePositiveCapacity(maxTrackedSpansPerTrace, "maxTrackedSpansPerTrace");
        int trueMaxTrackedSpans = requirePositiveCapacity(maxTrackedSpans, "maxTrackedSpans");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.index = new InFlightTraceIndex(
                spanTimeout.toNanos(),
                traceTimeout.toNanos(),
                trueMaxTrackedTraces,
                trueMaxTrackedSpansPerTrace,
                trueMaxTrackedSpans,
                this.nanoTime
        );
        this.scan = new SpanTimeoutScan(index, this.nanoTime);
        this.scanner = new SpanTimeoutScanner(scan, epochMillis);
    }

    private static int requirePositiveCapacity(int value, String propertyName) {
        if (value <= 0) {
            throw new IllegalArgumentException(propertyName + " must be greater than zero");
        }

        return value;
    }

    @Override
    public String diagnosticId() {
        return DIAGNOSTIC_ID;
    }

    @Override
    public void onStart(@Nonnull Context parentContext, @Nonnull ReadWriteSpan span) {
        if (!admissionOpen.get()) {
            return;
        }

        String spanId = span.getSpanContext().getSpanId();
        String traceId = span.getSpanContext().getTraceId();

        long now = nanoTime.getAsLong();
        index.admit(traceId, spanId, span, now, admissionOpen::get);
    }

    @Override
    public boolean isStartRequired() {
        return true;
    }

    @Override
    public void onEnd(@Nonnull ReadableSpan span) {
        String spanId = span.getSpanContext().getSpanId();
        String traceId = span.getSpanContext().getTraceId();
        index.onEnd(traceId, spanId, span);
    }

    @Override
    public boolean isEndRequired() {
        return true;
    }

    @Override
    public CompletableResultCode shutdown() {
        admissionOpen.set(false);

        boolean scannerStopped = scanner.stop();

        int leakedSpans = getActiveSpanCount();
        if (leakedSpans > 0) {
            log.log(SpanTimeoutLogEvent.UNFINISHED_SPANS_AT_SDK_SHUTDOWN,
                    fields -> fields.putLong(AgentLogField.COUNT, leakedSpans));
        }

        index.drainAll();

        return scannerStopped ? CompletableResultCode.ofSuccess() : CompletableResultCode.ofFailure();
    }

    @Override
    public CompletableResultCode forceFlush() {
        return scan.run() ? CompletableResultCode.ofSuccess() : CompletableResultCode.ofFailure();
    }

    public long getForcedSpanEndAttemptsTotal() {
        return scan.forcedSpanEndAttemptsTotal();
    }

    public long getForcedTraceEndAttemptsTotal() {
        return scan.forcedTraceEndAttemptsTotal();
    }

    public long getScanFailuresTotal() {
        return scan.scanFailuresTotal();
    }

    public long getForceEndFailuresTotal() {
        return scan.forceEndFailuresTotal();
    }

    public long getLastSuccessfulBackgroundScanEpochMillis() {
        return scanner.lastSuccessfulScanEpochMillis();
    }

    public SpanTimeoutScannerState getScannerState() {
        return scanner.state();
    }

    public int getActiveSpanCount() {
        return index.trackedSpanCount();
    }

    public int getActiveTraceCount() {
        return index.nonEmptyTraceCount();
    }

    public int getTrackedTraceCount() {
        return index.size();
    }

    public int getTrackedTraceCapacity() {
        return index.traceCapacity();
    }

    public long getTrackingEvictedTotal() {
        return index.trackingEvictedTotal();
    }

    public int getTrackedSpanCapacity() {
        return index.spanCapacity();
    }

    public int getTrackedSpanPerTraceCapacity() {
        return index.perTraceCapacity();
    }

    public long getSpansRejectedPerTraceTotal() {
        return index.rejectedPerTraceTotal();
    }

    public long getSpansRejectedGlobalTotal() {
        return index.rejectedGlobalTotal();
    }

    public long getSpansRejectedTraceCapacityTotal() {
        return index.rejectedTraceCapacityTotal();
    }

    public long getSpanPermitInvariantViolationTotal() {
        return index.permitInvariantViolationTotal();
    }
}
