# План конспекта для согласования TraceOperations с SRE и архитекторами

> **Цель документа:** 8–12 страниц (или 30-мин презентация) — аргументированная позиция «почему наше решение соответствует OTel best practices», а не пересказ книги.  
> **Аудитория:** архитекторы (границы, ADR), SRE (pipeline, sampling, alerts), вы как owner platform library.  
> **Источник:** MD-главы 5–9 + приложение (ранние главы в MD почти отсутствуют — не включать).

---

## 0. One-pager в начале (обязательно)

Одна страница до деталей — то, что комитет читает первым.

| Блок | Содержание (3–5 bullet) |
|---|---|
| **Что мы строим** | Agent-first Java platform starter: Agent + extension + Collector + narrow `TraceOperations` API |
| **Что не строим** | Собственный tracing backend, замена OTel SDK, per-service tracing config |
| **Формула** | Auto-instrumentation default; `manual()` — exception path |
| **Разделение ответственности** | Platform = library + policy; SRE = Collector + capacity; App teams = business attrs only |
| **Статус** | Pre-production; v3 breaking redesign; evidence gates до prod |

**Диаграмма для one-pager** (одна, не больше):

```
App (Spring) → Agent/extension (head policy) → local/agent Collector → Gateway (tail sampling) → Tempo/Jaeger
                    ↑ TraceOperations (manual governance only)
```

---

## 1. Раздел «Зачем OTel и почему platform library» (Глава 5 — библиотеки)

**Тезис книги (2–3 предложения):**
- Проблемы prod — в библиотеках (HTTP, DB, Kafka), не в бизнес-IF.
- Нативное инструментирование в библиотеке лучше плагинов: один SDK → всё работает.
- API отдельно от SDK; no-op по умолчанию; semconv обязательны.

**Mapping на TraceOperations:**

| Книга | Наше решение | ADR/артефакт |
|---|---|---|
| Нативное инструментирование | Agent auto + platform starter | ADR-otel-direct-integration |
| API vs SDK | `platform-tracing-api` compileOnly OTel; SDK в Agent | module taxonomy |
| Не оборачивать OTel API | Governance facade, не replacement | v3 plan §3 |
| Один SDK на приложение | Bean singularity, один `TracingImplementation` | refactoring plan Slice 2 |
| Semconv | `CategoryContracts`, STRICT, typed builders | ADR-typed-span-api |

**Вопрос для архитекторов (1 слайд):**
> TraceOperations — platform library с нативным OTel, не tracing backend. Подтверждаем?

**Не включать:** теорию про Cocoa/SwiftUI, длинные примеры sequential DB (только как иллюстрация «trace показывает anti-pattern»).

---

## 2. Раздел «Границы ответственности» (Главы 5 + 8)

**Тезис книги:**
- Конфигурация телеметрии ≠ конфигурация приложения → Collector/SRE, не hardcode в app.
- Фильтрация/сampling лучше на ранней стадии pipeline.
- Head-only sampling опасен для errors; tail — для retention.

**Mapping:**

| Зона | Кто владеет | Что делает |
|---|---|---|
| **Head sampling** | SDK/Agent (`CompositeSampler`) | Cost guard: ratio, drop-paths, kill-switch, force header |
| **Tail sampling** | Collector Gateway | errors, slow, forced, high-priority |
| **Scrubbing** | SDK 1st line + Collector 2nd | PII |
| **Topology config** | Startup only (K8s/Helm) | exporter endpoint, queue size |
| **Runtime policy** | JMX/Actuator | ratio, drop-paths без рестарта |

**Ключевой аргумент для SRE (из книги + наш ADR):**
> Head не видит errors (решается до `span.end()`). Поэтому **head = экономия CPU**, **tail = сохранение инцидентов**. Это не баг — это контракт OTel.

**Ссылки:** `ADR-runtime-sampling-policy`, `ADR-collector-boundary`, `ADR-runtime-config-policy-vs-topology`.

