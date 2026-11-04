package space.br1440.platform.tracing.otel.javaagent.sampler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsWriter;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateAttempt;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateStatus;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingStartupRules;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;

class SamplingRevisionConsistencyTest {

    private static final AttributeKey<String> REASON = AttributeKey.stringKey("platform.sampling.reason");

    @Test
    void eachDecisionReadsExactlyOneAcceptedRevision() {
        // given
        TracingSettingsSource source = spy(source());
        CompositeSampler sampler = sampler(source);
        clearInvocations(source);

        // when
        SamplingResult result = sample(sampler);

        // then
        verify(source, times(1)).current();
        assertThat(result.getDecision()).isEqualTo(SamplingDecision.DROP);
        assertThat(result.getAttributes().get(REASON)).isEqualTo("route_ratio_drop");
    }

    @Test
    void concurrentPublicationNeverMixesRouteAndDefaultRatioAndKeepsCounters() throws Exception {
        // given
        TracingSettingsSource source = source();
        CompositeSampler sampler = sampler(source);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch firstRead = new CountDownLatch(1);
        int decisions = 20000;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> writer = pool.submit(() -> {
                await(start);
                await(firstRead);
                for (int i = 0; i < 200; i++) {
                    TracingSettingsRevision previous = source.current();
                    TracingSettings current = previous.policy();
                    boolean routeEnabled = (i & 1) != 0;
                    TracingSettings candidate = TracingSettings.of(true, routeEnabled ? 1.0 : 0.0,
                            routeEnabled ? Map.of("/x", 0.0) : Map.of(), current.validationLevel(),
                            current.exporterEnabled(), current.traceControlHeadersEnabled(), current.diagnosticsLogLevel());
                    assertThat(source.updater().apply(new SettingsUpdateAttempt(UUID.randomUUID().toString(),
                            SettingsWriter.CONFIG_SERVER), previous.base(), candidate).outcome())
                            .isEqualTo(SettingsUpdateStatus.APPLIED);
                }
            });
            Future<?> reader = pool.submit(() -> {
                await(start);
                for (int i = 0; i < decisions; i++) {
                    SamplingResult result = sample(sampler);
                    firstRead.countDown();
                    assertThat(result.getDecision()).isEqualTo(SamplingDecision.DROP);
                    assertThat(result.getAttributes().get(REASON)).isIn("route_ratio_drop", "global_ratio_drop");
                }
            });

            // when
            start.countDown();
            reader.get(20, TimeUnit.SECONDS);
            writer.get(20, TimeUnit.SECONDS);

            // then
            assertThat(sampler.getDecisionCounts().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(decisions);
            assertThat(source.current().acceptedSequence()).isEqualTo(200);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static TracingSettingsSource source() {
        return TracingSettingsSourceTestFixtures.boundSampling(AgentInstanceId.random(), true, Map.of("/x", 0.0), 1.0);
    }

    private static CompositeSampler sampler(TracingSettingsSource source) {
        return new CompositeSampler(source, SamplingStartupRules.create(List.of(), List.of()));
    }

    private static SamplingResult sample(CompositeSampler sampler) {
        return sampler.shouldSample(Context.root(), "00000000000000000000000000000001", "test",
                SpanKind.SERVER, Attributes.of(AttributeKey.stringKey("url.path"), "/x"), List.of());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Sampling concurrency barrier timed out");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Sampling concurrency barrier interrupted", failure);
        }
    }
}
