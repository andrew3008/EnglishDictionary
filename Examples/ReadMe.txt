Вот глубокое исследование с production-кодом из реальных OpenSource и коммерческих проектов по использованию OpenTelemetry SDK.

Отчёт охватывает 18 ключевых паттернов с боевым кодом, собранным из:

OpenSource-дистрибутивов: Elastic EDOT, Honeycomb Java Distro, Grafana, Quarkus, Micronaut

Коммерческих вендоров: Datadog dd-trace-java, New Relic Java Agent, Elastic APM OTel Bridge

Официального contrib: opentelemetry-java-contrib — BaggageSpanProcessor, InferredSpans, SpanStacktrace

Проблемных GitHub Issues/Discussions: Kafka context propagation, gRPC interceptors, Baggage API

Ключевые находки, которые стоит перенять прямо сейчас:

BaggageSpanProcessor (Honeycomb → contrib) — автоматически копирует correlation-id, user-id, tenant-id из Baggage во все span-атрибуты и лог-атрибуты

HealthcheckFilterSampler — кастомный Sampler для исключения /actuator/health и /metrics из трейсов, паттерн из Elastic EDOT и Erlang SDK

XRequestIdPropagator — кастомный TextMapPropagator для проброса X-Request-Id через Baggage, актуален для Istio

BatchSpanProcessor tuning: default 2048 spans в очереди = только ~409 spans/sec, для highload поднимать до 16384

Datadog OTel Bridge: при DD_TRACE_OTEL_ENABLED=true Datadog регистрирует свою реализацию в GlobalOpenTelemetry, код остаётся OTel-совместимым