package space.br1440.platform.tracing.otel.extension.sampler;

import io.opentelemetry.api.trace.SpanKind;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.attributes.PlatformSamplingReasons;
import space.br1440.platform.tracing.test.assertions.SamplerDecisionAssert;
import space.br1440.platform.tracing.test.assertions.SamplingAssertions;
import space.br1440.platform.tracing.test.harness.SamplerHarness;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterization: читатели sampling видят консistent snapshot после runtime update (PR-5A).
 * <p>
 * Дополняет {@link SamplerRuntimeUpdateConcurrencyTest} проверкой изменения decision/reason.
 */
class SamplingRuntimeUpdateCharacterizationTest {

    @Test
    void route_ratio_update_меняет_reason_с_global_на_route() throws Exception {
        SamplerStateHolder holder = new SamplerStateHolder(
                true, List.of(), List.of("on"), Map.of(), 1.0);
        CompositeSampler sampler = new CompositeSampler(holder);
        String urlPath = "/api/v1/orders";

        var globalResult = SamplerHarness.of(sampler)
                .spanKind(SpanKind.SERVER)
                .putStringAttribute("url.path", urlPath)
                .sample();
        SamplingAssertions.assertSamplingReason(globalResult, PlatformSamplingReasons.GLOBAL_RATIO);

        long versionBefore = holder.version();
        holder.update(new SamplerState(
                true, List.of(), Set.of("on"), Map.of("/api/v1/orders", 0.0), 1.0,
                versionBefore + 1, Instant.now(), "characterization"));

        var routeDrop = SamplerHarness.of(sampler)
                .spanKind(SpanKind.SERVER)
                .putStringAttribute("url.path", urlPath)
                .sample();
        SamplerDecisionAssert.assertThat(routeDrop).isDrop();
        SamplingAssertions.assertSamplingReason(routeDrop, PlatformSamplingReasons.ROUTE_RATIO_DROP);
    }

    @Test
    void параллельное_чтение_после_валидного_update_не_видит_невалидный_ratio() throws Exception {
        SamplerStateHolder holder = new SamplerStateHolder(
                true, List.of(), List.of("on"), Map.of(), 0.5);
        CompositeSampler sampler = new CompositeSampler(holder);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch writerDone = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean stopReader = new java.util.concurrent.atomic.AtomicBoolean(false);
        AtomicReference<String> violation = new AtomicReference<>();

        try {
            pool.submit(() -> {
                await(start);
                try {
                    for (int i = 0; i < 200; i++) {
                        holder.tryUpdate(prev -> new SamplerState(
                                true, List.of(), Set.of("on"), Map.of(), 0.1,
                                prev.version() + 1, Instant.now(), "writer"));
                    }
                } finally {
                    writerDone.countDown();
                }
            });

            pool.submit(() -> {
                await(start);
                while (!stopReader.get()) {
                    double ratio = holder.current().defaultRatio();
                    if (ratio != 0.5 && ratio != 0.1) {
                        violation.compareAndSet(null, "unexpected ratio: " + ratio);
                        return;
                    }
                    SamplerHarness.of(sampler)
                            .spanKind(SpanKind.SERVER)
                            .sample();
                    if (holder.current().policySnapshot() == null) {
                        violation.compareAndSet(null, "missing policy snapshot");
                        return;
                    }
                }
            });

            start.countDown();
            assertThat(writerDone.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            stopReader.set(true);
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(violation.get()).isNull();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
