package space.br1440.platform.tracing.otel.extension.sampler;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.attributes.PlatformSamplingReasons;
import space.br1440.platform.tracing.api.propagation.control.PlatformTraceContextKeys;
import space.br1440.platform.tracing.otel.extension.jmx.sampling.PlatformSamplingControl;
import space.br1440.platform.tracing.test.assertions.SamplerDecisionAssert;
import space.br1440.platform.tracing.test.assertions.SamplingAssertions;
import space.br1440.platform.tracing.test.harness.InboundTraceControls;
import space.br1440.platform.tracing.test.harness.SamplerHarness;

import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR-6D: atomic runtime sampling policy update via JMX bridge → {@link SamplerStateHolder}.
 */
class SamplingPolicyRuntimeUpdateJmxTest {

    @Test
    void updateSamplingPolicy_arrays_updatesDomainAndCompositeSampler() {
        SamplerStateHolder holder = new SamplerStateHolder(
                true, List.of(), List.of("on"), Map.of(), 1.0);
        CompositeSampler sampler = new CompositeSampler(holder);
        PlatformSamplingControl control = new PlatformSamplingControl(holder, sampler, new LongAdder());

        control.updateSamplingPolicy(
                true,
                0.0,
                new String[]{"/actuator"},
                new String[]{"force-me"},
                new String[]{"/api/v1/critical"},
                new double[]{1.0},
                "pr-6d-test");

        SamplerState state = holder.current();
        assertThat(state.source()).isEqualTo("pr-6d-test");
        assertThat(state.droppedRoutes()).containsExactly("/actuator");
        assertThat(state.forceRecordValues()).contains("force-me");
        assertThat(state.routeRatios()).containsEntry("/api/v1/critical", 1.0);

        Context forceContext = Context.root().with(
                PlatformTraceContextKeys.TRACE_CONTROL,
                InboundTraceControls.of(true, false, null, PlatformSamplingReasons.FORCE_HEADER, "force-me"));
        var forceResult = SamplerHarness.of(sampler)
                .spanKind(SpanKind.SERVER)
                .parentContext(forceContext)
                .sample();
        SamplingAssertions.assertSamplingReason(forceResult, PlatformSamplingReasons.FORCE_HEADER);

        var dropResult = SamplerHarness.of(sampler)
                .spanKind(SpanKind.SERVER)
                .putStringAttribute("url.path", "/actuator/health")
                .sample();
        SamplerDecisionAssert.assertThat(dropResult).isDrop();
        SamplingAssertions.assertSamplingReason(dropResult, PlatformSamplingReasons.DROP_PATH);

        var routeSample = SamplerHarness.of(sampler)
                .spanKind(SpanKind.SERVER)
                .putStringAttribute("url.path", "/api/v1/critical/x")
                .sample();
        SamplerDecisionAssert.assertThat(routeSample).isRecordAndSample();
        SamplingAssertions.assertSamplingReason(routeSample, PlatformSamplingReasons.ROUTE_RATIO);
    }

    @Test
    void invalidUpdate_keepsLkg_versionUnchanged_incrementsInvalidCounter() {
        SamplerStateHolder holder = new SamplerStateHolder(
                true, List.of(), List.of("on"), Map.of("/old", 0.2), 0.3);
        LongAdder counter = new LongAdder();
        PlatformSamplingControl control = new PlatformSamplingControl(holder, null, counter);
        long v0 = holder.version();

        assertThatThrownBy(() -> control.updateSamplingPolicy(
                true, 0.5, null, null, new String[]{"/api"}, new double[]{0.1, 0.2}, "bad"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(holder.version()).isEqualTo(v0);
        assertThat(holder.current().defaultRatio()).isEqualTo(0.3);
        assertThat(holder.current().routeRatios()).containsEntry("/old", 0.2);
        assertThat(counter.sum()).isEqualTo(1);
    }

    @Test
    void nullForceValue_rejectedWithoutVersionBump() {
        SamplerStateHolder holder = new SamplerStateHolder(
                true, List.of(), List.of("on"), Map.of(), 0.5);
        PlatformSamplingControl control = new PlatformSamplingControl(holder, null, new LongAdder());
        long v0 = holder.version();

        assertThatThrownBy(() -> control.updateSamplingPolicy(
                true, 0.5, null, new String[]{null}, null, null, "bad"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(holder.version()).isEqualTo(v0);
    }

    @Test
    void concurrentUpdateSamplingPolicy_preservesMonotonicVersion() throws Exception {
        SamplerStateHolder holder = new SamplerStateHolder(
                true, List.of(), List.of("on"), Map.of(), 0.5);
        PlatformSamplingControl control = new PlatformSamplingControl(holder, null, new LongAdder());
        long v0 = holder.version();
        int threads = 4;
        int updatesPerThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicReference<String> violation = new AtomicReference<>();

        for (int t = 0; t < threads; t++) {
            double ratio = 0.1 + (t * 0.1);
            pool.submit(() -> {
                await(start);
                try {
                    for (int i = 0; i < updatesPerThread; i++) {
                        control.updateSamplingPolicy(
                                true,
                                ratio,
                                new String[]{"/drop-" + ratio},
                                new String[]{"on"},
                                new String[]{"/route-" + ratio},
                                new double[]{ratio},
                                "concurrency");
                    }
                } catch (RuntimeException e) {
                    violation.compareAndSet(null, e.getMessage());
                } finally {
                    done.countDown();
                }
            });
        }

        CompositeSampler sampler = new CompositeSampler(holder);
        pool.submit(() -> {
            await(start);
            while (done.getCount() > 0) {
                try {
                    SamplerHarness.of(sampler).spanKind(SpanKind.SERVER).sample();
                    if (holder.current().policySnapshot() == null) {
                        violation.compareAndSet(null, "missing snapshot");
                        return;
                    }
                } catch (RuntimeException e) {
                    violation.compareAndSet(null, e.getMessage());
                    return;
                }
            }
        });

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(violation.get()).isNull();
        assertThat(holder.version()).isEqualTo(v0 + (long) threads * updatesPerThread);
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
