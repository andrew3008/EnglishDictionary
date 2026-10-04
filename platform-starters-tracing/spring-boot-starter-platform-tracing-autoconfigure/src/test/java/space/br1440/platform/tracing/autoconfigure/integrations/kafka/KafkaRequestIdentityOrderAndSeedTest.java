package space.br1440.platform.tracing.autoconfigure.integrations.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.SimpleTransactionStatus;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.annotation.Traced;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.context.CorrelationScope;
import space.br1440.platform.tracing.api.mdc.TracingMdcKeys;
import space.br1440.platform.tracing.api.propagation.PlatformHeaders;
import space.br1440.platform.tracing.autoconfigure.integrations.aop.TracedAspect;
import space.br1440.platform.tracing.autoconfigure.integrations.aop.TracingAopAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.integrations.errorhandling.RequestContextSupplierAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.span.SpanEnrichmentAutoConfiguration;
import space.br1440.platform.tracing.otel.facade.DefaultTraceOperations;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;
import space.br1440.platform.tracing.otel.runtime.otel.OtelTracingRuntimeFactory;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

class KafkaRequestIdentityOrderAndSeedTest {

    private static final AttributeKey<String> REQUEST_ID = AttributeKey.stringKey(PlatformAttributes.PLATFORM_REQUEST_ID);
    private static final String WRAPPED_TRACE_ID = "0123456789abcdef0123456789abcdef";
    private static final String WRAPPED_SPAN_ID = "0123456789abcdef";
    private static final String REQUEST_B = "request-b";
    private static final String OUTER_REQUEST_ID = "outer-id";

    private static InMemorySpanExporter exporter;
    private static OpenTelemetrySdk sdk;
    private static TracingRuntime runtime;

