package space.br1440.platform.tracing.otel.javaagent.sampler;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.api.attributes.PlatformSamplingReasons;
import space.br1440.platform.tracing.otel.javaagent.control.AcceptedPolicyMutationAccess;
import space.br1440.platform.tracing.otel.javaagent.control.CategoryBSamplingAccess;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.test.assertions.SamplerDecisionAssert;
import space.br1440.platform.tracing.test.assertions.SamplingAssertions;
import space.br1440.platform.tracing.test.harness.SamplerHarness;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class SamplingRuntimeUpdateCharacterizationTest {

    @RegisterExtension
    final AgentLogCapture settingsLogs = new AgentLogCapture();

    private static final String ROUTE_PATH = "/api/v1/orders";
    private static final double INITIAL_ROUTE_RATIO = 1.0d;
    private static final double DROPPED_ROUTE_RATIO = 0.0d;

    private static final double INITIAL_GLOBAL_RATIO = 0.5d;
    private static final double UPDATED_GLOBAL_RATIO = 0.1d;
    private static final int CONCURRENT_UPDATE_ITERATIONS = 200;
    private static final int READER_WRITER_POOL_SIZE = 2;
    private static final Duration WRITER_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration POOL_SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

    @Test
    void routeRatioUpdateChangesReasonFromGlobalToRoute() {
        // given
        TracingSettingsSource settings = TracingSettingsSourceTestFixtures.boundSampling(
                AgentInstanceId.random(), true, Map.of(), INITIAL_ROUTE_RATIO);
        try (CompositeSampler sampler = new CompositeSampler(
                settings,
                CategoryBSamplingAccess.snapshot(List.of(), List.of("on"))
        )) {
            // when
            SamplingResult globalResult = sampleRoute(sampler);

            // then
            SamplingAssertions.assertSamplingReason(globalResult, PlatformSamplingReasons.GLOBAL_RATIO);

            // when
            AcceptedPolicyMutationAccess.applySampling(
                    settings,
                    true,
                    INITIAL_ROUTE_RATIO,
                    Map.of(ROUTE_PATH, DROPPED_ROUTE_RATIO)
            );
            SamplingResult routeDropResult = sampleRoute(sampler);

            // then
            SamplerDecisionAssert.assertThat(routeDropResult).isDrop();
            SamplingAssertions.assertSamplingReason(routeDropResult, PlatformSamplingReasons.ROUTE_RATIO_DROP);
        }
    }

    @Test
    void parallelReadAfterValidUpdateDoesNotSeeInvalidRatio() throws InterruptedException {
        // given
        TracingSettingsSource settings = TracingSettingsSourceTestFixtures.boundSampling(
                AgentInstanceId.random(), true, Map.of(), INITIAL_GLOBAL_RATIO);

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch writerDone = new CountDownLatch(1);
        AtomicBoolean stopReader = new AtomicBoolean(false);
        AtomicReference<String> violation = new AtomicReference<>();

        // when
        try (CompositeSampler sampler = new CompositeSampler(
                settings,
                CategoryBSamplingAccess.snapshot(List.of(), List.of("on"))
        ); ExecutorService pool = Executors.newFixedThreadPool(READER_WRITER_POOL_SIZE)) {
            try {
                pool.submit(() -> runRatioWriter(settings, start, writerDone));
                pool.submit(() -> runRatioReader(settings, sampler, start, stopReader, violation));

                start.countDown();
                assertThat(writerDone.await(WRITER_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();

                stopReader.set(true);
                pool.shutdown();
                assertThat(pool.awaitTermination(POOL_SHUTDOWN_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
            } finally {
                stopReader.set(true);
                pool.shutdownNow();
            }
        }

        // then
        assertThat(violation.get()).isNull();
    }

    private static SamplingResult sampleRoute(CompositeSampler sampler) {
        return SamplerHarness.of(sampler)
                .spanKind(SpanKind.SERVER)
                .putStringAttribute("url.path", ROUTE_PATH)
                .sample();
    }

    private static void runRatioWriter(TracingSettingsSource settings,
                                       CountDownLatch start,
                                       CountDownLatch writerDone) {
        awaitUninterruptibly(start);
        try {
            for (int i = 0; i < CONCURRENT_UPDATE_ITERATIONS; i++) {
                AcceptedPolicyMutationAccess.applySampling(settings, true, UPDATED_GLOBAL_RATIO, Map.of());
            }
        } finally {
            writerDone.countDown();
        }
    }

    private static void runRatioReader(TracingSettingsSource settings,
                                       CompositeSampler sampler,
                                       CountDownLatch start,
                                       AtomicBoolean stopReader,
                                       AtomicReference<String> violation) {
        awaitUninterruptibly(start);
        while (!stopReader.get()) {
            double observedRatio = settings.current().samplingRatio();
            if (observedRatio != INITIAL_GLOBAL_RATIO && observedRatio != UPDATED_GLOBAL_RATIO) {
                violation.compareAndSet(null, "unexpected ratio: " + observedRatio);
                return;
            }

            SamplerHarness.of(sampler)
                    .spanKind(SpanKind.SERVER)
                    .sample();

            if (settings.current() == null) {
                violation.compareAndSet(null, "missing policy snapshot");
                return;
            }
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for test synchronization latch", e);
        }
    }
}
