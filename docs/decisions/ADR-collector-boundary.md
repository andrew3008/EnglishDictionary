# ADR: Collector Boundary — контракт SDK ↔ Collector + pin Contrib 0.154.0 (Track B+)

| Поле | Значение |
|------|----------|
| Статус | **Принято (Track B+; boundary-контракт дополнен по итогам Spike S1–S3)** |
| Дата adoption | 2026-06-10 |
| Родитель | Фаза 16 Collector Boundary |
| Spike baseline | `E:\Platform_Traces_Archive\Testing\Harness` S0–S3 (вердикты: S1 FALLBACK→GO variant-b, S2 GO, S3 GO) |

## Контекст

Платформа поставляет версионируемые YAML-конфигурации Collector (`platform-tracing-collector-config`).
До Фазы 16 pin был `otel/opentelemetry-collector-contrib:0.110.0`. Архитекторы утвердили **Track B+**:
переход на latest stable GA **0.154.0** перед spike'ами и production rollout.

## Решение

| Компонент | Pin |
|-----------|-----|
| OTel Collector Contrib (gateway, agent validate) | **0.154.0** |
| Jaeger all-in-one (e2e, spike Harness) | **1.62.0** |
| Feature gate (gateway) | `+processor.tailsamplingprocessor.recordpolicy` |

Изменённые артефакты Platform_Traces:

- `platform-tracing-collector-config/build.gradle` — `validateCollectorConfigs`
- `platform-tracing-e2e-tests` — Testcontainers image
- `docs/SUPPORTED.md` — compatibility matrix

## Обоснование

1. **Reproducibility** — GA pin, не nightly (`v0.155.0-nightly`).
2. **Routing connector** — с 0.116 routing processor deprecated; с 0.120 удалён `match_once`; connector — единственный путь TTL-tiers.
3. **recordpolicy** — доступен с v0.120+, стабилен на 0.154.0 для audit attr `tailsampling.policy`.
4. **Cosign** — теги `sha256-....sig` являются payload подписи, не runtime-образом.

## Риски и митигация (spike-фаза)

| Риск | Митигация |
|------|-----------|
| Internal metrics rename (Prometheus 3.0, с 0.120) | PR-0.5.4: runbook review; Datadog migration guide |
| Changelog 0.111–0.154 breaking changes | PR-0.5.1 changelog review перед spike S1–S3 |
| TCCL / ExtensionClassLoader в Agent + bootJar | S2 `/extension-smoke` (Harness Archive) |
| Trace fragmentation при routing `context: span` | S1 `/nested-trace` + variant-e `groupbytrace` |
| Health-check RAM при Collector-only drop | S3 #1b/#1c; Head+Tail recommendation в SUMMARY |

## Boundary-контракт SDK ↔ Collector (Фаза 16)

### Таблица ответственности → артефакты проекта

| Зона | Обязанность | Артефакт |
|------|-------------|----------|
| SDK | Context propagation | OTel Agent W3C + `PlatformTraceControlPropagator` (named SPI) |
| SDK | Forced recording (`X-Trace-On`/`X-QA-Trace`) | `ForceHeaderRule`/`QaTraceRule` в `CompositeSampler` |
| SDK | Head sampling (ratio/route/drop-paths) | `CompositeSampler` + `SamplerStateHolder` (runtime JMX) |
| SDK | Span limits | `TracingProperties.Limits` → `OTEL_SPAN_*` |
| SDK | Masking, 1-я линия | `ScrubbingSpanProcessor` + `BuiltInSpanAttributeScrubbingRules` + SPI |
| SDK | Resource service identity | `PlatformResourceProvider` |
| SDK | App overhead protection | bounded drop-oldest очередь, export timeout, no-op fallback |
| SDK | Enrichment | `EnrichingSpanProcessor`/`ClassificationSpanProcessor` |
| Collector | Tail sampling (errors/slow/forced/priority/drop/baseline) | `otel-collector-gateway-tail-sampling.yaml` |
| Collector | Redaction, 2-я линия | `redaction/platform-second-line` (gateway) |
| Collector | Infrastructure metadata (`k8s.*`) | `k8sattributes` (agent-tier, ADR-resource-merge-precedence) |
| Collector | Retention routing (TTL-tiers) | `otel-collector-config-ttl-tiers.yaml` (routing connector) |
| Collector | Retry/queue до backend | `otlp.sending_queue` + `retry_on_failure` |

### Минимальный контракт атрибутов SDK → Collector-политики

Единственный source of truth значений `platform.sampling.reason` —
`PlatformSamplingReasons` (`platform-tracing-api`). Политики `string_attribute` в YAML
обязаны ссылаться только на `PlatformSamplingReasons.EXPORTED`
(`force_header`, `qa_trace`, `parent_sampled`, `route_ratio`, `global_ratio`);
DROP-значения Collector никогда не видит. Разрешённые ключи политик:
`platform.sampling.reason`, `platform.trace.priority`, `http.route`.
Контракт проверяется машинно: `CollectorPolicyContractTest`
(`platform-tracing-collector-config/src/test`, JVM-only, без Docker).

`parent_sampled` сознательно НЕ входит в forced-policy: parent-sampled трафик проходит
общий baseline/error-фильтр, иначе forced-policy вырождается в keep-all
(решение архитектурного ревью Фазы 16).

### Порядок процессоров (инвариант, проверяется contract-тестом)

- Gateway: `memory_limiter` → `transform/platform-semconv-backstop` →
  `redaction/platform-second-line` → `tail_sampling` → `batch`.
- Agent: `memory_limiter` → `k8sattributes` → `batch`.
- TTL-tiers: `memory_limiter` → `tail_sampling` → `transform/copy-result-to-resource` →
  routing connector → per-pipeline `batch`.

