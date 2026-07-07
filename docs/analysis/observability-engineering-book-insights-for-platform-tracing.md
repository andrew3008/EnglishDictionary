# Выжимка из *Observability Engineering* для PlatformTracing

> **Источник:** `E:\Книги\Observability\Observability Engineering.txt` (2-е издание O'Reilly, ~2025–2026; Charity Majors, Liz Fong-Jones, George Miranda, Austin Parker + guest chapters).  
> **Аудитория:** owner platform library, архитекторы, SRE.  
> **Цель:** не пересказ книги, а извлечь идеи, которые **подтверждают, уточняют или ставят под вопрос** текущее решение PlatformTracing v3.

---

## 1. Что это за книга и почему она релевантна

Книга — практическое руководство по observability для **software engineers**, а не только для ops/SRE. Центральный тезис: observability — это способность **понять любое состояние системы**, с которым вы ещё не сталкивались, через **контекстно-богатые structured events** (wide events), а не через три изолированных «столпа» (logs / metrics / traces).

Для PlatformTracing наиболее ценны главы:

| Глава | Тема | Прямая связь с PlatformTracing |
|-------|------|--------------------------------|
| **4** | Instrumentation basics, OTel, auto vs custom, sampling intro | Agent-first, `manual()` как exception path |
| **5–6** | Structured events, traces как «fancy logs + context», wide attributes | Semconv, typed builders, business attrs |
| **7** | OTel trace-first, context propagation, async/Kafka, span boundaries | ROOT+links, Micrometer boundary, Agent |
| **8** | Core analysis loop, качество телеметрии | Зачем semconv + governance, не «сырой OTel» |
| **9** | Observability-driven development, rollout | Evidence gates, progressive delivery |
| **15** | Head/tail sampling, consistent sampling, target-rate | Two-tier topology Agent → Gateway |
| **16** | Telemetry pipelines (Collector) | Collector boundary, enrich/reduce/route |

Ранние главы (1–3) дают философию (high cardinality, developer intent, production validation). Главы 11+ (SLO, datastore, FinOps, leadership) — полезны стратегически, но **не blocker** для tracing library.

---

## 2. Ключевые идеи книги (сжато)

### 2.1. Tracing — не магия, а structured events + context propagation

Книга демистифицирует tracing: span — это structured event с полями `trace_id`, `span_id`, `parent_id`, `timestamp`, `duration` + attributes. Distributed tracing = **передача ID через границы процессов** (W3C TraceContext, baggage).

**Практический вывод:** PlatformTracing не должен «прятать» OTel — он должен **управлять** тем, как developers добавляют контекст поверх auto-instrumentation Agent'а.

### 2.2. Auto-instrumentation default; custom — для business context

> «Use both. Automatic instrumentation provides the skeleton; custom instrumentation adds muscles.» (Ch. 4)

Auto-instrumentation (Agent, zero-code) покрывает HTTP/DB/Kafka/RPC. Custom нужен для:
- business workflows (checkout, registration);
- domain-specific errors (`exception.slug`);
- user/tenant context (`user.type`, `feature_flag.*`).

**Формула книги = формула PlatformTracing v3:**
```
Agent auto-instrumentation (default) + PlatformTracing manual() (exception path)
```

### 2.3. Не оборачивать всё в child spans

Ch. 6 (Morrell) и Ch. 7 предупреждают: **самая частая ошибка** — span на каждую функцию/Redis-вызов. Child spans хороши для waterfall одного запроса, но **плохо агрегируются** across requests. Альтернатива — timing attributes на root span (`auth.duration_ms`, `stats.postgres_query_count`).

**Для PlatformTracing:**
- Agent уже создаёт spans на HTTP/DB — **не дублировать** через `manual()` без причины;
- typed builders — для **meaningful units of work**, не для getter/setter;
- ArchUnit + `SpanSpecReason` governance — защита от span explosion.

### 2.4. Trace-first design (OTel)

OTel — **trace-first**: metrics и logs наследуют context из active span. Без propagation теряется главное преимущество OTel.

Два типа propagation (Ch. 7):
- **Interprocess** — HTTP headers, Kafka envelope, gRPC metadata;
- **Intraprocess** — scope/context внутри JVM (threads, reactive chains).

**Критерий «создавать span?»** (Table 7-1):
1. *Interesting?* — влияет на latency/failures?
2. *Aggregable?* — можно group by name/attributes?

| Operation | Span? |
|-----------|-------|
| HTTP handler, DB query, external API, MQ publish/consume | **Да** |
| Private helper, loop iteration, pure orchestration | **Нет** |
| Business saga / transaction | **Да** |

### 2.5. Async / streaming — не одна trace, а links + correlation

Для Kafka/stream processing (Ch. 7):
- Producer span → context в message envelope;
- Consumer span → **span links**, не parent-child;
- Correlation ID через весь pipeline;
- Метрики + exemplars для fleet-level alerting; spans — для deep dive.

**Прямое попадание в ADR PlatformTracing:**
- `ROOT + links` — canonical для batch (Slice 5B);
- `DETACHED + links` — forbidden;
- `X-Request-Id` ≠ `traceId`.

Для long-running jobs (ETL, nightly): **отдельные traces со links**, не million-children trace. Обновлять span in-place — anti-pattern (большинство backends не поддерживают).

### 2.6. Layered telemetry, не duplication

Три вопроса при выборе signal (Ch. 7):
1. Нужна causality и full-request context? → **traces**
2. Нужны cheap alerting и long-term storage? → **metrics**
3. Rare events / audit? → **logs/events**

Паттерн HTTP: span + histogram metric. При head sampling traces — histograms дают RED alerting.

**Важно:** metrics from spans **невозможны/неточны** при naive head sampling (Ch. 7, Ch. 15). Collector-side span metrics или probabilistic head sampling — experimental mitigation.

**Для Micrometer boundary (ADR Option C):** Observation — metrics path; OTel Agent — trace path. **Не два truth sources** для одного HTTP span.

### 2.7. Semantic conventions — не optional

Ch. 7 + Ch. 16 (ontologies):
- Имена: dot notation, snake_case; **не** класть IDs в span name (`GET /users/{id}`, не `GET /users/349827487124`);
- Attributes для переменных компонентов;
- Org-wide schema (OpenTelemetry Weaver) — emerging practice;
- PII: hash + SDK/Collector processors + allowlist keys.

**PlatformTracing mapping:** `CategoryContracts`, STRICT mode, typed builders, `SpanSpec` governance.

### 2.8. Head vs tail sampling — контракт, не баг

Ch. 4, 15:

| | Head (at source) | Tail (after emit) |
|--|------------------|-------------------|
| **Когда решение** | At span start | After trace complete |
| **Знает error/latency?** | **Нет** | **Да** |
| **Цель** | Resource efficiency, cost guard | Fidelity, incident retention |
| **Где** | SDK/Agent sampler | Collector buffer + policy |

Head sampling **oblivious** к downstream errors — это не недостаток реализации, а **фундаментальное ограничение**. Решение: **two-tier** — head = cost, tail = errors/slow/forced.

**Consistent sampling:** один `trace_id` → все spans kept или dropped together (Ch. 15). Иначе — broken traces.

Дополнительно:
- **Target-rate sampling** — динамический rate по traffic volume;
- **Per-key sampling** — errors/slow отдельный budget;
- **Collector-side buffered tail** — объединяет преимущества head propagation + tail heuristics.

### 2.9. Telemetry pipeline — не app concern

Ch. 4, 16: processing decisions (filter, scrub, sample, route) **defer until pipeline**, не hardcode в app.

Pipeline stages: `collect → normalize → enrich → reduce → route` + resilience + control plane.

Architecture:
```
App/Agent (edge) → Gateway (reduce, tail sample) → Backend (Tempo/Jaeger)
                      ↑ k8sattributes, PII scrub, routing
```

**Разделение ответственности PlatformTracing:**

| Зона | Владелец |
|------|----------|
| Instrumentation + semconv | Platform library + Agent |
| Head policy (ratio, drop-paths) | Agent/extension + runtime JMX/Actuator |
| Tail sampling, enrich, scrub | SRE / Collector Gateway |
| Topology (exporter endpoint) | K8s/Helm, startup config |

### 2.10. Observability-driven development

Ch. 9: instrumentation — часть SDLC, как тесты. При каждом PR: *«How will I know if this change works in production?»*

Практики:
- progressive delivery + feature flags;
- bundle instrumentation with features;
- shorten feedback loop (on-call for merger, canary);
- **не** заменять debugger — observability = telescope, debugger = microscope.

Для v3 pre-production: evidence gates E1–E7 вместо «parallel old/new telemetry» (нет prod consumers).

### 2.11. Качество телеметрии критично для анализа

Ch. 8: inconsistent attribute names, missing descriptions и duplicate signals ломают расследование — investigator тратит время на угадывание canonical field, а не на поиск root cause.

**Core analysis loop** (Ch. 8): hypothesis → query → isolate anomaly → find dimensions that differ from baseline → iterate. Требует **high-cardinality wide events** — metrics alone недостаточны. Semconv + governance снижают friction при core analysis loop.

---

## 3. Mapping: книга → PlatformTracing v3

### 3.1. Архитектурная формула (подтверждена книгой)

```
App (Spring)
  → OTel Java Agent + platform extension (head policy, semconv)
  → local/agent Collector
  → Gateway Collector (tail sampling, k8sattributes, scrubbing)
  → Tempo/Jaeger
       ↑
  PlatformTracing manual() — governance facade, не replacement OTel SDK
```

| Тезис книги | Решение PlatformTracing | Статус |
|-------------|-------------------------|--------|
| Vendor-neutral OTel, API ≠ SDK | `platform-tracing-api` compileOnly; SDK в Agent | ✅ ADR-otel-direct-integration |
| Auto default, custom exception | Agent-first; narrow `manual()` | ✅ v3 plan |
| Не wrapping OTel API | Governance facade + `TracingImplementation` | ✅ v3 plan |
| Один SDK / одна цепочка | Bean singularity, no partial decorators (R01) | 🔄 Slice 2+ |
| Semconv обязательны | `CategoryContracts`, typed builders | ✅ ADR-typed-span-api |
| Head = cost, tail = incidents | Two-tier Collector | ✅ ADR-runtime-sampling-policy |
| Kafka = links not parent-child | `ROOT + links` canonical | ✅ Slice 5B |
| Pipeline owns config | Collector/SRE, not per-service app config | ✅ ADR-collector-boundary |
| Micrometer ≠ duplicate traces | Hybrid Option C | ✅ ADR Accepted 2026-07-06 |

### 3.2. Что книга усиливает в аргументации для комитета

1. **Platform library, не tracing backend** — книга отдельно выделяет instrumentation libraries vs pipeline vs datastore. PlatformTracing = **library + policy**, не Tempo/Jaeger replacement.

2. **Head sampling не «чинится» в SDK** — SRE должны владеть tail policies; head — cost guard. Это **контракт OTel**, не баг PlatformTracing.

3. **Span explosion — org-wide anti-pattern** — обоснование для governance (`SpanSpecReason`, ArchUnit `NO_TRACED_AND_OBSERVED_ON_SAME_METHOD`).

4. **Instrumentation = product decision** (Ch. 1–2) — platform team владеет conventions; app teams добавляют **только business attrs**.

5. **Centralized helper libraries** (Ch. 9) — typed builders / constants вместо ad-hoc `span.setAttribute()` в каждом сервисе.

### 3.3. Где книга расходится с нашим контекстом (честно)

| Тема книги | Наш контекст | Комментарий |
|------------|--------------|-------------|
| Wide events / Honeycomb-style analysis | Tempo/Jaeger trace backend | Мы оптимизируем под **distributed tracing**, не event store. Wide attrs — на root span, не отдельный event DB |
| «Start with logs OR traces, not both» (Ch. 2) | Unified OTel signals | Для Java/K8s fleet traces — правильный primary signal |
| Schema-driven telemetry (Weaver) | Планируется | Watchlist; сейчас — `CategoryContracts` + ArchUnit |
| OpAMP fleet management (Ch. 16) | Watchlist | Не v1; Helm/GitOps достаточно |
| eBPF/OBI as gap filler (Ch. 7) | Agent-first Java | Дополнение для uninstrumented legacy, не замена |

---

## 4. Рекомендации для PlatformTracing (actionable)

### 4.1. Instrumentation policy (для app teams + doc-portal)

**DO (по книге + наши ADR):**
- Полагаться на Agent для HTTP, JDBC, Kafka, gRPC;
- `manual()` / typed builders — для business operations, domain errors, user context;
- Добавлять `exception.slug` (low-cardinality, greppable) на predictable errors;
- Использовать semconv HTTP attributes; парсить User-Agent в structured fields, не regex at query time;
- Для Kafka batch: `ROOT + links`, correlation ID в message attrs;
- Feature flags и deployment version на spans (Table 6-5, 6-6).

**DON'T:**
- Span на каждый private method / Redis call;
- Дублировать Agent spans через Micrometer tracing **и** manual HTTP spans (R01 class);
- Класть PII raw в attributes — hash + Collector scrub;
- Unbounded cardinality в metric labels (user_id как metric tag);
- Runtime topology changes из app code (exporter endpoint в Java config).

### 4.2. Span design checklist (из Ch. 6–7)

Перед созданием custom span:

```
[ ] Interesting? (latency, failure, boundary crossing)
[ ] Aggregable? (group by route, operation, outcome)
[ ] Нельзя выразить attribute на parent span?
[ ] Не дублирует Agent auto-instrumentation?
[ ] Имя стабильное (semconv), переменные — в attributes?
[ ] Есть SpanSpecReason / category contract?
```

### 4.3. Sampling runbook pointers (для SRE, из Ch. 15–16)

| Сигнал | Действие |
|--------|----------|
| `tail_sampling_sampling_trace_dropped_too_early` | Scale Gateway / ↑ `num_traces` |
| Broken traces (parent dropped, child kept) | Проверить consistent head sampling + trace_id affinity |
| Error traces missing | Tail policy, не head ratio increase |
| Cost spike | Head ratio / drop-paths; **не** отключать tail error policy |
| `otelcol_processor_refused_spans` | Collector memory limiter / capacity |
| Platform `droppedSpansOverflow` | BSP queue tuning, backend health |

**Порядок pipeline (Ch. 16):** Filter → Transform → Sample → Export. Backpressure + metamonitoring Collector обязательны.

### 4.4. Evidence / rollout (из Ch. 2, 9)

До prod:
1. First end-to-end transaction visible в Tempo (happy path + error path);
2. Tail sampling retains errors при aggressive head ratio;
3. No duplicate HTTP spans (Agent + Micrometer matrix);
4. Kafka batch scenario: links visible, не orphan spans;
5. Actuator diagnostics: implementation, agent detection, sampling hints.

Progressive prod rollout: feature flags, canary, **не** «big bang» без parallel dashboards для SRE.

---

## 5. Цитаты-якоря (для презентаций)

> «Head sampling is almost entirely skewed toward saving on cost at the expense of debuggability.» — Ch. 4

> «Tail-based sampling examines the entirety of the transactional record before deciding to keep.» — Ch. 4

> «Tracing is fancy logs combined with context propagation.» — Ch. 5

> «Wrapping absolutely everything in its own span is the most common failure mode.» — Ch. 6 (Morrell)

> «Spans should model meaningful units of work… If your trace has a hundred two-millisecond spans, you probably have too many.» — Ch. 7

> «Generating accurate metrics from spans is difficult if you apply head sampling.» — Ch. 7

> «Instead of every application determining where telemetry should go, a telemetry pipeline should become the backbone.» — Ch. 16

> «Instrumentation in production is the living spec.» — Ch. 2

---

## 6. Связь с существующими артефактами PlatformTracing

| Артефакт | Как использовать вместе с этим документом |
|----------|------------------------------------------|
| [platform-tracing-otel-book-alignment-memo-plan.md](./platform-tracing-otel-book-alignment-memo-plan.md) | Executive brief для комитета (8–12 стр.) |
| [platform-tracing-otel-book-alignment-outline.md](./platform-tracing-otel-book-alignment-outline.md) | Структура презентации |
| [ADR-platform-tracing-micrometer-observation-boundary.md](../decisions/ADR-platform-tracing-micrometer-observation-boundary.md) | Hybrid boundary — подтверждён Ch. 7 layered telemetry |
| [ADR-otel-direct-integration.md](../decisions/ADR-otel-direct-integration.md) | Agent-first — подтверждён Ch. 4, 7 |
| [platform-tracing-refactoring-plan.md](./platform-tracing-refactoring-plan.md) | Slice roadmap |
| [R01.md](../known-issues/R01.md) | Decorator anti-pattern — противоречит Ch. 7 «one SDK, one chain» |

---

## 7. Открытые вопросы (книга не закрывает)

| Вопрос | Наша позиция |
|--------|--------------|
| Golden trace CI comparison (Ch. 9) | Future; не blocker v3 |
| OpenTelemetry Weaver / schema CI | Watchlist |
| Span metrics при head sampling | Collector-side aggregation; мониторить OTel probabilistic sampling OTEP |
| `TraceContextView.sampled()` | Deferred |
| OpAMP dynamic sampling | Watchlist; JMX/Actuator для runtime ratio |

---

## 8. Резюме в одном абзаце

Книга *Observability Engineering* подтверждает архитектуру PlatformTracing v3: **OTel Agent как default instrumentation**, узкий governance facade для custom spans, **semantic conventions как контракт**, **head/tail sampling как двухуровневый pipeline в Collector**, и **span links для async/Kafka**. Главные усиления для нашего решения: жёсткая политика против span explosion, layered telemetry (без дублирования Micrometer traces), defer processing в Collector, и трактовка instrumentation как product/platform concern — не per-service самодеятельность. Расхождение: книга ориентирована на wide-event analysis (Honeycomb); мы — на distributed tracing backend, но принципы attributes, cardinality и sampling полностью применимы.

---

*Документ подготовлен на основе полного TXT-источника. Для normative OTel details — opentelemetry.io/specs и semconv registry, не этот конспект.*
