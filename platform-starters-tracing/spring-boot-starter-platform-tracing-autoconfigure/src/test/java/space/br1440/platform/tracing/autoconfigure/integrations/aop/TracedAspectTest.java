package space.br1440.platform.tracing.autoconfigure.integrations.aop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.core.Ordered;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.annotation.Traced;
import space.br1440.platform.tracing.api.annotation.TracedAttribute;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanResult;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.otel.attributes.validation.SpanAttributeValidator;
import space.br1440.platform.tracing.otel.exception.ExceptionRecorder;
import space.br1440.platform.tracing.otel.exception.ExceptionRecordingPolicy;
import space.br1440.platform.tracing.otel.facade.DefaultTraceOperations;
import space.br1440.platform.tracing.otel.runtime.otel.OtelTracingRuntime;
import space.br1440.platform.tracing.otel.semconv.SemconvKeys;
import space.br1440.platform.tracing.test.TraceOperationsTestExtension;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;

@ExtendWith(TraceOperationsTestExtension.class)
class TracedAspectTest {

    private SampleService service;
    private TraceOperations tracing;
    private TracedAspect aspect;

    @BeforeEach
        // given
    void setUp(TraceOperations tracing) {
        this.tracing = tracing;
        this.aspect = new TracedAspect(tracing);
        AspectJProxyFactory factory = new AspectJProxyFactory(new SampleServiceImpl());
        factory.addAspect(aspect);
        service = factory.getProxy();
    }

    @Test
    void declaresHighestPrecedencePlusOneOrder() {
        // then
        assertThat(aspect).isInstanceOf(Ordered.class);
        assertThat(aspect.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 1);
        assertThat(aspect.getOrder()).isEqualTo(TracedAspect.ORDER);
    }

    @Test
    void createsSpanForAnnotatedMethod(InMemorySpanExporter exporter) {
        // when
        service.process(42L);

        // then
        List<SpanData> exported = exporter.getFinishedSpanItems();
        assertThat(exported).hasSize(1);
        SpanData span = exported.getFirst();

        assertThat(span.getName()).isEqualTo("sample.process");
        assertThat(span.getAttributes().asMap()).containsEntry(
                io.opentelemetry.api.common.AttributeKey.stringKey(PlatformAttributes.PLATFORM_TYPE),
                SpanCategory.INTERNAL.value()
        );
        assertThat(span.getAttributes().asMap()).containsEntry(
                io.opentelemetry.api.common.AttributeKey.stringKey(PlatformAttributes.PLATFORM_RESULT),
                SpanResult.SUCCESS.value()
        );
        assertThat(span.getAttributes().asMap()).containsEntry(
                io.opentelemetry.api.common.AttributeKey.stringKey("orderId"),
                "42"
        );
    }

    @Test
    void createsSpanForClassLevelTraced(InMemorySpanExporter exporter) {
        // given
        TracedAspect localAspect = new TracedAspect(tracing);
        AspectJProxyFactory factory = new AspectJProxyFactory(new ClassLevelTracedService());
        factory.addAspect(localAspect);
        ClassLevelTracedService proxy = factory.getProxy();

        // when
        proxy.run();

        // then
        List<SpanData> exported = exporter.getFinishedSpanItems();
        assertThat(exported).hasSize(1);
        assertThat(exported.getFirst().getName()).isEqualTo("run");
    }

    @Test
    void usesParameterNameWhenTracedAttributeKeyBlank(InMemorySpanExporter exporter) throws Exception {
        // given
        String expectedKey = SampleServiceImpl.class
                .getMethod("processWithBlankAttributeKey", String.class)
                .getParameters()[0]
                .getName();

        // when
        service.processWithBlankAttributeKey("abc");

        // then
        List<SpanData> exported = exporter.getFinishedSpanItems();
        assertThat(exported).hasSize(1);
        assertThat(exported.getFirst().getAttributes().asMap()).containsEntry(
                io.opentelemetry.api.common.AttributeKey.stringKey(expectedKey),
                "abc"
        );
    }

