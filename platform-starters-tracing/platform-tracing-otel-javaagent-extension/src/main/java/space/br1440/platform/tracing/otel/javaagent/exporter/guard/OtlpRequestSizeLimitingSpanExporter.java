package space.br1440.platform.tracing.otel.javaagent.exporter.guard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

import space.br1440.platform.tracing.otel.javaagent.configuration.OtelSdkDefaults;
import space.br1440.platform.tracing.otel.javaagent.exporter.response.ExportResponseObservation;
import space.br1440.platform.tracing.otel.javaagent.exporter.response.ExportResponseScope;
import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

public final class OtlpRequestSizeLimitingSpanExporter {

    private final SpanExporter delegate;
    private final int maxRequestBytes;
    private final Supplier<ToIntFunction<SpanData>> sizeMeasurers;

    public OtlpRequestSizeLimitingSpanExporter(SpanExporter delegate, int maxRequestBytes) {
        this(delegate, maxRequestBytes, OtlpSpanSizeMeasurer::new);
    }

    OtlpRequestSizeLimitingSpanExporter(SpanExporter delegate,
                                        int maxRequestBytes,
                                        Supplier<ToIntFunction<SpanData>> sizeMeasurers) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.sizeMeasurers = Objects.requireNonNull(sizeMeasurers, "sizeMeasurers");

        if (maxRequestBytes <= 0 || maxRequestBytes > OtelSdkDefaults.MAX_OTLP_REQUEST_SIZE_BYTES) {
            throw new IllegalArgumentException("maxRequestBytes must be positive and not exceed the platform limit: " + maxRequestBytes);
        }

        this.maxRequestBytes = maxRequestBytes;
    }

    public CompletableResultCode export(List<SpanData> spans, ExportResponseObservation parent) {
        Objects.requireNonNull(spans, "spans");
        Objects.requireNonNull(parent, "parent");

        SpanOutcomeAccumulator outcomes = new SpanOutcomeAccumulator();
        List<List<SpanData>> chunks = pack(spans, outcomes);
        List<ExportResponseObservation> observations = new ArrayList<>(chunks.size());
        List<CompletableResultCode> results = new ArrayList<>(chunks.size());

        for (List<SpanData> chunk : chunks) {
            ExportResponseObservation observation = new ExportResponseObservation(chunk.size());
            observations.add(observation);

            try (ExportResponseScope.Handle ignored = ExportResponseScope.open(observation)) {
                CompletableResultCode resultCode = Objects.requireNonNull(delegate.export(chunk), "delegate export result");
                results.add(resultCode);
            } catch (Throwable failure) {
                PlatformThrowables.propagateIfFatal(failure);
                results.add(CompletableResultCode.ofExceptionalFailure(failure));
            }
        }

        CompletableResultCode result = new CompletableResultCode();
        CompletableResultCode all = CompletableResultCode.ofAll(results);
        all.whenComplete(() -> {
            try {
                for (int index = 0; index < results.size(); index++) {
                    ExportResponseObservation observation = observations.get(index);
                    if (results.get(index).isSuccess()) {
                        outcomes.fold(observation);
                    } else {
                        outcomes.transportFailed(observation.effectiveSpanCount());
                    }
                }

                parent.recordAggregate(outcomes.toAggregate(results.size()));

                if (outcomes.transportDropped() == 0) {
                    result.succeed();
                } else {
                    result.failExceptionally(all.getFailureThrowable());
                }
            } catch (Throwable failure) {
                try {
                    result.failExceptionally(failure);
                } finally {
                    PlatformThrowables.propagateIfFatal(failure);
                }
            }
        });

        return result;
    }

    private List<List<SpanData>> pack(Collection<SpanData> spans, SpanOutcomeAccumulator outcomes) {
        List<List<SpanData>> chunks = new ArrayList<>();
        List<SpanData> chunk = new ArrayList<>();
        long chunkBytes = 0;

        ToIntFunction<SpanData> sizeMeasurer = sizeMeasurers.get();
        for (SpanData span : spans) {
            int size;
            try {
                switch (SpanSizeLowerBound.check(span, maxRequestBytes)) {
                    case EXCEEDS_LIMIT -> {
                        outcomes.dropOversized();
                        continue;
                    }

                    case VALUE_TOO_DEEP -> {
                        outcomes.measurementFailed(
                                new IllegalStateException("attribute value nesting exceeds " + SpanSizeLowerBound.MAX_VALUE_DEPTH)
                        );
                        continue;
                    }

                    case WITHIN_LIMIT -> {
                    }
                }

                size = sizeMeasurer.applyAsInt(span);
            } catch (Throwable failure) {
                PlatformThrowables.propagateIfFatal(failure);
                outcomes.measurementFailed(failure);
                continue;
            }

            if (size < 0) {
                outcomes.measurementFailed(new IllegalStateException("negative serialized size"));
            } else if (size > maxRequestBytes) {
                outcomes.dropOversized();
            } else {
                if ((chunkBytes + size) > maxRequestBytes) {
                    chunks.add(List.copyOf(chunk));
                    chunk.clear();
                    chunkBytes = 0;
                }

                chunk.add(span);
                chunkBytes += size;
            }
        }

        if (!chunk.isEmpty()) {
            chunks.add(List.copyOf(chunk));
        }

        return chunks;
    }

    public CompletableResultCode flush() {
        return Objects.requireNonNull(delegate.flush(), "delegate flush result");
    }

    public CompletableResultCode shutdown() {
        return Objects.requireNonNull(delegate.shutdown(), "delegate shutdown result");
    }
}
