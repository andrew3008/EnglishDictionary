package space.br1440.platform.tracing.autoconfigure.aspect;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import space.br1440.platform.tracing.api.PlatformTracing;
import space.br1440.platform.tracing.api.annotation.Traced;
import space.br1440.platform.tracing.api.annotation.TracedAttribute;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanResult;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.test.PlatformTracingTestExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(PlatformTracingTestExtension.class)
class TracedAspectTest {

    private SampleService service;
    private PlatformTracing tracing;

    @BeforeEach
        // given
    void setUp(PlatformTracing tracing) {
        this.tracing = tracing;
        TracedAspect aspect = new TracedAspect(tracing);
        AspectJProxyFactory factory = new AspectJProxyFactory(new SampleServiceImpl());
        factory.addAspect(aspect);
        service = factory.getProxy();
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
     * ENRICH_CURRENT при наличии активного span'а: дочерний не создаётся, активный обогащается
     * атрибутами {@code @TracedAttribute} и {@code platform.traced.method}.
     */
    @Test
    void enrichCurrentModeDoesNotCreateChildSpanWhenParentActive(InMemorySpanExporter exporter) {
        // given: внешний parent-span (имитируем серверный span от Agent / Filter).
        try (SpanHandle parent = tracing.manual().operation("parent.span").start()) {
            // when: вызов @Traced-метода в режиме ENRICH_CURRENT (по умолчанию).
            service.process(7L);
        }

        // then: всего один span — parent. Дочерний не создан, parent обогащён.
        List<SpanData> exported = exporter.getFinishedSpanItems();
        assertThat(exported).hasSize(1);
        SpanData parent = exported.getFirst();

        assertThat(parent.getName()).isEqualTo("parent.span");
        // Имя @Traced-метода записано как атрибут на parent'е (см. enrichCurrentSpan).
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

    /**
     * CHILD_SPAN: явное создание дочернего span'а независимо от наличия parent'а.
     */
    @Test
    void childSpanModeCreatesNewSpan(InMemorySpanExporter exporter) {
        // given: переопределяем aspect в режиме CHILD_SPAN.
        TracedAspect aspect = new TracedAspect(tracing, TracingProperties.Aop.Mode.CHILD_SPAN);
        AspectJProxyFactory factory = new AspectJProxyFactory(new SampleServiceImpl());
        factory.addAspect(aspect);
        SampleService child = factory.getProxy();

        // when: вызов @Traced-метода под активным parent-span'ом.
        try (SpanHandle parent = tracing.manual().operation("parent.span").start()) {
            child.process(99L);
        }

        // then: оба span'а присутствуют.
        List<SpanData> exported = exporter.getFinishedSpanItems();
        assertThat(exported).hasSize(2);
        assertThat(exported).extracting(SpanData::getName)
                .containsExactlyInAnyOrder("parent.span", "sample.process");
    }

    /**
     * Step 8.3: WARN однократно при @Traced(category=HTTP_SERVER) — это почти всегда ошибка
     * инструментации (HTTP-server span создаётся OTel Agent / Micrometer Observation).
     */
    @Test
    void warnsOnceWhenTracedAppliedToHttpServerCategory() {
        Logger aspectLogger = (Logger) LoggerFactory.getLogger(TracedAspect.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        aspectLogger.addAppender(appender);
        try {
            // Создаём отдельный proxy с целевым «плохим» сервисом.
            TracedAspect aspect = new TracedAspect(tracing);
            AspectJProxyFactory factory = new AspectJProxyFactory(new HttpServerMisuseImpl());
            factory.addAspect(aspect);
            HttpServerMisuseService misuse = factory.getProxy();

            // when: дважды зовём метод с category=HTTP_SERVER.
            misuse.handle();
            misuse.handle();

            // then: WARN выведен ровно один раз.
            long warnCount = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .filter(e -> e.getFormattedMessage().contains("category=HTTP_SERVER"))
                    .count();
            assertThat(warnCount).isEqualTo(1);
        } finally {
            aspectLogger.detachAppender(appender);
            appender.stop();
        }
    }

    interface SampleService {
        void process(long orderId);

        void failing(String input);
    }

    static class SampleServiceImpl implements SampleService {
        @Override
        @Traced(value = "sample.process", category = SpanCategory.INTERNAL, attributes = {"orderId"})
        public void process(@TracedAttribute("orderId") long orderId) {
        }

        @Override
        @Traced("sample.failing")
        public void failing(@TracedAttribute("input") String input) {
            throw new IllegalStateException("expected error");
        }
    }

    interface HttpServerMisuseService {
        void handle();
    }

    static class HttpServerMisuseImpl implements HttpServerMisuseService {
        @Override
        @Traced(value = "bad.http.server", category = SpanCategory.HTTP_SERVER)
        public void handle() {
        }
    }
}
