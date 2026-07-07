# OpenTelemetry SDK — Полезный код из OpenSource и коммерческих проектов

## Обзор

Этот документ — результат глубокого исследования того, как **ведущие OpenSource-проекты** (Elastic APM, Grafana Tempo, Quarkus, Honeycomb, New Relic) и **коммерческие APM-вендоры** (Datadog, New Relic, Elastic) реализуют и расширяют OpenTelemetry Java SDK. Для каждой темы приводится боевой код, который можно переиспользовать в платформенных Spring Boot стартерах.

***

## 1. Программная инициализация SDK (вместо agent)

Это наиболее важный паттерн для **собственного Spring Boot стартера**. Вместо JVM-агента — программная конфигурация с экспортом в OTLP.

```java
// Паттерн из New Relic examples + официальной документации opentelemetry-java
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.semconv.resource.attributes.ResourceAttributes;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.context.propagation.TextMapPropagator;

import java.time.Duration;

public class PlatformOpenTelemetryConfig {

    public static OpenTelemetrySdk buildSdk(String serviceName, String serviceVersion,
                                            String collectorEndpoint) {
        // Resource — описание сервиса (перенято из Elastic EDOT и contrib resource providers)
        Resource resource = Resource.getDefault().merge(
            Resource.builder()
                .put(ResourceAttributes.SERVICE_NAME, serviceName)
                .put(ResourceAttributes.SERVICE_VERSION, serviceVersion)
                .put(ResourceAttributes.DEPLOYMENT_ENVIRONMENT, 
                     System.getenv().getOrDefault("DEPLOYMENT_ENV", "production"))
                .build()
        );

        // Exporter с таймаутом (из Datadog opentelemetry-examples)
        OtlpGrpcSpanExporter spanExporter = OtlpGrpcSpanExporter.builder()
            .setEndpoint(collectorEndpoint)
            .setTimeout(Duration.ofSeconds(10))
            .build();

        // BatchSpanProcessor — ключевые параметры для highload (из OneUptime tuning guide)
        BatchSpanProcessor spanProcessor = BatchSpanProcessor.builder(spanExporter)
            .setMaxQueueSize(8192)           // default 2048 — мало для highload
            .setMaxExportBatchSize(512)
            .setScheduleDelay(Duration.ofMillis(2000))  // default 5000ms
            .setExporterTimeout(Duration.ofSeconds(10))
            .build();

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .setResource(resource)
            .setSampler(Sampler.parentBased(Sampler.traceIdRatioBased(1.0)))
            .addSpanProcessor(spanProcessor)
            .build();

        // Metrics — PeriodicMetricReader
        OtlpGrpcMetricExporter metricExporter = OtlpGrpcMetricExporter.builder()
            .setEndpoint(collectorEndpoint)
            .build();
        SdkMeterProvider meterProvider = SdkMeterProvider.builder()
            .setResource(resource)
            .registerMetricReader(PeriodicMetricReader.builder(metricExporter)
                .setInterval(Duration.ofMinutes(1))
                .build())
            .build();

        // Logs — BatchLogRecordProcessor (bridge для Logback/Log4j2)
        OtlpGrpcLogRecordExporter logExporter = OtlpGrpcLogRecordExporter.builder()
            .setEndpoint(collectorEndpoint)
            .build();
        SdkLoggerProvider loggerProvider = SdkLoggerProvider.builder()
            .setResource(resource)
            .addLogRecordProcessor(BatchLogRecordProcessor.builder(logExporter).build())
            .build();

        // Composite propagators: W3C + Baggage (из официального Spring Boot starter guide)
        ContextPropagators propagators = ContextPropagators.create(
            TextMapPropagator.composite(
                W3CTraceContextPropagator.getInstance(),
                W3CBaggagePropagator.getInstance()
            )
        );

        return OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setMeterProvider(meterProvider)
            .setLoggerProvider(loggerProvider)
            .setPropagators(propagators)
            .buildAndRegisterGlobal(); // регистрирует как GlobalOpenTelemetry
    }
}
```

> **Ключевой момент из Alibaba Cloud ARMS**: всегда используй `GlobalOpenTelemetry.get()` — НЕ создавай второй экземпляр SDK вручную, иначе кастомные span-ы станут невидимы для agent-а.[^1]

***

## 2. AutoConfigurationCustomizerProvider — точка расширения Spring Boot Starter

Это **главный паттерн Datadog и Elastic** для добавления функциональности без переписывания агента. Используется в `opentelemetry-spring-boot-starter`.[^2][^3]

```java
// Паттерн из examples/distro (DemoAutoConfigurationCustomizerProvider)
// и из Spring Boot starter документации
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.autoconfigure.AutoConfigurationCustomizer;
import com.google.auto.service.AutoService;

@AutoService(AutoConfigurationCustomizerProvider.class)
public class PlatformAutoConfigurationCustomizer implements AutoConfigurationCustomizerProvider {

    @Override
    public void customize(AutoConfigurationCustomizer autoConfiguration) {
        // 1. Добавить кастомные SpanProcessor-ы (из Honeycomb distro паттерна)
        autoConfiguration.addSpanProcessorCustomizer((processor, config) -> {
            // Цепочка: сначала BaggageSpanProcessor, затем существующий processor
            return SpanProcessor.composite(
                BaggageSpanProcessor.allowAllBaggageKeys(), // из opentelemetry-java-contrib
                processor
            );
        });

        // 2. Добавить кастомные resource attributes из environment
        autoConfiguration.addResourceCustomizer((resource, config) -> 
            resource.merge(Resource.builder()
                .put("platform.version", PlatformVersion.get())
                .put("k8s.pod.name", System.getenv().getOrDefault("POD_NAME", "unknown"))
                .put("k8s.namespace.name", System.getenv().getOrDefault("NAMESPACE_NAME", "unknown"))
                .build())
        );

        // 3. Задать дополнительные properties через Supplier (из StackOverflow примера)
        autoConfiguration.addPropertiesSupplier(() -> {
            Map<String, String> props = new HashMap<>();
            props.put("otel.propagators", "tracecontext,baggage");
            props.put("otel.java.experimental.span-attributes.copy-from-baggage.include",
                      "correlation-id,user-id,tenant-id");
            return props;
        });

        // 4. Настроить Sampler программно
        autoConfiguration.addTracerProviderCustomizer((builder, config) -> 
            builder.setSampler(
                Sampler.parentBased(Sampler.traceIdRatioBased(
                    Double.parseDouble(
                        config.getString("otel.traces.sampler.arg", "1.0")
                    )
                ))
            )
        );
    }

    @Override
    public int order() {
        return 0; // порядок выполнения среди нескольких провайдеров
    }
}
```

