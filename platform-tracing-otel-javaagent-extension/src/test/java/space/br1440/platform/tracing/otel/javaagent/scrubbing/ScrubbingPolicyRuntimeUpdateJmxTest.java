package space.br1440.platform.tracing.otel.javaagent.scrubbing;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import jakarta.annotation.Nonnull;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.jmx.scrubbing.PlatformScrubbingControl;
import space.br1440.platform.tracing.otel.javaagent.processor.ScrubbingSpanProcessor;

import java.util.concurrent.atomic.LongAdder;
import space.br1440.platform.tracing.test.assertions.ScrubbingAssertions;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR-7B: atomic runtime scrubbing policy update via JMX bridge → {@link ScrubbingPolicyHolder}.
 */
class ScrubbingPolicyRuntimeUpdateJmxTest {

    @Test
    void updateScrubbingPolicy_publishes_enabled_rules_source_and_version() {
        ScrubbingSpanProcessor processor = processorWith("password");
        PlatformScrubbingControl control = new PlatformScrubbingControl(processor, new LongAdder());
        long v0 = processor.getPolicyVersion();

        control.updateScrubbingPolicy(true, new String[]{"password", "jwt"}, "pr-7b-test");

        assertThat(processor.getPolicyVersion()).isEqualTo(v0 + 1);
        assertThat(processor.getPolicySource()).isEqualTo("pr-7b-test");
        assertThat(processor.getRuleCount()).isEqualTo(2);
        assertThat(processor.isEnabled()).isTrue();
    }

