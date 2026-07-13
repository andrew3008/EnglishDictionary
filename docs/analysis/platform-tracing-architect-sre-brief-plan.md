# План конспекта: TraceOperations × OTel best practices (для SRE и архитекторов)

> **Кто пишет / кому:** ведущий Java-разработчик (owner platform library) → архитекторы + lead SRE.  
> **Цель встречи:** получить **явное согласование границ**, не «ознакомить с observability».  
> **Источник книги:** *Observability Engineering* (TXT), главы **4–9, 15–16** — см. [observability-engineering-book-insights-for-platform-tracing.md](./observability-engineering-book-insights-for-platform-tracing.md).  
> **Итоговый объём:** **5–7 страниц** или **20–25 мин** устно. Таблицы и 2 диаграммы, без историй и философии.

---

## Принципы конспекта (без воды)

| Делать | Не делать |
|--------|-----------|
| «Книга говорит X → у нас Y → нужен sign-off Z» | Пересказ определения observability |
| Таблицы owner / responsibility | Three pillars theory, Honeycomb case studies |
| 1–2 предложения тезиса книги + сразу mapping | Цитаты длиннее двух строк |
| Ссылки на ADR и green tests (Slice 7) | Slice-by-slice roadmap |
| Вопросы на согласование в конце каждого раздела | AI, FinOps, SLO math, datastore internals |
| Java/Spring/Agent конкретика | eBPF, serverless, GraphQL примеры |

**Формула всего документа (повторить один раз в начале):**

```
OTel Java Agent = default instrumentation
traceOperations.manual() = governance для exception path (business attrs, topology, Kafka links)
Collector/SRE = tail sampling, enrich, scrub, capacity
```

---

## 0. Вступление — что просим согласовать (½ стр.)

**Заголовок слайда:** «TraceOperations v3 — не backend, а platform starter»

**4 bullet — статус на сегодня (post-Slice 7):**
- Public API: `traceContext()` + `manual()` only; единая точка создания span через `TracingImplementation`
- Agent-first production; SDK в приложении через Agent, API — compileOnly
- Pre-production: `gradlew build` GREEN, coexistence tests GREEN
- Breaking v3 без prod consumers; prod — через evidence gates

**Одна диаграмма (обязательна):**

```
Spring App
  → OTel Java Agent + platform extension     [head: ratio, drop-paths]
  → agent/local Collector
  → Gateway Collector                        [tail: errors, slow, forced]
  → Tempo/Jaeger
       ↑
  traceOperations.manual() — только governance, не замена SDK
```

**Вопрос на выходе раздела:** подтверждаем, что scope platform library — **instrumentation + policy**, не Tempo/Jaeger/Collector ownership?

---

## 1. Зачем OTel Agent, а не «свой tracing» (¾ стр.) — книга Ch. 4, 7

**Тезис книги (2 предложения):**
- Auto-instrumentation покрывает HTTP/DB/Kafka/RPC; custom — для business context.
- OTel API ≠ SDK: библиотека вызывает API (no-op без SDK); destination и SDK — конфигурируемы.

**Наша позиция:**

| Книга | TraceOperations | Артефакт |
|-------|-----------------|----------|
| Zero-code / Agent default | OTel Java Agent + extension | ADR-otel-direct-integration |
| Custom = muscles, не skeleton | `manual()` narrow facade | v3 plan §3 |
| Не vendor lock-in | OTLP → любой backend | Collector config |
| Semconv обязательны | `CategoryContracts`, typed builders, STRICT | ADR-typed-span-api |
| Один SDK на процесс | `TracingImplementation` bean singularity | Slice 2, ArchUnit |

**Анти-паттерн из книги (1 строка):** «Install agent, don't worry» без governance → span explosion и duplicate paths (у нас R01).

**Вопрос архитекторам:**
> TraceOperations — **governance facade** над OTel API, не replacement SDK и не tracing backend. OK?

**Вырезать:** Cocoa/SwiftUI, eBPF, proprietary agents history.

---

## 2. Границы ответственности: Platform vs SRE vs App (1¼ стр.) — Ch. 4, 15, 16

**Тезис книги (3 bullet):**
- Конфигурация pipeline ≠ конфигурация приложения → defer в Collector.
- Head sampling решает **до** `span.end()` → **не видит** errors/latency.
- Tail sampling буферизует trace целиком → retention инцидентов.

**Таблица ownership (ядро конспекта):**

| Зона | Владелец | Механизм | Книжное обоснование |
|------|----------|----------|---------------------|
| HTTP/DB/Kafka auto spans | Agent | bytecode instrumentation | Ch. 4 auto default |
| Head sampling | Agent/extension + platform policy | `CompositeSampler`, ratio, drop-paths | Ch. 4, 15 head = cost |
| Tail sampling | SRE / Gateway Collector | `tail_sampling` policies | Ch. 15 tail = fidelity |
| PII scrub | SDK 1st line + Collector 2nd | processors, allowlist | Ch. 7, 16 normalize |
| k8s/pod metadata | Collector `k8sattributes` | не в app code | Ch. 16 enrich |
| Exporter endpoint, queues | K8s/Helm, startup only | не runtime в Java | Ch. 16 topology |
| Runtime ratio / kill-switch | Platform Actuator/JMX | без рестарта | operational need |