В Spring Boot-контексте тот же результат через `@Bean`:[^4][^5]

```java
@Configuration
public class OtelPlatformConfiguration {

    @Bean
    public AutoConfigurationCustomizerProvider otelCustomizer() {
        return autoConfiguration -> autoConfiguration
            .addSpanProcessorCustomizer((processor, config) ->
                SpanProcessor.composite(new PlatformSpanProcessor(), processor)
            );
    }
}
```

***

## 3. Кастомный SpanProcessor — паттерны из реальных дистрибутивов

### 3.1 Базовая реализация (из opentelemetry-java-instrumentation/examples/extension)

```java
// Взято и адаптировано из DemoSpanProcessor (официальный extension example)
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;

public class PlatformSpanProcessor implements SpanProcessor {

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        // Добавляем атрибуты при старте спана
        span.setAttribute("platform.service.mesh", "istio");
        span.setAttribute("platform.region", System.getenv("AWS_REGION"));
        
        // Извлекаем и проставляем correlation-id из Baggage
        String correlationId = Baggage.fromContext(parentContext)
            .getEntryValue("correlation-id");
        if (correlationId != null) {
            span.setAttribute("correlation.id", correlationId);
        }
    }

    @Override
    public boolean isStartRequired() { return true; }

    @Override
    public void onEnd(ReadableSpan span) {
        // Можно фильтровать медленные спаны для алертинга
        if (span.toSpanData().getEndEpochNanos() - 
            span.toSpanData().getStartEpochNanos() > 1_000_000_000L) { // > 1 сек
            // Например, отправить метрику о медленном спане
        }
    }

    @Override
    public boolean isEndRequired() { return true; }

    @Override
    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode forceFlush() {
        return CompletableResultCode.ofSuccess();
    }
}
```

### 3.2 BaggageSpanProcessor из opentelemetry-java-contrib (Honeycomb → contrib)

Honeycomb первой реализовала этот паттерн, он был вынесен в официальный contrib репозиторий.[^6][^7]

```gradle
// Gradle dependency
implementation 'io.opentelemetry.contrib:opentelemetry-baggage-processor:1.56.0-alpha'
```

```java
// Копирует ВСЕ baggage-ключи как span/log атрибуты
SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
    .addSpanProcessor(BaggageSpanProcessor.allowAllBaggageKeys())
    .build();

SdkLoggerProvider loggerProvider = SdkLoggerProvider.builder()
    .addLogRecordProcessor(BaggageLogRecordProcessor.allowAllBaggageKeys())
    .build();

// Или с фильтрацией по предикату (с regex, как в примере contrib)
Pattern sensitiveKeys = Pattern.compile("^(?!password|secret|token).*");
new BaggageSpanProcessor(key -> sensitiveKeys.matcher(key).matches());

// Или через autoconfigure property (без кода):
// otel.java.experimental.span-attributes.copy-from-baggage.include=correlation-id,user-id,*
```

***

## 4. Кастомный Sampler — паттерны из OpenSource дистрибутивов

### 4.1 ParentBased + TraceIdRatio (из Uptrace, New Relic, Elastic)

Это **стандарт production**: `parentbased_traceidratio` сохраняет целостность трейсов.[^8][^9]

```java
// ПРАВИЛЬНО для production: parentBased гарантирует complete traces
Sampler productionSampler = Sampler.parentBased(
    Sampler.traceIdRatioBased(0.1)  // 10% новых трейсов, но complete
);

// НЕПРАВИЛЬНО для production: неполные трейсы
Sampler badSampler = Sampler.traceIdRatioBased(0.1); // режет спаны mid-trace
```

### 4.2 Кастомный Sampler — исключение healthcheck-ов (паттерн Elastic EDOT и Erlang SDK)

```java
// Паттерн из attribute_sampler в Erlang SDK и Elastic EDOT
// Аналог применяется в DemoSampler из examples/distro
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import java.util.List;

public class HealthcheckFilterSampler implements Sampler {

    private final Sampler delegate;
    private final List<String> excludedPaths;

    public HealthcheckFilterSampler(Sampler delegate, List<String> excludedPaths) {
        this.delegate = delegate;
        this.excludedPaths = excludedPaths;
    }

    @Override
    public SamplingResult shouldSample(Context parentContext, String traceId,
                                        String name, SpanKind spanKind,
                                        Attributes attributes,
                                        List<LinkData> parentLinks) {
        // Исключаем healthcheck-эндпоинты из трейсов
        String httpTarget = attributes.get(SemanticAttributes.HTTP_TARGET);
        if (httpTarget != null && excludedPaths.stream().anyMatch(httpTarget::startsWith)) {
            return SamplingResult.drop();
        }
        return delegate.shouldSample(parentContext, traceId, name, spanKind,
                                     attributes, parentLinks);
    }

    @Override
    public String getDescription() {
        return "HealthcheckFilterSampler{delegate=" + delegate.getDescription() + "}";
    }
}

// Использование в конфигурации
Sampler sampler = new HealthcheckFilterSampler(
    Sampler.parentBased(Sampler.traceIdRatioBased(1.0)),
    List.of("/actuator/health", "/actuator/prometheus", "/ping")
);
```

***

## 5. Кастомный TextMapPropagator — X-Request-Id и проприетарные заголовки

Этот паттерн активно используется в **Istio-интеграциях** и **корпоративных API-шлюзах**.[^10][^11]