    @BeforeAll
    static void openSdk() {
        exporter = InMemorySpanExporter.create();
        sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build())
                .build();
        runtime = OtelTracingRuntimeFactory.create(sdk);
    }

    @AfterAll
    static void closeSdk() {
        if (sdk != null) {
            sdk.close();
        }
    }

    @AfterEach
    void resetTelemetry() {
        if (exporter != null) {
            exporter.reset();
        }
        MDC.clear();
    }

    @Test
    void transactionBeginsAfterProductionAspectsSeedTheCurrentSpan() {
        // given
        runner().run(context -> {
            assertProductionRuntime(context);
            SeedListener listener = context.getBean(SeedListener.class);
            ObservingTransactionManager transactions = context.getBean(ObservingTransactionManager.class);
            ConsumerRecord<String, String> record = record(REQUEST_B);

            // when
            listener.onMessage(record);

            // then
            assertThat(transactions.events()).contains(expectedTransactionBegin(REQUEST_B, REQUEST_B));
            assertThat(context.getBean(KafkaRequestIdentityAspect.class).getOrder())
                    .isGreaterThan(context.getBean(TracedAspect.class).getOrder())
                    .isLessThan(Ordered.LOWEST_PRECEDENCE);
            List<SpanData> finished = exporter.getFinishedSpanItems();
            assertThat(finished).hasSize(1);
            assertThat(finished.getFirst().getAttributes().get(REQUEST_ID)).isEqualTo(REQUEST_B);
            assertThat(listener.observations())
                    .containsExactly(observation(finished.getFirst().getSpanId(), REQUEST_B));
        });
    }

    @Test
    void recordingSpanKeepsItsIdentityAndExportsOnlyItself() {
        // given
        runner().run(context -> {
            assertProductionRuntime(context);
            SeedListener listener = context.getBean(SeedListener.class);
            ObservingTransactionManager transactions = context.getBean(ObservingTransactionManager.class);
            Tracer tracer = sdk.getTracer("kafka-seed-order");
            Span processing = tracer.spanBuilder("processing").startSpan();
            processing.setAttribute(REQUEST_ID, "request-a");
            String spanId = processing.getSpanContext().getSpanId();
            ConsumerRecord<String, String> record = record(REQUEST_B);

            // when
            try (Scope ignored = processing.makeCurrent()) {
                listener.onMessage(record);
            }
            processing.end();

            // then
            assertThat(listener.observations()).containsExactly(observation(spanId, REQUEST_B));
            assertThat(transactions.events()).contains(expectedTransactionBegin(REQUEST_B, REQUEST_B));
            List<SpanData> finished = exporter.getFinishedSpanItems();
            assertThat(finished).hasSize(1);
            assertThat(finished.getFirst().getSpanId()).isEqualTo(spanId);
            assertThat(finished.getFirst().getAttributes().get(REQUEST_ID)).isEqualTo(REQUEST_B);
        });
    }

    @Test
    void nonRecordingSpanKeepsRequestScopeAndRestoresOuterIdentity() {
        // given
        runner().run(context -> {
            assertProductionRuntime(context);
            SeedListener listener = context.getBean(SeedListener.class);
            ObservingTransactionManager transactions = context.getBean(ObservingTransactionManager.class);
            Span wrapped = Span.wrap(SpanContext.create(
                    WRAPPED_TRACE_ID,
                    WRAPPED_SPAN_ID,
                    TraceFlags.getSampled(),
                    TraceState.getDefault()));
            ConsumerRecord<String, String> record = record(REQUEST_B);
            assertThat(runtime.currentRequestId()).isEmpty();
            assertThat(MDC.get(TracingMdcKeys.REQUEST_ID)).isNull();

            // when
            try (CorrelationScope ignored = runtime.openRequestIdentityScope(OUTER_REQUEST_ID);
                 Scope ignored1 = wrapped.makeCurrent()) {
                listener.onMessage(record);
                assertThat(runtime.currentRequestId()).contains(OUTER_REQUEST_ID);
                assertThat(MDC.get(TracingMdcKeys.REQUEST_ID)).isEqualTo(OUTER_REQUEST_ID);
            }

            // then
            assertThat(listener.observations()).containsExactly(observation(WRAPPED_SPAN_ID, REQUEST_B));
            assertThat(transactions.events()).contains(expectedTransactionBegin(REQUEST_B, "-"));
            assertThat(exporter.getFinishedSpanItems()).isEmpty();
            assertThat(runtime.currentRequestId()).isEmpty();
            assertThat(MDC.get(TracingMdcKeys.REQUEST_ID)).isNull();
        });
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        TracingAopAutoConfiguration.class,
                        PlatformKafkaAutoConfiguration.class,
                        SpanEnrichmentAutoConfiguration.class,
                        RequestContextSupplierAutoConfiguration.class))
                .withBean(TraceOperations.class, () -> new DefaultTraceOperations(runtime))
                .withUserConfiguration(TransactionConfiguration.class);
    }

    private static void assertProductionRuntime(ConfigurableApplicationContext context) {
        assertThat(context.getBean(TracingRuntime.class)).isSameAs(runtime);
        TraceOperations operations = context.getBean(TraceOperations.class);
        assertThat(operations).isInstanceOf(DefaultTraceOperations.class);
        assertThat(((DefaultTraceOperations) operations).tracingRuntime()).isSameAs(runtime);
    }

    @SuppressWarnings("SameParameterValue")
    private static ConsumerRecord<String, String> record(String requestId) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("orders", 0, 0L, "key", "value");
        record.headers().add(PlatformHeaders.X_REQUEST_ID, requestId.getBytes(StandardCharsets.UTF_8));
        return record;
    }

    @SuppressWarnings("SameParameterValue")
    private static String observation(String spanId, String requestId) {
        return "orders " + spanId + " requestId=" + requestId + " mdc=" + requestId;
    }

    @SuppressWarnings("SameParameterValue")
    private static String expectedTransactionBegin(String requestId, String seed) {
        return "tx:begin requestId=" + requestId + " seed=" + seed;
    }

    private static String currentSeed() {
        Span span = Span.current();
        if (!(span instanceof ReadableSpan readable) || !span.getSpanContext().isValid()) {
            return "-";
        }
        String value = readable.getAttribute(REQUEST_ID);
        return value == null ? "null" : value;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TransactionConfiguration {

        @Bean
        TracingRuntime tracingRuntime() {
            return runtime;
        }

        @Bean
        ObservingTransactionManager observingTransactionManager(TracingRuntime tracingRuntime) {
            return new ObservingTransactionManager(tracingRuntime);
        }

        @Bean
        SeedListener seedListener(TracingRuntime tracingRuntime) {
            return new SeedListener(tracingRuntime);
        }
    }

    static class SeedListener {

        private final TracingRuntime runtime;
        private final List<String> observations = new ArrayList<>();

        SeedListener(TracingRuntime runtime) {
            this.runtime = runtime;
        }

        @Traced("kafka.seed")
        @KafkaListener(topics = "orders")
        @Transactional
        public void onMessage(ConsumerRecord<String, String> record) {
            observations.add(record.topic()
                    + " " + Span.current().getSpanContext().getSpanId()
                    + " requestId=" + runtime.currentRequestId().orElse("-")
                    + " mdc=" + MDC.get(TracingMdcKeys.REQUEST_ID));
        }

        List<String> observations() {
            return List.copyOf(observations);
        }
    }

    static final class ObservingTransactionManager implements PlatformTransactionManager {

        private final TracingRuntime runtime;
        private final List<String> events = new ArrayList<>();

        ObservingTransactionManager(TracingRuntime runtime) {
            this.runtime = runtime;
        }

        @Override
        public @NotNull TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
            events.add("tx:begin requestId=" + runtime.currentRequestId().orElse("-")
                    + " seed=" + currentSeed());
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(@NotNull TransactionStatus status) throws TransactionException {
            events.add("tx:commit");
        }

        @Override
        public void rollback(@NotNull TransactionStatus status) throws TransactionException {
            events.add("tx:rollback");
        }

        List<String> events() {
            return List.copyOf(events);
        }
    }
}