    @Test
    void marksSpanAsFailureOnException(InMemorySpanExporter exporter) {
        // when
        assertThatThrownBy(() -> service.failing("input"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("expected error");

        // then
        List<SpanData> exported = exporter.getFinishedSpanItems();
        assertThat(exported).hasSize(1);
        SpanData span = exported.getFirst();

        assertThat(span.getAttributes().asMap()).containsEntry(
                io.opentelemetry.api.common.AttributeKey.stringKey(PlatformAttributes.PLATFORM_RESULT),
                SpanResult.FAILURE.value()
        );
        assertThat(span.getEvents()).anyMatch(event -> event.getName().equals("exception"));
        assertThat(span.getStatus().getDescription()).isEqualTo("expected error");
        assertThat(span.getEvents().getFirst().getAttributes().get(SemconvKeys.EXCEPTION_MESSAGE))
                .isEqualTo("expected error");
        assertThat(span.getEvents().getFirst().getAttributes().get(SemconvKeys.EXCEPTION_STACKTRACE)).isNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void defaultRecorderSanitizesCreatedAndActiveSpansWithoutReplacingFailure(
            boolean activeSpan, OpenTelemetrySdk sdk, InMemorySpanExporter exporter) {
        // given
        IllegalStateException failure = new IllegalStateException("password=aop-secret-canary");
        Span parent = activeSpan ? sdk.getTracer("test").spanBuilder("active.parent").startSpan() : Span.getInvalid();
        Throwable escaped;

        // when
        try (Scope ignored = parent.makeCurrent()) {
            escaped = catchThrowable(() -> service.failWith(failure));
        } finally {
            parent.end();
        }

        // then
        assertThat(escaped).isSameAs(failure);
        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
        SpanData span = exporter.getFinishedSpanItems().getFirst();
        assertThat(span.getName()).isEqualTo(activeSpan ? "active.parent" : "sample.failure");
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getStatus().getDescription()).isEqualTo("<redacted>");
        assertThat(span.getEvents()).hasSize(1);
        assertThat(span.getEvents().getFirst().getAttributes().get(SemconvKeys.EXCEPTION_TYPE))
                .isEqualTo(IllegalStateException.class.getName());
        assertThat(span.getEvents().getFirst().getAttributes().get(SemconvKeys.EXCEPTION_MESSAGE))
                .isEqualTo("<redacted>");
        assertThat(span.getEvents().getFirst().getAttributes().get(SemconvKeys.EXCEPTION_STACKTRACE)).isNull();
    }

    @ParameterizedTest
    @EnumSource(FailingGetter.class)
    void createdSpanPreservesOriginalFailureWhenDiagnosticGetterThrows(
            FailingGetter getter, OpenTelemetrySdk sdk, InMemorySpanExporter exporter) {
        // given
        HostileException failure = new HostileException(getter);
        ExceptionRecorder recorder = new ExceptionRecorder(new ExceptionRecordingPolicy(true, true));
        TraceOperations operations = new DefaultTraceOperations(
                new OtelTracingRuntime(sdk, new SpanAttributeValidator(), recorder));
        HostileFailureService proxy = hostileService(operations, recorder, failure);
        Span previous = Span.current();

        // when
        Throwable escaped = catchThrowable(proxy::fail);

        // then
        assertThat(escaped).isSameAs(failure);
        assertThat(failure.failedGetterCalls).isPositive();
        assertThat(Span.current()).isSameAs(previous);
        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
        SpanData span = exporter.getFinishedSpanItems().getFirst();
        assertThat(span.getName()).isEqualTo("hostile.failure");
        assertRecordedFailure(span, failure);
    }

    @ParameterizedTest
    @EnumSource(FailingGetter.class)
    void activeSpanPreservesOriginalFailureWhenDiagnosticGetterThrows(
            FailingGetter getter, OpenTelemetrySdk sdk, InMemorySpanExporter exporter) {
        // given
        HostileException failure = new HostileException(getter);
        ExceptionRecorder recorder = new ExceptionRecorder(new ExceptionRecordingPolicy(true, true));
        HostileFailureService proxy = hostileService(tracing, recorder, failure);
        Span previous = Span.current();
        Span parent = sdk.getTracer("test").spanBuilder("active.parent").startSpan();
        Throwable escaped;

        // when
        try (Scope ignored = parent.makeCurrent()) {
            escaped = catchThrowable(proxy::fail);

            // then
            assertThat(Span.current()).isSameAs(parent);
            assertThat(parent.isRecording()).isTrue();
            assertThat(exporter.getFinishedSpanItems()).isEmpty();
        } finally {
            parent.end();
        }

        assertThat(escaped).isSameAs(failure);
        assertThat(failure.failedGetterCalls).isPositive();
        assertThat(Span.current()).isSameAs(previous);
        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
        SpanData span = exporter.getFinishedSpanItems().getFirst();
        assertThat(span.getName()).isEqualTo("active.parent");
        assertRecordedFailure(span, failure);
    }

    private static HostileFailureService hostileService(
            TraceOperations operations, ExceptionRecorder recorder, HostileException failure) {
        AspectJProxyFactory factory = new AspectJProxyFactory(new HostileFailureService(failure));
        factory.addAspect(new TracedAspect(operations, recorder));
        return factory.getProxy();
    }

    private static void assertRecordedFailure(SpanData span, HostileException failure) {
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getAttributes().get(SemconvKeys.PLATFORM_RESULT)).isEqualTo(SpanResult.FAILURE.value());
        assertThat(span.getAttributes().get(SemconvKeys.ERROR_TYPE)).isEqualTo(failure.getClass().getName());
        assertThat(span.getEvents()).hasSize(1);
        assertThat(span.getEvents().getFirst().getName()).isEqualTo("exception");
        assertThat(span.getEvents().getFirst().getAttributes().get(SemconvKeys.EXCEPTION_TYPE))
                .isEqualTo(failure.getClass().getName());
    }

    /**
     * При наличии активного span'а дочерний не создаётся, активный обогащается
     * атрибутами {@code @TracedAttribute} и {@code platform.traced.method}.
     */
    @Test
    void doesNotCreateChildSpanWhenParentActive(InMemorySpanExporter exporter) {
        // given
        SpanHandle parentHandle = tracing.spans().operation("parent.span").start();
        try (parentHandle) {
            // when
            service.process(7L);
        }

        // then
        List<SpanData> exported = exporter.getFinishedSpanItems();
        assertThat(exported).hasSize(1);
        SpanData parent = exported.getFirst();

        assertThat(parent.getName()).isEqualTo("parent.span");
        assertThat(parent.getAttributes().asMap()).containsKey(
                io.opentelemetry.api.common.AttributeKey.stringKey(PlatformAttributes.PLATFORM_TRACED_METHOD)
        );
        assertThat(parent.getAttributes().asMap()).containsEntry(
                io.opentelemetry.api.common.AttributeKey.stringKey(PlatformAttributes.PLATFORM_TRACED_METHOD),
                "sample.process"
        );
        assertThat(parent.getAttributes().asMap()).containsEntry(
                io.opentelemetry.api.common.AttributeKey.stringKey("orderId"),
                "7"
        );
    }

    @Test
    void selfInvocationDoesNotApplyAdvice(InMemorySpanExporter exporter) {
        // when
        service.selfInvoke(11L);

        // then
        assertThat(exporter.getFinishedSpanItems()).isEmpty();
    }

    /**
     * Characterization: синхронный AOP-lifecycle закрывает span при возврате future,
     * а не при его completion — основание для ArchUnit-запрета.
     */
    @Test
    void closesSpanBeforeCompletableFutureCompletes(InMemorySpanExporter exporter) throws Exception {
        // given
        TracedAspect localAspect = new TracedAspect(tracing);
        AspectJProxyFactory factory = new AspectJProxyFactory(new AsyncReturningService());
        factory.addAspect(localAspect);
        AsyncReturningService proxy = factory.getProxy();
        AtomicBoolean completedAfterSpanClose = new AtomicBoolean(false);

        // when
        CompletableFuture<String> future = proxy.asyncWork();
        List<SpanData> afterReturn = List.copyOf(exporter.getFinishedSpanItems());
        future.thenRun(() -> completedAfterSpanClose.set(true));
        future.complete("done");
        future.get(1, TimeUnit.SECONDS);

        // then
        assertThat(afterReturn).hasSize(1);
        assertThat(completedAfterSpanClose).isTrue();
        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
    }

    interface SampleService {
        void process(long orderId);

        void processWithBlankAttributeKey(String token);

        void failing(String input);

        void failWith(RuntimeException failure);

        void selfInvoke(long orderId);
    }

    static class SampleServiceImpl implements SampleService {
        @Override
        @Traced("sample.process")
        public void process(@TracedAttribute("orderId") long orderId) {
        }

        @Override
        @Traced("sample.blankKey")
        public void processWithBlankAttributeKey(@TracedAttribute String token) {
        }

        @Override
        @Traced("sample.failing")
        public void failing(@TracedAttribute("input") String input) {
            throw new IllegalStateException("expected error");
        }

        @Override
        @Traced("sample.failure")
        public void failWith(RuntimeException failure) {
            throw failure;
        }

        @Override
        public void selfInvoke(long orderId) {
            process(orderId);
        }
    }

    @Traced
    static class ClassLevelTracedService {
        public void run() {
        }
    }

    static class AsyncReturningService {
        @Traced("async.work")
        public CompletableFuture<String> asyncWork() {
            return new CompletableFuture<>();
        }
    }

    static class HostileFailureService {
        private final HostileException failure;

        HostileFailureService(HostileException failure) {
            this.failure = failure;
        }

        @Traced("hostile.failure")
        public void fail() {
            throw failure;
        }
    }

    enum FailingGetter {
        MESSAGE, CAUSE, STACK
    }

    private static final class HostileException extends RuntimeException {
        private final FailingGetter failingGetter;
        private int failedGetterCalls;

        private HostileException(FailingGetter failingGetter) {
            super("diagnostic message");
            this.failingGetter = failingGetter;
            setStackTrace(new StackTraceElement[]{
                    new StackTraceElement("example.Service", "call", "Service.java", 17)
            });
        }

        @Override
        public String getMessage() {
            failIfSelected(FailingGetter.MESSAGE);
            return super.getMessage();
        }

        @Override
        public synchronized Throwable getCause() {
            failIfSelected(FailingGetter.CAUSE);
            return super.getCause();
        }

        @Override
        public StackTraceElement[] getStackTrace() {
            failIfSelected(FailingGetter.STACK);
            return super.getStackTrace();
        }

        private void failIfSelected(FailingGetter getter) {
            if (failingGetter == getter) {
                failedGetterCalls++;
                throw new UnsupportedOperationException("Diagnostic getter failed");
            }
        }
    }
}