```java
// Паттерн из XReqIdPropagator (opentelemetry-java issue #5883)
// и из opentelemetry-operator issue #1758 (Istio x-request-id propagation)
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.BaggageBuilder;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import java.util.List;

public class XRequestIdPropagator implements TextMapPropagator {

    public static final String X_REQUEST_ID = "X-Request-Id";
    public static final String X_CORRELATION_ID = "X-Correlation-Id";
    public static final String BAGGAGE_KEY = "correlation-id";

    @Override
    public List<String> fields() {
        return List.of(X_REQUEST_ID, X_CORRELATION_ID);
    }

    @Override
    public <C> void inject(Context context, C carrier, TextMapSetter<C> setter) {
        // Читаем из Baggage, пишем в заголовок
        String correlationId = Baggage.fromContext(context).getEntryValue(BAGGAGE_KEY);
        if (correlationId != null && !correlationId.isEmpty()) {
            setter.set(carrier, X_REQUEST_ID, correlationId);
            setter.set(carrier, X_CORRELATION_ID, correlationId);
        }
    }

    @Override
    public <C> Context extract(Context context, C carrier, TextMapGetter<C> getter) {
        // Читаем из заголовка, кладём в Baggage
        String requestId = getter.get(carrier, X_REQUEST_ID);
        if (requestId == null) {
            requestId = getter.get(carrier, X_CORRELATION_ID);
        }
        if (requestId != null && !requestId.isEmpty()) {
            // ВАЖНО: строим новый Baggage поверх существующего,
            // не заменяем полностью (паттерн из issue #5883)
            Baggage updatedBaggage = Baggage.fromContext(context).toBuilder()
                .put(BAGGAGE_KEY, requestId)
                .build();
            return context.with(updatedBaggage);
        }
        return context;
    }
}

// Регистрация в составном propagator:
ContextPropagators.create(
    TextMapPropagator.composite(
        W3CTraceContextPropagator.getInstance(),
        W3CBaggagePropagator.getInstance(),
        new XRequestIdPropagator()   // <-- наш кастомный
    )
)
```

***

## 6. Ручное управление Baggage (паттерн OpenTracing → OTel миграции)

Официальный паттерн из GitHub Discussions opentelemetry-java и OpenTelemetry Concepts Baggage:[^12][^13]

```java
// Паттерн из opentelemetry-java Discussion #5651
// (миграция с OpenTracing baggageItem)

public class CorrelationContext {
    public static final String CORRELATION_ID_KEY = "correlation-id";
    public static final String USER_ID_KEY = "user-id";
    public static final String TENANT_ID_KEY = "tenant-id";

    // Устанавливает значение в Baggage текущего контекста
    public static Scope setCorrelationId(String value) {
        Baggage baggage = Baggage.current().toBuilder()
            .put(CORRELATION_ID_KEY, value)
            .build();
        // makeCurrent() возвращает Scope — ОБЯЗАТЕЛЬНО закрывать через try-with-resources
        return baggage.makeCurrent();
    }

    // Получить значение из текущего контекста
    public static String getCorrelationId() {
        return Baggage.current().getEntryValue(CORRELATION_ID_KEY);
    }

    // Распространение через несколько ключей за раз
    public static Scope setRequestContext(String correlationId, String userId, String tenantId) {
        Baggage baggage = Baggage.current().toBuilder()
            .put(CORRELATION_ID_KEY, correlationId)
            .put(USER_ID_KEY, userId)
            .put(TENANT_ID_KEY, tenantId)
            .build();
        return baggage.makeCurrent();
    }
}

// Использование в Spring Boot controller
@RestController
public class OrderController {

    @GetMapping("/orders/{id}")
    public Order getOrder(@PathVariable String id,
                          @RequestHeader("X-Correlation-Id") String correlationId) {
        // Установить в контексте — всё downstream автоматически получит
        try (Scope scope = CorrelationContext.setCorrelationId(correlationId)) {
            // BaggageSpanProcessor автоматически добавит correlation-id в span атрибуты
            return orderService.findById(id);
        }
    }
}
```

***

## 7. gRPC — ручная инструментация через GrpcTelemetry

Паттерн из `opentelemetry-instrumentation-grpc` и GitHub jtayl222/grpc-java-instrumentation:[^14][^15]

```java
// gRPC client с автоматической OTel трассировкой
import io.opentelemetry.instrumentation.grpc.v1_6.GrpcTelemetry;

GrpcTelemetry grpcTelemetry = GrpcTelemetry.create(openTelemetry);

// Client interceptor — инжектирует trace context в gRPC metadata
ManagedChannel channel = ManagedChannelBuilder
    .forAddress("inventory-service", 9090)
    .usePlaintext()
    .intercept(grpcTelemetry.newClientInterceptor())  // <-- OTel interceptor
    .build();

// Server interceptor — извлекает trace context из входящих metadata
Server server = ServerBuilder.forPort(9090)
    .addService(new InventoryServiceImpl())
    .intercept(grpcTelemetry.newServerInterceptor())  // <-- OTel interceptor
    .build();
```

Для **ручного извлечения контекста** из gRPC Metadata (паттерн из StackOverflow grpc context propagation):[^16]

```java
// ServerInterceptor для ручного извлечения трейс-контекста
public class TracingServerInterceptor implements ServerInterceptor {

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {

        TextMapPropagator propagator = GlobalOpenTelemetry.getPropagators()
                                                          .getTextMapPropagator();

        // TextMapGetter адаптирует gRPC Metadata к интерфейсу OTel
        Context extractedContext = propagator.extract(
            Context.current(),
            headers,
            new TextMapGetter<Metadata>() {
                @Override
                public Iterable<String> keys(Metadata carrier) {
                    return carrier.keys();
                }

                @Override
                public String get(Metadata carrier, String key) {
                    return carrier.get(Metadata.Key.of(key, 
                                       Metadata.ASCII_STRING_MARSHALLER));
                }
            }
        );

        // Создаём span с правильным parent context
        Span span = GlobalOpenTelemetry.getTracer("platform-grpc")
            .spanBuilder(call.getMethodDescriptor().getFullMethodName())
            .setSpanKind(SpanKind.SERVER)
            .setParent(extractedContext)
            .startSpan();

        try (Scope scope = span.makeCurrent()) {
            return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(
                next.startCall(call, headers)) {
                @Override
                public void onComplete() {
                    span.end();
                    super.onComplete();
                }
                @Override
                public void onCancel() {
                    span.setStatus(StatusCode.ERROR, "cancelled");
                    span.end();
                    super.onCancel();
                }
            };
        }
    }
}
```

