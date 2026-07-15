# ADR: Outbound propagation платформенных заголовков (agent-compatible)

| Поле | Значение |
|------|----------|
| Статус | **Принято** |
| Дата | 2026-06-09 |
| Контекст | Фаза 12 — Custom propagation для HTTP и Kafka; завершение п.4 [ADR-context-first-propagation.md](./ADR-context-first-propagation.md) |
| Стек | OTel BOM 1.62.0, OTel Agent 2.28.1, Spring Boot 3.5.x, Java 21 |
| Связанные ADR | [ADR-context-first-propagation.md](./ADR-context-first-propagation.md), [ADR-request-id-correlation-id.md](./ADR-request-id-correlation-id.md), [ADR-baggage-filtering-spike-finding.md](./ADR-baggage-filtering-spike-finding.md), [ADR-otel-direct-integration.md](./ADR-otel-direct-integration.md) |

## Проблема

`PlatformTraceControlPropagator.inject()` требует `PROPAGATION_DECISION` в Context (secure-by-default), но ничто его не выставляло — платформенные заголовки (`X-Trace-On`, `X-QA-Trace`, `X-Request-Id`) наружу не уходили. Не было client-интерсепторов HTTP/Kafka и outbound-политики.

## Решение

**Agent-compatible режим.** Платформа НЕ создаёт HTTP/Kafka span'ы и НЕ инжектит W3C `traceparent`/`tracestate` (это зона OTel Java Agent). Платформенный слой добавляет только policy-driven исходящую передачу платформенных управляющих заголовков:

1. **Secure-by-default DENY + trusted gating.** Исходящая передача выключена по умолчанию (`platform.tracing.propagation.outbound.enabled=false`). При включении заголовки уходят только на доверенные destination (`trusted-host-patterns` для HTTP, `trusted-topic-patterns` для Kafka). Имя `enabled` (а не `propagate-to-external`) однозначно: `false` = ничего не уходит даже на internal trusted.
2. **Контракты в `platform-tracing-api`, реализация в `platform-tracing-core`.** Типы outbound/inbound control-plane (`OutboundPropagationDecision`, `PlatformTraceContextKeys`, `InboundTraceControl`, `TrustedDestinationMatcher`, `OutboundPropagationPolicy`, `TraceControlHeaderInjector`, `InboundTraceControlExtractor`) — контракты в **api** (видны app-classloader и agent-classloader через embedding). Поведение (`DefaultOutboundPropagationPolicy`, `DefaultTraceControlHeaderInjector`, `DefaultInboundTraceControlExtractor`, `TrustedDestinationMatchers`) — в **core.propagation.control**. Agent extension jar включает **api + core** (`agentExtensionJar`), поэтому classloader-граница сохранена. `InboundTraceControlPropagator.inject()` делегирует в `TraceControlHeaderInjector` (нет дрейфа логики).
3. **Interceptor-only инжекция платформенных заголовков.** `PROPAGATION_DECISION` выставляет только client-интерсептор (на основании trusted-решения). В глобальной outbound-цепочке Агента `inject()` без decision — no-op. Инжектор идемпотентен (перезапись того же ключа), W3C/baggage не трогает -> нет дублей с Агентом.
4. **`propagateForceTrace=false` по умолчанию.** W3C sampled-flag в `traceparent` уже переносит решение о записи на downstream (parent-based sampling). Проброс `X-Trace-On` наружу избыточен в большинстве случаев. **Escape hatch:** `propagate-force-trace=true`, если downstream использует head-ratio sampler, игнорирующий parent sampled-flag (sampled-flag — best-effort, не форс по W3C).
5. **Security (CWE-113).** Входящие платформенные заголовки — недоверенный ввод: allowlist-формат + reject-and-regenerate (`RequestIdSupport`), запись только санитизированных/контролируемых значений.
6. **TrustedDestinationMatcher hardening.** Канонизация host (lower-case, срез порта/userinfo/trailing-dot), label-aware glob (`*` — один label, `**` — много), запрет IP-литералов по умолчанию.