**Ключевая фраза для SRE (выделить):**
> Head = экономия CPU/network. Tail = сохранение error/slow traces. Это **контракт OTel**, не недоделка platform library.

**Consistent sampling (Ch. 15):** один `trace_id` — все spans kept или dropped; иначе broken traces и ложные RCA.

**Вопросы:**
- **SRE:** two-tier topology Agent DaemonSet → Gateway `tail_sampling` + trace affinity по `trace_id`?
- **Архитекторы:** runtime tracing policy (ratio, drop-paths) в platform; topology (endpoint, pools) — только SRE/Helm?

**Ссылки:** `ADR-runtime-sampling-policy`, `ADR-collector-boundary`, `ADR-runtime-config-policy-vs-topology`.

---

## 3. Micrometer + Agent: один trace, не два (¾ стр.) — Ch. 7

**Тезис книги:** layered telemetry — traces для causality, metrics для alerting; **не дублировать** одно и то же как два независимых trace path.

**Проблема:** Spring Observation + OTel Agent без boundary → duplicate HTTP spans (R01 class).

**Решение (ADR Accepted 2026-07-06, Option C Hybrid):**

| Path | Владеет | Эмитит |
|------|---------|--------|
| OTel Agent | Trace lifecycle для HTTP/DB/Kafka | Spans → OTLP |
| Micrometer Observation | Metrics/conventions | Metrics, не второй root span |
| `traceOperations.manual()` | Custom spans | Через `TracingImplementation` / OTel API |

**Книжная формулировка:** «If your trace has a hundred two-millisecond spans, you probably have too many» — относится и к **двум root spans на один HTTP request**.

**Proof:** `DuplicateSpansRegressionMatrixTest`, `ObservationCoexistenceTest` (Slice 7).

**Вопрос архитекторам:**
> Hybrid Micrometer boundary + `suppress-micrometer-tracing` для Agent-only prod — финальная модель?

**Вырезать:** детали `MeteredTracingImplementation` — одна строка «SPI decorator, не facade».

---

## 4. Span design policy для app teams (¾ стр.) — Ch. 6, 7

**Тезис книги:**
- Самая частая ошибка — span на каждый method/Redis call.
- Критерий span: *interesting?* (latency/failure/boundary) + *aggregable?* (group by).
- Альтернатива — timing attrs на root/parent (`auth.duration_ms`), не лишний child.

**Правила TraceOperations (выдать как policy):**

**DO**
- Agent для HTTP, JDBC, Kafka, gRPC
- `manual()` / typed builders: business operation, domain error, user/tenant context
- `exception.slug` — low-cardinality, greppable
- Semconv HTTP; route в `http.route`, не ID в span name
- Kafka batch: `ROOT + links`

**DON'T**
- Span на private helper / getter-setter
- Дублировать Agent span вручную
- `@Traced` + `@Observed` на одном method (ArchUnit)
- PII raw в attributes
- `DETACHED + links` (forbidden v1)

**Checklist (вставить в конспект как box):**

```
[ ] interesting?  [ ] aggregable?  [ ] не дублирует Agent?
[ ] semconv name  [ ] SpanSpecReason  [ ] vars в attributes, не в name
```

**Вопрос:** app teams добавляют **только business attrs**; platform владеет contracts и ArchUnit — согласны?

---

## 5. Async / Kafka topology (½ стр.) — Ch. 7

**Тезис книги:** stream processing — producer context в envelope; consumer — **span links**, не parent-child; long jobs — отдельные traces со links, не million-children.

**Наша модель (Slice 5B, GREEN):**

| Сценарий | Модель |
|----------|--------|
| Kafka batch consumer | `ROOT + links` |
| Parent-child через broker | Не canonical |
| `DETACHED + links` | Forbidden |
| Correlation | `X-Request-Id` ≠ `traceId` |

**Вопрос архитекторам:** `ROOT + links` — единственный approved паттерн для batch; OK?

**Для SRE:** fan-out/fan-in — tail sampling + optional span metrics в Collector; не тащить kube-state в app spans.

---

## 6. Pipeline reliability — что нужно от SRE (¾ стр.) — Ch. 16

**Тезис книги:** pipeline = `collect → normalize → enrich → reduce → route`; backpressure важнее transform; метамониторинг Collector обязателен.

**Порядок processors:** Filter → Transform → Sample → Export.

**Таблица alerts (готовая, без пояснений длиннее одной строки):**

| Сигнал | Значение | Действие |
|--------|----------|----------|
| `tail_sampling_sampling_trace_dropped_too_early` | Gateway не успевает буфер | scale Gateway / ↑ `num_traces` |
| `otelcol_processor_refused_spans` | memory limiter | capacity / `GOMEMLIMIT` |
| `droppedSpansOverflow` (platform) | BSP queue full | queue size / backend health |
| Broken traces в UI | inconsistent head sampling | trace_id affinity, consistent sampling |