***

## 8. Kafka — ручная контекстная пропаганда через заголовки

Проблема авто-инструментации Kafka: контекст не всегда корректно распространяется через SQS/Kafka без явных adapter-ов.[^17][^18]

```java
// Паттерн из обсуждений opentelemetry-java-instrumentation (issues #1113, #3684)
// Используется в Grafana демо-проекте с Kafka

public class KafkaTracingUtils {

    // Inject: запись trace context в Kafka headers при ОТПРАВКЕ
    public static void injectTraceContext(ProducerRecord<?, ?> record) {
        TextMapPropagator propagator = GlobalOpenTelemetry.getPropagators()
                                                          .getTextMapPropagator();
        propagator.inject(
            Context.current(),
            record.headers(),
            (headers, key, value) -> headers.add(key, value.getBytes(StandardCharsets.UTF_8))
        );
    }

    // Extract: извлечение trace context из Kafka headers при ПОЛУЧЕНИИ
    public static Context extractTraceContext(ConsumerRecord<?, ?> record) {
        TextMapPropagator propagator = GlobalOpenTelemetry.getPropagators()
                                                          .getTextMapPropagator();
        return propagator.extract(
            Context.current(),
            record.headers(),
            new TextMapGetter<Headers>() {
                @Override
                public Iterable<String> keys(Headers carrier) {
                    List<String> keys = new ArrayList<>();
                    carrier.forEach(h -> keys.add(h.key()));
                    return keys;
                }

                @Override
                public String get(Headers carrier, String key) {
                    Header header = carrier.lastHeader(key);
                    if (header == null) return null;
                    return new String(header.value(), StandardCharsets.UTF_8);
                }
            }
        );
    }
}

// Использование в Kafka consumer
@KafkaListener(topics = "orders")
public void consume(ConsumerRecord<String, String> record) {
    // Извлекаем контекст из Kafka заголовков
    Context extractedContext = KafkaTracingUtils.extractTraceContext(record);

    Tracer tracer = GlobalOpenTelemetry.getTracer("platform-kafka");
    Span span = tracer.spanBuilder("orders.process")
        .setSpanKind(SpanKind.CONSUMER)
        .setParent(extractedContext)  // Связываем с producer span
        .setAttribute("messaging.system", "kafka")
        .setAttribute("messaging.destination", record.topic())
        .setAttribute("messaging.kafka.partition", record.partition())
        .startSpan();

    try (Scope scope = span.makeCurrent()) {
        processOrder(record.value());
    } catch (Exception e) {
        span.recordException(e);
        span.setStatus(StatusCode.ERROR, e.getMessage());
        throw e;
    } finally {
        span.end();
    }
}
```

***

## 9. Кастомный SpanExporter — фильтрация и обогащение перед экспортом

Паттерн из `SpanExporter.composite()` JavaDoc и RETIT extension (добавление CPU/memory к спанам):[^19][^20]

```java
// FilteringSpanExporter — отфильтровывает и обогащает спаны
// Паттерн из examples/distro DemoSpanExporter
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

public class FilteringEnrichingSpanExporter implements SpanExporter {

    private final SpanExporter delegate;
    private final List<String> ignoredOperations;

    public FilteringEnrichingSpanExporter(SpanExporter delegate,
                                          List<String> ignoredOperations) {
        this.delegate = delegate;
        this.ignoredOperations = ignoredOperations;
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        List<SpanData> filteredSpans = spans.stream()
            // Исключаем healthcheck и premetheus spans
            .filter(span -> ignoredOperations.stream()
                .noneMatch(op -> span.getName().contains(op)))
            // Добавляем только ERROR спаны с дополнительными деталями
            // (логика обогащения)
            .collect(Collectors.toList());

        if (filteredSpans.isEmpty()) {
            return CompletableResultCode.ofSuccess();
        }
        return delegate.export(filteredSpans);
    }

    @Override
    public CompletableResultCode flush() {
        return delegate.flush();
    }

    @Override
    public CompletableResultCode shutdown() {
        return delegate.shutdown();
    }
}

// Composite exporter — отправка в два бекенда одновременно
// Из JavaDoc SpanExporter.composite()
SpanExporter compositeExporter = SpanExporter.composite(
    OtlpGrpcSpanExporter.builder().setEndpoint("http://jaeger:4317").build(),
    new FilteringEnrichingSpanExporter(
        OtlpGrpcSpanExporter.builder().setEndpoint("http://tempo:4317").build(),
        List.of("/actuator/health", "/metrics")
    )
);
```

***

## 10. Метрики — Counter, Histogram, Gauge в Spring сервисах

Паттерн из CNCF/New Relic blog + официальный Metrics API:[^21][^22][^23]

```java
// Инструментация бизнес-метрик через OTel Metrics API
// Аналогично паттернам из Quarkus и Micronaut contrib
@Component
public class PlatformMetrics {

    private final LongCounter requestCounter;
    private final LongHistogram processingDuration;
    private final ObservableLongGauge queueSizeGauge;

    public PlatformMetrics(OpenTelemetry openTelemetry) {
        Meter meter = openTelemetry.getMeter("platform.metrics");

        // Counter — монотонно растущий счётчик
        this.requestCounter = meter.counterBuilder("platform.requests.total")
            .setDescription("Total number of processed requests")
            .setUnit("{request}")
            .build();

        // Histogram — распределение времён обработки
        this.processingDuration = meter.histogramBuilder("platform.processing.duration")
            .setDescription("Request processing duration")
            .setUnit("ms")
            .ofLongs()
            .build();

        // Observable Gauge — текущий размер очереди (async инструмент)
        this.queueSizeGauge = meter.gaugeBuilder("platform.queue.size")
            .setDescription("Current queue depth")
            .setUnit("{item}")
            .ofLongs()
            .buildWithCallback(measurement -> 
                measurement.record(getQueueSize(), Attributes.empty())
            );
    }

    public void recordRequest(String operation, String status, long durationMs) {
        Attributes attrs = Attributes.builder()
            .put("operation", operation)
            .put("status", status)
            .build();
        requestCounter.add(1, attrs);
        processingDuration.record(durationMs, attrs);
    }
}
```