## Что НЕ делаем

- Не дублируем W3C propagation и span'ы (зона Агента).
- Не трогаем baggage в client-интерсепторах (фильтруется `FilteringBaggagePropagator` в глобальной цепочке).
- Не используем `tracestate` как носитель force-trace (vendor-лимиты; sampled-flag уже несёт решение).
- **Не читаем и не пишем W3C trace-flags** — ни `sampled` (бит `0x01`), ни `random-trace-id` (бит `0x02`, W3C Trace
  Context Level 2). Прозрачный перенос флагов обязателен по стандарту и относится исключительно к Агенту/
  `W3CTraceContextPropagator`. Force-trace выражается только отдельным платформенным заголовком `X-Trace-On`, а не
  манипуляцией trace-flags. Любой собственный парсер/мутатор `traceparent`/`tracestate` запрещён (риск рассинхрона с
  эволюцией Level 2/Level 3).
- Не вводим отдельные модули `propagation-http`/`-kafka` (объём кода мал — интеграция в существующие autoconfigure-модули).

## Control-plane сигналы vs domain-метаданные

Три платформенных заголовка (`X-Trace-On`, `X-QA-Trace`, `X-Request-Id`) — это **control-plane сигналы**
(force-record / QA-маркер / correlation id), а НЕ канал для domain-данных. Любые user/tenant/QoS/feature-flag и прочие
бизнес-метаданные ДОЛЖНЫ передаваться через **OTel Baggage** (зона Агента + `FilteringBaggagePropagator`), а не плодить
новые custom-заголовки. Опыт платформенных команд (Skyscanner): передача domain-контекста через custom-заголовки
«сложнее, чем кажется» — минимальный header-surface из 3 control-заголовков выбран сознательно (плюс к безопасности).

## Edge / trust boundary (операционная ответственность gateway)

Agent-compatible режим **доверяет** входящему W3C trace-context и делегирует его extract Агенту — это верно для
**внутренних** сервисов за mesh/gateway. Для **публичных/edge** сервисов входящий `traceparent`/`tracestate`/`baggage`
от недоверенных источников надо **удалять/рестартовать на границе** (ingress / API gateway / Cloudflare), иначе Агент
усыновит поддельного родителя (Honeycomb «Phantom Spans»; согласуется с OTel «sanitize incoming context from untrusted
sources» и W3C «restart trace при невалидном `traceparent`»).

**Решение:** это **операционная политика gateway**, а НЕ код платформенной библиотеки. Платформа сознательно не строит
второй W3C-pipeline (см. «Что НЕ делаем»). Эксплуатационные указания — в [SUPPORTED.md](../SUPPORTED.md) (Edge trust
boundary, Istio `preserve_external_request_id`). Поведение внутренних доверенных сервисов не меняется.

## Конфигурация

```yaml
platform:
  tracing:
    propagation:
      outbound:
        enabled: false                 # master switch (secure-by-default)
        trusted-host-patterns: []       # label-aware glob; * — один label, ** — много
        allow-ip-literals: false
        propagate-force-trace: false    # escape hatch для non-parent-based downstream
        propagate-qa-trace: false
        propagate-request-id: true
    kafka:
      mode: agent-compatible            # agent-compatible | disabled
      propagate-platform-headers: false
      trusted-topic-patterns: []
```

## Последствия

- Платформенные заголовки уходят наружу только осознанно и только на доверенные destination.
- Совместимость с Агентом гарантирована: нет дублей span'ов и W3C-заголовков.
- Рекомендуемый agent-флаг для async messaging: `otel.instrumentation.messaging.experimental.receive-telemetry.enabled=true` (consumer -> новый trace + span link на producer).
- **Kafka producer interceptor:** инжект только в `onSend()` (до перевода `Headers` в read-only). Диагностику НЕ строить
  на `onAcknowledgement` (headers уже недоступны, KIP-512); ретраи — через настройки продюсера, не ручной resend.
  Сеттер идемпотентен по построению (`remove(key).add(...)`), меняет только headers (не key/value/partition — log
  compaction safety).