**Platform-side:** `PlatformDropOldestExportSpanProcessor`; Actuator diagnostics (`implementation`, agent detection, `OTEL_BSP_*` hints).

**Вопрос SRE:** принять alert set + runbook pointers как минимум для prod rollout?

**Ссылки:** collector README, `collector-pipeline-production.md`.

**Вырезать:** OpAMP, Bindplane case study, multi-cloud FinOps.

---

## 7. Rollout и evidence (½ стр.) — Ch. 9

**Тезис книги:** instrumentation в SDLC; первая end-to-end transaction — быстрый win; progressive delivery.

**Наш контекст (честно):**

| Книга | У нас |
|-------|-------|
| Parallel old/new telemetry | N/A для v3 API (нет prod consumers) |
| First transaction | `TracingE2ETest` happy + error path |
| Patchwork forbidden | `SpanSpecReason`, ArchUnit |
| Prod rollout | Evidence gates E1–E7, canary, не big bang |

**Минимум до prod (5 пунктов — checklist):**
1. E2E trace в Tempo (ok + error)
2. Tail retains errors при aggressive head ratio
3. No duplicate HTTP spans (matrix test)
4. Kafka batch: links visible
5. Actuator diagnostics stable DTO

**Вопрос комитету:** v3 breaking без deprecate-first — осознанно; prod только после gates.

---

## 8. v3 vs R01 — почему ломаем API (½ стр.) — наш план, опора на книгу

Короткая таблица «было → книжный язык → стало»:

| Было (R01) | Книга | v3 |
|------------|-------|-----|
| Interface defaults на facade | Скрытая магия SDK | No behavioral defaults |
| `@Primary` partial `MeteredPlatformTracing` | Два path = два truth | Decorator на `TracingImplementation` |
| Micrometer + Agent без boundary | Layered, not duplicated | ADR Option C |
| Ad-hoc `setAttribute` | Semconv + governance | Typed builders + `SpanSpecReason` |

**Не расписывать slices** — ссылка на [platform-tracing-refactoring-plan.md](./platform-tracing-refactoring-plan.md).

---

## 9. Открытые вопросы (¼ стр.)

| Тема | Статус |
|------|--------|
| OpenTelemetry Weaver / schema CI | Watchlist |
| OpAMP fleet config | Watchlist, Helm достаточно v1 |
| Span metrics при head sampling | Collector-side; следить OTEP |
| `TraceContextView.sampled()` | Deferred |
| Golden trace CI | Future |

---

## 10. Финальный чек-лист встречи (приложение)

```
Архитекторы
[ ] Platform library ≠ tracing backend
[ ] Agent-first + manual() exception path
[ ] Hybrid Micrometer boundary (ADR Accepted)
[ ] ROOT+links / no DETACHED+links
[ ] Bean singularity + single TracingImplementation
[ ] App teams: business attrs only

SRE
[ ] Two-tier Collector (agent → gateway tail_sampling)
[ ] Head=cost / tail=incidents — не баг platform
[ ] Alert set (dropped_too_early, refused_spans, overflow)
[ ] k8sattributes в Collector, не в app
[ ] Topology config только Helm/startup

Platform (вы)
[ ] Evidence gates E1–E7 перед prod
[ ] Slice 8: service team guide в doc-portal
```

---

## 11. Glossary (¼ стр., только если спросят)

Head sampling · Tail sampling · Span link · ROOT/CHILD/DETACHED · OTLP · BSP · Trace affinity · Consistent sampling

---

## 12. Порядок написания финального конспекта

| # | Раздел | Время | Приоритет |
|---|--------|-------|-----------|
| 1 | §0 вступление + диаграмма | 20 мин | Must |
| 2 | §2 границы Platform/SRE | 40 мин | Must — главный для SRE |
| 3 | §3 Micrometer boundary | 25 мин | Must — главный для архитекторов |
| 4 | §4 span policy | 20 мин | Must |
| 5 | §6 pipeline alerts | 25 мин | Must для SRE |
| 6 | §1, §5, §7, §8 | 40 мин | Should |
| 7 | §10 checklist | 10 мин | Must |

**Итого:** ~3 часа черновика.

---

## 13. Имя итогового файла

`docs/analysis/platform-tracing-architect-sre-alignment-brief.md`

Связанные документы:
- Анализ книги: [observability-engineering-book-insights-for-platform-tracing.md](./observability-engineering-book-insights-for-platform-tracing.md)
- Старый план (8–12 стр., committee tone): [platform-tracing-otel-book-alignment-memo-plan.md](./platform-tracing-otel-book-alignment-memo-plan.md)
- Evidence: [platform-tracing-post-slice-7-review-package.md](./platform-tracing-post-slice-7-review-package.md)

---

## 14. Что выкинуть из финального конспекта

- Главы 1–3 (философия, AI-era, history of observability)
- Ch. 8 core analysis loop / BubbleUp (не blocker для sign-off tracing architecture)
- Ch. 11+ SLO, datastore, ClickHouse, cost chapters
- Wide events / Honeycomb как целевой backend (упомянуть одной строкой расхождения)
- Все 14 Gradle modules
- Slice roadmap по номерам