***

## 11. Аннотации @WithSpan и @SpanAttribute — декларативный подход

Паттерн из официальной документации OpenTelemetry + Spring Boot Starter:[^24][^3]

```java
// Зависимость: opentelemetry-instrumentation-annotations
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import io.opentelemetry.api.trace.SpanKind;

@Service
public class PaymentService {

    // Автоматически создаёт span с именем PaymentService.processPayment
    @WithSpan(kind = SpanKind.INTERNAL)
    public PaymentResult processPayment(
            @SpanAttribute("payment.order.id") String orderId,
            @SpanAttribute("payment.amount") BigDecimal amount,
            @SpanAttribute("payment.currency") String currency) {
        // логика...
    }

    // Кастомное имя спана, отдельный trace root
    @WithSpan(value = "kafka.publish.payment.event", inheritContext = false)
    public void publishEvent(@SpanAttribute("event.type") String eventType) {
        // логика...
    }
}
```

> **Важно**: для работы `@SpanAttribute` с именами параметров необходимо компилировать с `-parameters` флагом для javac. В Gradle: `compileJava.options.compilerArgs += ['-parameters']`.[^24]

***

## 12. Resource Providers — обнаружение Kubernetes/Cloud атрибутов

Паттерн из Elastic blog + opentelemetry-java-contrib resource providers:[^25][^26][^27]

```java
// Gradle зависимости для cloud resource detection
implementation 'io.opentelemetry.contrib:opentelemetry-aws-resources:1.56.0-alpha'
implementation 'io.opentelemetry.contrib:opentelemetry-gcp-resources:1.56.0-alpha'

// Конфигурация через Kubernetes Downward API (паттерн из GCP Resources README)
// В Pod spec:
// env:
//   - name: POD_NAME
//     valueFrom: { fieldRef: { fieldPath: metadata.name } }
//   - name: NAMESPACE_NAME
//     valueFrom: { fieldRef: { fieldPath: metadata.namespace } }
//   - name: OTEL_RESOURCE_ATTRIBUTES
//     value: k8s.pod.name=$(POD_NAME),k8s.namespace.name=$(NAMESPACE_NAME)
```

```java
// Кастомный ResourceProvider через SPI (паттерн из contrib #187)
import io.opentelemetry.sdk.autoconfigure.spi.ResourceProvider;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import com.google.auto.service.AutoService;

@AutoService(ResourceProvider.class)
public class PlatformResourceProvider implements ResourceProvider {

    @Override
    public Resource createResource(ConfigProperties config) {
        return Resource.builder()
            .put("platform.name", "my-platform")
            .put("platform.team", System.getenv().getOrDefault("TEAM_NAME", "unknown"))
            .put("platform.component", detectComponentName())
            .build();
    }

    @Override
    public int order() {
        return Integer.MAX_VALUE; // выполнить последним, самый высокий приоритет
    }

    private String detectComponentName() {
        // Читаем из MANIFEST.MF, build.properties и т.д.
        return Optional.ofNullable(
            PlatformResourceProvider.class.getPackage().getImplementationTitle()
        ).orElse("unknown");
    }
}
```

***

## 13. Span Stacktrace Capture — паттерн из Elastic EDOT и contrib

Этот contrib-модуль разработан Elastic для захвата `code.stacktrace` у медленных спанов:[^28][^29]

```gradle
implementation 'io.opentelemetry.contrib:opentelemetry-span-stacktrace:1.56.0-alpha'
```

```java
// Из Elastic EDOT: активируется через env var
// OTEL_JAVA_EXPERIMENTAL_SPAN_STACKTRACE_MIN_DURATION=5ms
// Но можно и программно через AutoConfigurationCustomizer:
autoConfiguration.addSpanProcessorCustomizer((processor, config) -> {
    SpanStacktraceProcessor stacktraceProcessor = SpanStacktraceProcessor.builder()
        .setMinDuration(Duration.ofMillis(
            config.getLong("otel.java.experimental.span-stacktrace.min-duration", 5L)
        ))
        .build();
    return SpanProcessor.composite(processor, stacktraceProcessor);
});
```

***

## 14. Inferred Spans — профилирование + трейсинг (Elastic → contrib)

Этот модуль использует async-profiler для создания span-ов из методов без явной инструментации:[^30][^31]

```gradle
implementation 'io.opentelemetry.contrib:opentelemetry-inferred-spans:1.56.0-alpha'
```

```java
// Активация через конфигурацию (env):
// OTEL_INFERRED_SPANS_ENABLED=true
// OTEL_INFERRED_SPANS_SAMPLING_INTERVAL=10ms

// Или программно:
InferredSpansAutoConfig.enable(openTelemetrySdk);
```

***

## 15. BatchSpanProcessor — тюнинг для highload (из OneUptime tuning guide)

Критически важная настройка для production-сервисов с нагрузкой > 500 RPS:[^32][^33]

```java
// Формула из OneUptime guide:
// max_throughput = maxQueueSize / scheduledDelayMillis * 1000
// Default: 2048 / 5000 * 1000 = 409 spans/sec — МАЛО для highload

// Для 5000+ RPS:
BatchSpanProcessor.builder(exporter)
    .setMaxQueueSize(16384)              // 8x default
    .setMaxExportBatchSize(1024)         // 2x default
    .setScheduleDelay(Duration.ofMillis(1000))  // 5x быстрее default
    .setExporterTimeout(Duration.ofSeconds(30))
    .build();

// Мониторинг дропа спанов через BatchSpanProcessor self-metrics:
// otelcol_processor_dropped_spans (в Prometheus)
// или смотреть на: io.opentelemetry.sdk.internal.SdkObservableMeasurement
```

***

## 16. Log Correlation — связывание логов с трейсами

Паттерн из Grafana Tempo + Spring Boot auto-instrumentation:[^34][^35]

```xml
<!-- Logback: добавить trace_id и span_id в MDC для корреляции -->
<!-- При использовании OTel Java agent или Spring Boot Starter: -->
<dependency>
    <groupId>io.opentelemetry.instrumentation</groupId>
    <artifactId>opentelemetry-logback-appender-1.0</artifactId>
    <version>2.5.0-alpha</version>
</dependency>
```

