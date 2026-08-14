package space.br1440.platform.tracing.autoconfigure.integrations.aop;

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.core.Ordered;
import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.annotation.Traced;
import space.br1440.platform.tracing.api.annotation.TracedAttribute;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanResult;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.test.TraceOperationsTestExtension;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    }

    /**
     * При наличии активного span'а дочерний не создаётся, активный обогащается
     * атрибутами {@code @TracedAttribute} и {@code platform.traced.method}.
     */
    @Test
    void doesNotCreateChildSpanWhenParentActive(InMemorySpanExporter exporter) {
        // given
        try (SpanHandle parent = tracing.spans().operation("parent.span").start()) {
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
}