### Правило анти-фрагментации (Spike S1)

Routing connector маршрутизирует трейсы между backend'ами **только по
resource-атрибутам** (`context: resource`). `context: span` синтаксически валиден,
но расщепляет multi-span трейс между пайплайнами (error-child уходит в long,
parent и success-children — в short; доказано Harness `/nested-trace`).
Эскалация span-признака на resource выполняется `transform`-процессором и опирается
на trace affinity (`load_balancing routing_key=traceID` на agent-tier).
Fallback при нарушении affinity/late spans — `groupbytrace` перед transform
(variant-e Spike S1, ценой RAM/latency).

### Иерархия каналов конфигурации

1. **SDK runtime-policy** — JMX/actuator (`SamplerStateHolder`, Фаза 14), без редеплоя.
2. **SDK topology** — startup-properties (ADR-runtime-config-policy-vs-topology).
3. **Collector-политики** — env-vars (`${env:TAIL_SAMPLING_*:-default}`) + GitOps-процесс SRE.

Синтаксис env-переменных в YAML — строго `${env:VAR:-default}`: легаси-форма
`${VAR:default}` на contrib ≥ 0.123 интерпретируется как URI-провайдер и ломает
`validate` (находка Spike S3).

### Anti-patterns (зафиксировано, не делаем)

- «Толстый SDK»: multi-export, backend routing, tail sampling, durable queue в SDK.
- «Collector всё исправит»: перенос первичной маскировки в Collector (данные уже покинули процесс).
- `allowed_keys`-режим redaction на Collector'е (blast radius всего трафика; allowlist — зона SDK).
- spanmetrics ПОСЛЕ tail_sampling: RED-метрики деривируются из 100% трафика ДО сэмплирования.
  Metrics-стека в репо нет — правило фиксируется на будущее.
- Health-check suppression только на Collector'е: tail-drop держит трейсы в RAM до
  `decision_wait`. Паттерн **Head + Tail**: первичный drop на SDK (`drop-paths`),
  tail-policy `drop-successful-infra-noise` — backstop (риск R3, runbook).

### Exception-event scrubbing: app-side (SDK) vs agent-emitted (Collector) — Wave A4

Контекст: `ScrubbingSpanProcessor` скрабит только span-attributes, НЕ events (by design).
Exception-event'ы (`exception.message`/`exception.stacktrace`) — отдельная PII-поверхность.

**Проверено по исходникам** (`E:\Platform_Traces_Examples\src`):

- **SDK не может скрабить уже записанные events.** `ReadWriteSpan = Span + ReadableSpan`
  (`opentelemetry-java`, sdk/trace): write-сторона append-only (`addEvent`), API
  удаления/замены event'а нет; `SdkSpan` отдаёт events наружу только как unmodifiable
  (`getImmutableTimedEvents`). Мутация events в `ExtendedSpanProcessor.onEnding` невозможна
  без reflection (анти-паттерн). Следовательно SDK-level scrub агентских exception-event'ов
  нежизнеспособен.
- **Collector redaction обрабатывает атрибуты events.** В contrib 0.154.0 `redactionprocessor`
  вызывает `processSpanEvents(span.Events())` → `processAttrs(event.Attributes())`
  (`processor/redactionprocessor/processor.go`). Значит `redaction/platform-second-line`
  на gateway покрывает и `event.attributes[exception.message]` — value-based маскировка по
  `blocked_values` (JWT/Bearer/e-mail/PAN), а не удаление ключа (режим `allow_all_keys`).

**Матрица ответственности exception-events:**

| Источник span'а | Путь записи | Скрабинг |
|------------------|-------------|----------|
| App manual / `@Traced` / `inSpan` | `ExceptionRecorder` (фасад, scope'ы, Kafka-aspect) | SDK app-side: секьюр-дефолт, `exception.type` only (Wave A1) |
| Agent auto-instrumentation (HTTP/DB) `recordException` | OTel bytecode → raw event | Collector `redaction` backstop (value-based по `blocked_values`) |

**Решение:** app-side закрыт `ExceptionRecorder`'ом (Wave A1); для agent-emitted events —
Collector redaction backstop (новый scrubbing-фреймворк НЕ вводим). Удаление ключа
`exception.message` целиком потребовало бы `allowed_keys`-режима redaction, что отвергнуто
(blast radius всего трафика — см. anti-patterns выше); защита остаётся value-based.

Рекомендуемый follow-up (не в Wave A): контракт-тест/e2e, фиксирующий, что `blocked_values`
gateway-redaction'а покрывает `event.attributes`.

### Trade-off: aggressive head sampling vs error retention

При head ratio < 100% часть ошибок не доходит до Collector'а — осознанная цена
защиты приложения. Профили: (а) cost-first — ratio < 100%, ошибки сохраняются из
дошедшего трафика; (б) retention-first — ratio = 100% + tail-фильтрация (вся
селекция на gateway, выше нагрузка на Collector). Выбор — зона SRE per-environment.

## Критерий готовности

- `validateCollectorConfigs -PstrictValidation` — PASS
- `./gradlew :platform-tracing-e2e-tests:test -PrunE2e` — PASS (при наличии Docker)
- Spike Harness S0 bootJar + Agent path — READY

## Ссылки

- [OTel Collector Contrib releases](https://github.com/open-telemetry/opentelemetry-collector-contrib/releases)
- [routing connector README](https://github.com/open-telemetry/opentelemetry-collector-contrib/blob/main/connector/routingconnector/README.md)
- [redaction processor README](https://github.com/open-telemetry/opentelemetry-collector-contrib/tree/main/processor/redactionprocessor)