```xml
<!-- logback-spring.xml — добавить %X{trace_id} и %X{span_id} в паттерн -->
<pattern>
    %d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level 
    [traceId=%X{trace_id} spanId=%X{span_id}] %logger{36} - %msg%n
</pattern>
```

***

## 17. Datadog OTel Bridge — паттерн гибридной инструментации

Когда компания использует Datadog, но хочет писать OTel-совместимый код:[^36][^37][^38]

```java
// Паттерн из ptabasso2/datadog-otel-tracing и dd-trace-java
// DD_TRACE_OTEL_ENABLED=true

// Получаем OTel TracerProvider через GlobalOpenTelemetry
// (Datadog регистрирует свою реализацию под капотом)
OpenTelemetry openTelemetry = GlobalOpenTelemetry.get();
Tracer tracer = openTelemetry.getTracer("my-service");

// При этом класс провайдера будет: 
// "datadog.opentelemetry.shim.trace.OtelTracerProvider"
// но API полностью совместимо с OTel
```

***

## 18. Elastic APM OpenTelemetry Bridge

Elastic APM поддерживает OpenTelemetry API как bridge — можно использовать OTel API, данные идут в Elastic:[^39][^40]

```java
// Elastic APM + OTel Bridge (из elastic.co/docs)
// Агент: elastic-apm-agent-*.jar
// -Delastic.apm.use_elastic_traceparent_header=true
// -Delastic.apm.enable_experimental_instrumentations=true

// В коде — стандартный OTel API, Elastic перехватывает через bridge
Span span = GlobalOpenTelemetry.getTracer("elastic-bridge-demo")
    .spanBuilder("custom-operation")
    .startSpan();
// span автоматически становится Elastic APM Transaction/Span
```

***

## Таблица: Сравнение коммерческих дистрибутивов по подходу к OTel SDK

| Вендор | Подход | OTel API | Кастомные расширения | Ссылка |
|--------|--------|----------|----------------------|--------|
| **Elastic EDOT** | Дистрибутив OTel agent | Полная поддержка | Inferred Spans, Stacktrace, Baggage Processor | [^29] |
| **Datadog** | Shim поверх dd-trace-java | Tracing (метрики — beta) | OTel-compatible TracerProvider | [^36][^38] |
| **New Relic** | OTel distribution + OTLP ingest | Полная поддержка | Минимальные расширения | [^41] |
| **Honeycomb** | OTel distribution (архив 2025) | Полная поддержка | DeterministicSampler, BaggageSpanProcessor | [^42][^43] |
| **Grafana** | EDOT-like distribution | Полная поддержка | Pre-configured LGTM stack | [^44] |
| **Quarkus** | Встроенная интеграция | Полная поддержка | Vert.x OTLP exporter, Micrometer bridge | [^45][^46] |

***

## Рекомендованные зависимости Gradle для платформенного стартера

```gradle
// build.gradle — платформенный OTel стартер
implementation platform("io.opentelemetry:opentelemetry-bom:1.46.0")
implementation platform("io.opentelemetry.instrumentation:opentelemetry-instrumentation-bom-alpha:2.13.0-alpha")

// Core API и SDK
implementation 'io.opentelemetry:opentelemetry-api'
implementation 'io.opentelemetry:opentelemetry-sdk'
implementation 'io.opentelemetry:opentelemetry-sdk-trace'
implementation 'io.opentelemetry:opentelemetry-sdk-metrics'
implementation 'io.opentelemetry:opentelemetry-sdk-logs'

// Exporters
implementation 'io.opentelemetry:opentelemetry-exporter-otlp'

// SDK Autoconfigure
implementation 'io.opentelemetry:opentelemetry-sdk-extension-autoconfigure'

// Propagators
implementation 'io.opentelemetry:opentelemetry-extension-trace-propagators'  // B3

// Spring Boot Starter
implementation 'io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter'

// Annotations для @WithSpan
implementation 'io.opentelemetry.instrumentation:opentelemetry-instrumentation-annotations'

// Contrib — production-grade расширения
implementation 'io.opentelemetry.contrib:opentelemetry-baggage-processor:1.56.0-alpha'
implementation 'io.opentelemetry.contrib:opentelemetry-span-stacktrace:1.56.0-alpha'
// implementation 'io.opentelemetry.contrib:opentelemetry-inferred-spans:1.56.0-alpha'  // требует async-profiler
implementation 'io.opentelemetry.contrib:opentelemetry-aws-resources:1.56.0-alpha'
implementation 'io.opentelemetry.contrib:opentelemetry-gcp-resources:1.56.0-alpha'

// Logback bridge
implementation 'io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0'

// Micrometer bridge (для Spring Boot 3 с Actuator)
implementation 'io.micrometer:micrometer-tracing-bridge-otel'
```

***

## Итоговые архитектурные рекомендации

1. **Не создавай второй OpenTelemetrySdk** — используй `GlobalOpenTelemetry.get()` или DI-бин, чтобы не конфликтовать с agent-ом.[^1]
2. **`parentbased_traceidratio` вместо `traceidratio`** — иначе в production получишь неполные трейсы.[^8]
3. **BaggageSpanProcessor** — добавь в стартер по умолчанию для автоматической корреляции `correlation-id`, `user-id`, `tenant-id` во все спаны и логи.[^6]
4. **BatchSpanProcessor tuning** — default 2048 очереди недостаточно для нагрузок > 400 RPS, увеличивай до 8192–16384.[^32]
5. **Composite propagator** — всегда включай W3CTraceContext + W3CBaggage + свои корпоративные заголовки.[^47]
6. **ResourceProvider SPI** — регистрируй через `@AutoService` для автоматического подхвата в autoconfigure-режиме.[^48]
7. **Span Stacktrace** для медленных спанов — включай с порогом 5–50ms, помогает найти bottleneck без профилировщика.[^29]

---

## References