**Вопрос для SRE:**
> Подтверждаем two-tier topology (Agent DaemonSet → Gateway tail_sampling) и trace affinity по `trace_id`?

---

## 3. Раздел «Инфраструктура и async» (Глава 6/7)

**Тезис книги:**
- Infra metrics без контекста к app — monitoring, не observability.
- Длинные async workflow — не одна trace, а linked sub-traces + correlation ID.
- Collector — место для k8sattributes, scrubbing, routing.

**Mapping:**

| Сценарий | Решение платформы |
|---|---|
| Kafka batch | `ROOT + links` (Slice 5B), не parent-child |
| Correlation | `X-Request-Id` ≠ `traceId` (ADR-request-id) |
| k8s metadata | Collector `k8sattributes` + `PlatformResourceProvider` |
| Fan-out/fan-in | Tail sampling + optional metrics в Collector |

**Вопрос для архитекторов:**
> `DETACHED + links` forbidden; `ROOT + links` — canonical для batch. Согласны?

**Для SRE:** не тащить kube-state в app spans; infra — отдельный monitoring path.

---

## 4. Раздел «Pipeline и надёжность» (Глава 8)

**Тезис книги (выжимка):**
- Local Collector → pool → gateway → specialized pools.
- Backpressure критичнее transform; метамониторинг Collector обязателен.
- Порядок: Filter → Transform → Sample → Export.
- Не включать sampling, пока нет реальной cost pressure.

**Mapping:**

| Книга | У нас |
|---|---|
| Drop при overflow | `PlatformDropOldestExportSpanProcessor` |
| Tail window / memory | Gateway YAML + `GOMEMLIMIT` + alerts |
| Health noise | SDK `drop-paths` + tail `drop-successful-infra-noise` |
| Dual-channel drift | `OTEL_BSP_*` hints в Actuator diagnostics |

**Таблица для SRE (готовая в конспекте):**

| Метрика/алерт | Что значит | Действие |
|---|---|---|
| `tail_sampling_sampling_trace_dropped_too_early` | Gateway не успевает | scale up / ↑ num_traces |
| `droppedSpansOverflow` (platform) | App export queue full | tune queue / check backend |
| `otelcol_processor_refused_spans` | Collector memory limiter | capacity tuning |

**Ссылки:** collector README, `collector-pipeline-production.md`, `MIGRATION.md`.

---

## 5. Раздел «Внедрение и rollout» (Глава 9)

**Тезис книги:**
- Три оси: глубина/ширина, код/сбор, централизация.
- «Не ломай алерты» — parallel old/new telemetry при миграции.
- Central platform team + auto-instrumentation = минимум работы для app teams.
- Первая end-to-end транзакция → быстрый win → масштабирование.

**Mapping (честно про наш контекст):**

| Книга | Наш pre-production контекст |
|---|---|
| Width adoption | Java/K8s fleet через starter + Agent |
| Parallel old/new | **N/A для v3 API** (нет prod consumers); **да для prod rollout** dashboards |
| First transaction | `TracingE2ETest.happy_path` + error tail policy |
| Knowledge base | doc-portal otel.mdx + **gap:** v3 service team guide (Slice 8) |
| Patchwork forbidden | `SpanSpecReason` governance, ArchUnit |

**Вопрос для комитета:**
> v3 — breaking без deprecate-first (осознанно). Prod rollout — через evidence gates E1–E7, не через shim.

**Ссылки:** `platform-tracing-evidence-before-committee.md`.

---

## 6. Раздел «v3 Architecture — что меняем и зачем» (ваш план, не книга)

Кратко для архитекторов — 1 раздел, привязка к книге:

| Проблема (R01) | Книжной язык | v3 fix |
|---|---|---|
| Decorator + interface defaults | «Сквозная функциональность требует жёсткого API» | No behavioral defaults |
| `@Primary` partial wrapper | «Один SDK, одна цепочка» | `TracingImplementation` + `BeanTopologyTest` |
| Micrometer duplicate spans | «Два path = два truth» | ADR Option C hybrid |
| Escape hatch dumping | «Semconv + governance» | `spanFromSpec` + mandatory reason |

**Формула v3** — повторить один раз, не расписывать все slices.

---

## 7. Раздел «Открытые вопросы / не в scope v1»

Честный блок — повышает доверие на встрече:

| Тема | Статус |
|---|---|
| OpAMP auto-sampling | Watchlist, не v1 |
| Golden trace CI comparison | Future (книга Ch.9) |
| Observability Engineering (Majors) | Рекомендация SRE, не blocker |
| AI/LLM tracing semconv | Out of scope |
| `TraceContextView.sampled()` | Deferred |

---

## 8. Приложения к конспекту

**A. Glossary (½ страницы)**  
Head/tail sampling, span link, ROOT/DETACHED, OTLP, BSP, trace affinity.

**B. Таблица ADR → решение (1 страница)**  
15–20 ADR одной строкой: что решили, для кого.

**C. Чек-лист согласования (для встречи)**

```
Архитекторы:
[ ] Hybrid Micrometer boundary (ADR Accepted)
[ ] v3 narrow facade + TracingImplementation SPI
[ ] ROOT+links / no DETACHED+links
[ ] Single creation boundary + bean singularity

SRE:
[ ] Two-tier Collector topology
[ ] Tail sampling policies + capacity formulas
[ ] Alert set (dropped_too_early, export overflow)
[ ] OTEL_BSP_* alignment runbook

Platform / App teams:
[ ] Auto-instrumentation default
[ ] manual() only when needed
[ ] spanFromSpec governance rules
```

**D. Внешние ссылки (из приложения книги)**  
Только 3: opentelemetry.io/specs, semconv registry, OTEP (для BOM bumps).

---

## 9. Формат и объём

| Формат | Объём | Для кого |
|---|---|---|
| **Executive summary** | 1 стр. | Architect committee |
| **Technical body** | 6–8 стр. | Architects + lead SRE |
| **SRE runbook pointers** | 2 стр. | SRE on-call |
| **Appendix ADR index** | 1–2 стр. | Все |

**Стиль:** таблицы + bullet; диаграммы — 2–3 (boundary, pipeline, adoption). Без пересказа историй eBay/Farfetch — только вывод в одну строку.

**Язык:** русский; OTel-термины на английском (span, sampler, Collector).

---

## 10. Порядок написания (если делать самому или поручить)

1. One-pager + диаграмма boundary (30 мин)
2. Раздел 6 (v3 + R01) — ваш главный аргумент (45 мин)
3. Раздел 2 (head/tail) — для SRE (30 мин)
4. Раздел 4 (pipeline alerts) — для SRE (30 мин)
5. Разделы 1, 3, 5 — mapping книга→платформа (1 ч)
6. Appendix C checklist (15 мин)

**Итого:** ~4 часа на черновик конспекта.

---

## 11. Что сознательно выкинуть из конспекта

- Пересказ глав 1–4 (нет нормального MD)
- OCR-битую «Глава 5 инструментирование приложений»
- Длинные case studies (GraphQL, Go startup)
- OpAMP, OTel Arrow, FinOps, AI observability
- Детали всех 14 Gradle modules
- Slice-by-slice roadmap (есть в refactoring plan; дать ссылку)

---

## 12. Предлагаемое имя файла

`docs/analysis/platform-tracing-otel-book-alignment-memo.md`

или для встречи: `docs/analysis/platform-tracing-architect-sre-alignment-brief.md`

---

Если нужно, могу по этому плану сразу написать черновик конспекта (разделы 0 + 2 + 6 — самые важные для первой встречи с архитекторами и SRE).