    @Test
    void disabled_update_passthrough_without_reconstruct() {
        ScrubbingSpanProcessor processor = processorWith("password");
        PlatformScrubbingControl control = new PlatformScrubbingControl(processor, new LongAdder());

        control.updateScrubbingPolicy(false, new String[]{"password"}, "pr-7b-test");
        assertThat(processor.isEnabled()).isFalse();

        try (SpanProcessorHarness h = SpanProcessorHarness.of(processor)) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("op").startSpan();
            span.setAttribute("db.password", "secret");
            span.end();
            ScrubbingAssertions.assertStringAttributePreserved(
                    h.exporter().getFinishedSpanItems().get(0), "db.password", "secret");
        }
    }

    @Test
    void reenabled_update_applies_new_rules_without_reconstruct() {
        ScrubbingSpanProcessor processor = processorWith("password");
        PlatformScrubbingControl control = new PlatformScrubbingControl(processor, new LongAdder());

        control.updateScrubbingPolicy(true, new String[]{"jwt"}, "reload");

        try (SpanProcessorHarness h = SpanProcessorHarness.of(processor)) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("op").startSpan();
            span.setAttribute("token.value", "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.sig");
            span.end();
            assertThat(h.exporter().getFinishedSpanItems().get(0).getAttributes()
                    .get(AttributeKey.stringKey("token.value"))).isEmpty();
        }
    }

    @Test
    void nullRuleName_rejected_keepsLkg_incrementsInvalidCounter() {
        ScrubbingSpanProcessor processor = processorWith("password");
        LongAdder counter = new LongAdder();
        PlatformScrubbingControl control = new PlatformScrubbingControl(processor, counter);
        long v0 = processor.getPolicyVersion();

        assertThatThrownBy(() -> control.updateScrubbingPolicy(
                true, new String[]{"password", null}, "bad"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(processor.getPolicyVersion()).isEqualTo(v0);
        assertThat(processor.getRuleCount()).isEqualTo(1);
        assertThat(counter.sum()).isEqualTo(1);
    }

    @Test
    void tooManyRules_rejected_keepsLkg_incrementsInvalidCounter() {
        ScrubbingSpanProcessor processor = processorWith("password");
        LongAdder counter = new LongAdder();
        PlatformScrubbingControl control = new PlatformScrubbingControl(processor, counter);
        long v0 = processor.getPolicyVersion();
        String[] names = new String[201];
        java.util.Arrays.fill(names, "password");

        assertThatThrownBy(() -> control.updateScrubbingPolicy(true, names, "bad"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(processor.getPolicyVersion()).isEqualTo(v0);
        assertThat(counter.sum()).isEqualTo(1);
    }

    @Test
    void blankSource_normalizedToJmx() {
        ScrubbingSpanProcessor processor = processorWith("password");
        PlatformScrubbingControl control = new PlatformScrubbingControl(processor, new LongAdder());

        control.updateScrubbingPolicy(true, new String[]{"password"}, "  ");

        assertThat(processor.getPolicySource()).isEqualTo("JMX");
    }

    @Test
    void unknownRuleName_skipped_preservesLkgSemantics() {
        ScrubbingSpanProcessor processor = processorWith("password");
        PlatformScrubbingControl control = new PlatformScrubbingControl(processor, new LongAdder());

        control.updateScrubbingPolicy(true, new String[]{"not-a-real-rule"}, "test");

        assertThat(processor.getRuleCount()).isZero();
        assertThat(processor.isEnabled()).isTrue();
    }

    @Test
    void invalidRegexRule_keepsLkg_onCompileFailure() {
        ScrubbingPolicyHolder holder = new ScrubbingPolicyHolder(
                ScrubbingSnapshot.fromRules(true, List.of(BuiltInSpanAttributeScrubbingRules.resolve("password")),
                        1, java.time.Instant.now(), "startup"));
        long v0 = holder.version();

        boolean applied = holder.tryUpdate(prev -> ScrubbingSnapshot.fromRules(
                true,
                List.of(new InvalidRegexBuiltInRule()),
                prev.version() + 1,
                java.time.Instant.now(),
                "bad-regex"));

        assertThat(applied).isFalse();
        assertThat(holder.version()).isEqualTo(v0);
        assertThat(holder.current().wrappers()).hasSize(1);
    }

    @Test
    void concurrentUpdateScrubbingPolicy_monotonicVersion_noPartialState() throws Exception {
        ScrubbingSpanProcessor processor = processorWith("password", "jwt");
        PlatformScrubbingControl control = new PlatformScrubbingControl(processor, new LongAdder());
        long v0 = processor.getPolicyVersion();
        int threads = 4;
        int updatesPerThread = 40;
        ExecutorService pool = Executors.newFixedThreadPool(threads + 1);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicReference<String> violation = new AtomicReference<>();

        for (int t = 0; t < threads; t++) {
            boolean enabled = t % 2 == 0;
            pool.submit(() -> {
                await(start);
                try {
                    for (int i = 0; i < updatesPerThread; i++) {
                        control.updateScrubbingPolicy(enabled, new String[]{"password", "jwt"}, "concurrency");
                    }
                } catch (RuntimeException e) {
                    violation.compareAndSet(null, e.getMessage());
                } finally {
                    done.countDown();
                }
            });
        }

        pool.submit(() -> {
            await(start);
            try (SpanProcessorHarness h = SpanProcessorHarness.of(processor)) {
                Tracer tracer = h.tracer("t");
                while (done.getCount() > 0) {
                    Span span = tracer.spanBuilder("op").startSpan();
                    span.setAttribute("db.password", "secret");
                    span.end();
                }
            } catch (Exception e) {
                violation.compareAndSet(null, e.toString());
            }
        });

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(violation.get()).isNull();
        assertThat(processor.getPolicyVersion()).isEqualTo(v0 + (long) threads * updatesPerThread);
    }

    private static ScrubbingSpanProcessor processorWith(String... ruleNames) {
        java.util.ArrayList<SpanAttributeScrubbingRule> rules = new java.util.ArrayList<>();
        for (String name : ruleNames) {
            rules.add(BuiltInSpanAttributeScrubbingRules.resolve(name));
        }
        return new ScrubbingSpanProcessor(rules);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static final class InvalidRegexBuiltInRule implements SpanAttributeScrubbingRule {
        private final Pattern pattern;

        InvalidRegexBuiltInRule() {
            pattern = Pattern.compile("([unclosed");
        }

        @Nonnull
        @Override
        public String name() {
            return "invalid-regex";
        }

        @Override
        public int priority() {
            return 500;
        }

        @Nonnull
        @Override
        public ScrubbingDecision evaluate(@Nonnull String key, Object value) {
            return pattern.matcher(String.valueOf(value)).find()
                    ? ScrubbingDecision.drop("invalid-regex")
                    : ScrubbingDecision.keep();
        }
    }
}