1. [Add custom instrumentation to Java applications with OpenTelemetry](https://www.alibabacloud.com/help/en/arms/application-monitoring/use-cases/use-opentelemetry-sdk-for-java-to-manually-instrument-applications) - This guide covers four instrumentation tasks: Create custom spans. Add attributes to a span. Propaga...

2. [opentelemetry-java-instrumentation/examples/distro/README.md at main · open-telemetry/opentelemetry-java-instrumentation](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/examples/distro/README.md) - OpenTelemetry auto-instrumentation and instrumentation libraries for Java - open-telemetry/opentelem...

3. [Spring Boot starter | OpenTelemetry](https://opentelemetry.io/docs/zero-code/java/spring-boot-starter/) - You can use two options to instrument Spring Boot applications with OpenTelemetry. The default choic...

4. [SDK configuration - OpenTelemetry](https://opentelemetry.io/docs/zero-code/java/spring-boot-starter/sdk-configuration/) - <?code-excerpt path-base="examples/java/spring-starter"?> This spring starter supports configuration...

5. [Customize configuration in spring boot java using open telemetry automatic instrumentation](https://stackoverflow.com/questions/76441058/customize-configuration-in-spring-boot-java-using-open-telemetry-automatic-instr) - I am trying to customize otel configuration pragmatically and used the following code: @Configuratio...

6. [opentelemetry-java-contrib/baggage-processor/README.md at main](https://github.com/open-telemetry/opentelemetry-java-contrib/blob/main/baggage-processor/README.md) - Usage with declarative configuration. You can configure the baggage span and log record processors u...

7. [Request for new component: Baggage Span Processor · Issue #1277](https://github.com/open-telemetry/opentelemetry-java-contrib/issues/1277) - The Honeycomb distro currently provides a Span processor that takes items from the baggage and adds ...

8. [traceidratio vs parentbased_traceidratio · open-telemetry opentelemetry-java-instrumentation · Discussion #6304](https://github.com/open-telemetry/opentelemetry-java-instrumentation/discussions/6304) - Hi, Can you please clarify the sampling behavior of parentbased_traceidratio compared to traceidrati...

9. [Open Telemetry Sampling Techniques in Python/Java/Go: Optimizing Observability with Selective Data Collection](https://dev.to/0x113/open-telemetry-sampling-techniques-in-pythonjavago-optimizing-observability-with-selective-data-collection-56oi) - Open Telemetry is a vendor-agnostic, open-source observability framework that provides a standard wa...

10. [W3CBaggagePropagator wipe baggage from other propagators · Issue #5883 · open-telemetry/opentelemetry-java](https://github.com/open-telemetry/opentelemetry-java/issues/5883) - Describe the bug I am currently working on developing custom propagators that parse baggage from spe...

11. [Support opentelemetry Javaagent extensions · Issue #1758 - GitHub](https://github.com/open-telemetry/opentelemetry-operator/issues/1758) - Hi everyone! We are looking into implement an custom propagator to propagate x-request-id for ISTIO ...

12. [Mingration from OpenTracing: the baggageItem/context propagation. · open-telemetry opentelemetry-java · Discussion #5651](https://github.com/open-telemetry/opentelemetry-java/discussions/5651) - With OpenTracing, for context propagation, I have this very simple utility class: public MyClass { p...

13. [Baggage | OpenTelemetry](https://opentelemetry.io/docs/concepts/signals/baggage/) - Contextual information that is passed between signals.

14. [GitHub - jtayl222/grpc-java-instrumentation](https://github.com/jtayl222/grpc-java-instrumentation) - Contribute to jtayl222/grpc-java-instrumentation development by creating an account on GitHub.

15. [How to propagate OpenTelemetry Contexts using manual instrumentation in a Java/gRCP application? · open-telemetry opentelemetry-java-instrumentation · Discussion #9139](https://github.com/open-telemetry/opentelemetry-java-instrumentation/discussions/9139) - I am working with an application that is implemented using Java, a microservices architecture, and a...

16. [Trace context propagation in grpc java service](https://stackoverflow.com/questions/77086200/trace-context-propagation-in-grpc-java-service) - I have a core java based service which communicates with another .NET service through grpc calls. Th...

17. [How to create Context using traceId in Open Telemetry](https://stackoverflow.com/questions/72668718/how-to-create-context-using-traceid-in-open-telemetry) - To link spans from remote processes, it is sufficient to set the Remote Context as parent. Copy. Spa...

18. [Problem propagate kafka header telemetry · open-telemetry opentelemetry-java-instrumentation · Discussion #5739](https://github.com/open-telemetry/opentelemetry-java-instrumentation/discussions/5739) - HI. I trying to implement Opentelemetry in some java project and I am stuck I do not know if I missi...

19. [GitHub - RETIT/opentelemetry-javaagent-extension](https://github.com/RETIT/opentelemetry-javaagent-extension) - This repository contains an extension for the OpenTelemetry Java Auto-Instrumentation agent to colle...

20. [SpanExporter (OpenTelemetry SDK For Tracing)](https://javadoc.io/static/io.opentelemetry/opentelemetry-sdk-trace/1.0.1/io/opentelemetry/sdk/trace/export/SpanExporter.html)

21. [Trace Configuration](https://www.cncf.io/blog/2021/09/22/understand-opentelemetry-part-4-instrument-a-java-app-with-opentelemetry/) - Guest post originally published on New Relic’s blog by Jack Berg, engineer at New Relic This blog po...

22. [opentelemetry-specification/specification/metrics/api.md at main · open-telemetry/opentelemetry-specification](https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/metrics/api.md) - Specifications for OpenTelemetry. Contribute to open-telemetry/opentelemetry-specification developme...

23. [Metrics API | OpenTelemetry](https://opentelemetry.io/docs/specs/otel/metrics/api/) - Status: Stable, except where otherwise specified Overview The Metrics API consists of these main com...

24. [Annotations | OpenTelemetry](https://opentelemetry.io/docs/zero-code/java/agent/annotations/) - When a span is created for an annotated method, the values of the arguments to the method invocation...

25. [Automatic cloud resource attributes with OpenTelemetry Java](https://www.elastic.co/observability-labs/blog/opentelemetry-java-automatic-cloud-resource-attributes) - Capturing cloud resource attributes allow to describe application cloud deployment details. In this ...

26. [opentelemetry-java-contrib/gcp-resources/README.md at main · open-telemetry/opentelemetry-java-contrib](https://github.com/open-telemetry/opentelemetry-java-contrib/blob/main/gcp-resources/README.md) - Contribute to open-telemetry/opentelemetry-java-contrib development by creating an account on GitHub...

27. [Configuration - OpenTelemetry](https://opentelemetry.io/docs/zero-code/java/agent/configuration/) - For more information This page describes the various ways in which configuration can be supplied to ...

28. [capture code.stacktrace and mutable spans · open-telemetry opentelemetry-java-instrumentation · Discussion #10973](https://github.com/open-telemetry/opentelemetry-java-instrumentation/discussions/10973) - Hi, We currently have a feature in our OTel agent distribution that allows to capture the code.stack...

29. [Span Stacktrace](https://www.elastic.co/docs/reference/opentelemetry/edot-sdks/java/features) - Explore the features of the Elastic Distribution of OpenTelemetry (EDOT) Java Agent, including inher...

30. [Revealing unknowns in your tracing data with inferred spans in ...](https://www.elastic.co/observability-labs/blog/tracing-data-inferred-spans-opentelemetry) - By combining profiling techniques with distributed tracing, Elastic provides the inferred spans feat...

31. [opentelemetry-inferred-spans - Maven Central](https://central.sonatype.com/artifact/io.opentelemetry.contrib/opentelemetry-inferred-spans) - Discover opentelemetry-inferred-spans in the io.opentelemetry.contrib namespace. Explore metadata, c...

32. [How to Tune BatchSpanProcessor maxQueueSize ... - OneUptime](https://oneuptime.com/blog/post/2026-02-06-tune-batchspanprocessor-high-throughput/view) - Learn how to tune BatchSpanProcessor parameters like maxQueueSize, scheduledDelayMillis, and maxExpo...

33. [BatchSpanProcessorBuilder (OpenTelemetry SDK For ...](https://javadoc.io/static/io.opentelemetry/opentelemetry-sdk-trace/1.26.0/io/opentelemetry/sdk/trace/export/BatchSpanProcessorBuilder.html) - declaration: package: io.opentelemetry.sdk.trace.export, class: BatchSpanProcessorBuilder

34. [Logger MDC auto-instrumentation with opentelemetry- ...](https://github.com/open-telemetry/opentelemetry-java-instrumentation/discussions/7653) - Hello, I have a problem with a correlation between logs and traces, log statements don't contain any...

35. [Auto-instrumenting a Java Spring Boot application for traces and ...](https://grafana.com/blog/auto-instrumenting-a-java-spring-boot-application-for-traces-and-logs-using-opentelemetry-and-grafana-tempo/) - Let's dig into this technology and see how we can offload traces to Tempo and logs to Loki to create...

36. [No support for OpenTelemetry Metrics API · Issue #7924 · DataDog/dd-trace-java](https://github.com/DataDog/dd-trace-java/issues/7924) - Objectives My hope is that this issue will serve to: Assess the Datadog team's interest in supportin...

37. [GitHub - ptabasso2/datadog-otel-tracing: Custom tracing using the OpenTelementry API alongside the Datadog java agent](https://github.com/ptabasso2/datadog-otel-tracing) - Custom tracing using the OpenTelementry API alongside the Datadog java agent - ptabasso2/datadog-ote...

38. [OpenTelemetry Tracing Experimental Feature Questions · Issue #4935 · DataDog/dd-trace-java](https://github.com/DataDog/dd-trace-java/issues/4935) - This could be as simple as user error but I tried using a number of features with OpenTelemetry and ...

39. [Elastic APM - Custom Java Instrumentation with OpenTelemetry](https://www.youtube.com/watch?v=DmzDuHBbxM0) - ... Elastic APM Agent and leveraging the OpenTelemetry Java Agent using its Extensions framework. .....

40. [OpenTelemetry bridge | APM Java agent - Elastic](https://www.elastic.co/docs/reference/apm/agents/java/opentelemetry-bridge) - The Elastic APM OpenTelemetry bridge allows creating Elastic APM Transactions and Spans using the Op...

41. [New Relic OpenTelemetry Java agent distribution · GitHub](https://github.com/newrelic/newrelic-opentelemetry-integration-java) - This is a custom distribution of the OpenTelemetry Java agent that uses the New Relic OpenTelemetry ...

42. [Honeycomb's OpenTelemetry Java SDK distribution. This ... - GitHub](https://github.com/honeycombio/honeycomb-opentelemetry-java) - This is Honeycomb's distribution of OpenTelemetry for Java. It makes getting started with OpenTeleme...

43. [Honeycomb OpenTelemetry Distro for Java reaches 1.0 release](https://www.honeycomb.io/blog/opentelemetry-distro-java-release) - Honeycomb's OpenTelemetry Distribution for Java is our first OTel Distro to come out of beta. Learn ...

44. [Instrument a JVM application | OpenTelemetry documentation](https://grafana.com/docs/opentelemetry/instrument/grafana-java/) - The Grafana OpenTelemetry distribution for Java provides a pre-configured and pre-packaged bundle of...

45. [Quarkus instrumentation - OpenTelemetry](https://opentelemetry.io/docs/zero-code/java/quarkus/) - Quarkus is an open source framework designed to help software developers build efficient cloud nativ...

46. [docs/src/main/asciidoc/opentelemetry.adoc - Quarkus](https://fossies.org/linux/quarkus/docs/src/main/asciidoc/opentelemetry.adoc)

47. [multiple propagation headers: B3 and W3C · open-telemetry opentelemetry-java · Discussion #5000](https://github.com/open-telemetry/opentelemetry-java/discussions/5000) - Hi all, I am new to otel please bear with me, in case of manual instrumentation is it possible to us...

48. [Fix default `service.name` + simplify configuration using Otel AutoConfig SDK 1.10 ResourceProvider SPI improvements by cyrille-leclerc · Pull Request #187 · open-telemetry/opentelemetry-java-contrib](https://github.com/open-telemetry/opentelemetry-java-contrib/pull/187) - Description: Fix default service.name + simplify configuration using Otel AutoConfig SDK 1.10 Resour...

