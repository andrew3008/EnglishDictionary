# PlatformTracing — финальное архитектурное ревью плана v3.3.1 (expensive final pass)

> Ревьюируемый артефакт: `docs/analysis/platform-tracing-refactoring-plan.md` (v3.3.1).
> Входные данные: план v3.3.1, investigation-документ, исходники (`PlatformTracing.java`, `MeteredPlatformTracing.java`, `TracingMetricsAutoConfiguration.java`, builder package), шесть предыдущих model-проходов (Deep Research, Gemini, Sonnet, GPT-5.4, Kimi, Nemotron) из `Research_2/Analyzes`.
> Режим: adversarial final pass. Без backward compatibility, без deprecate-first, без косметики.

---

# 1. Final Verdict

## **ACCEPT WITH REQUIRED PATCHES**

Архитектурное направление верное и достаточно смелое: это настоящий pre-production redesign, а не патч. R01 диагностирован как структурный дефект (behavioral defaults + частичный decorator + `@Primary` wiring), и план лечит болезнь, а не симптом. Однако план в текущем виде — сильный redesign-draft с несколькими незакрытыми дырами в enforcement, две из которых относятся к тому же классу дефектов, что и сам R01. Патчи ниже обязательны **до Slice 1A/2**, но не блокируют Slice 0A/0B.

Консенсус шести предыдущих проходов: все шесть дали ACCEPT WITH CHANGES/CRITICAL FIXES, ни один не дал REJECT, ни один не предложил deprecate-first или косметику. Расхождения только в приоритизации блокеров. Этот финальный проход адьюдицирует их ниже.

---

# 2. Executive Decision Memo

**Для архитектурного комитета.**

План v3.3.1 предлагает: узкий фасад `PlatformTracing = traceContext() + manual()`, ноль behavioral default methods, полностью абстрактный внутренний SPI `TracingImplementation` как единственную точку создания span'ов, декорирование/метрики только на этой границе, строгую топологию (`CHILD/ROOT/DETACHED` + links matrix), типизированные атрибуты (`SpanAttributeValue` whitelist, без `attribute(String, Object)`), governed escape hatch (`spanFromSpec` + mandatory `SpanSpecReason`), внутренний `TracingState` вместо boolean, STRICT-валидацию как production default.

Это правильный ответ на R01. Java default-dispatch делает частичные декораторы структурно небезопасными: `MeteredPlatformTracing` переопределяет 2-арговый `startSpan`, а `ROOT`/`DETACHED`/links молча деградируют через default-методы интерфейса. Патч декоратора не устраняет класс дефекта — устранение behavioral defaults и перенос декорирования на абстрактную границу устраняет.

**Но R01 имеет два слоя, а план закрывает только один.** Первый слой — Java-семантика (default-dispatch) — закрыт. Второй слой — Spring bean wiring (`@Primary` в `TracingMetricsAutoConfiguration.java:106-112` подсунул дефектный бин каждому Micrometer-приложению) — в плане не закрыт: нет гейта «ровно один активный `TracingImplementation`, и при Micrometer на classpath он обязан быть обёрнут в `MeteredTracingImplementation`». Проверка bean-графа отложена до Slice 7 — то есть архитектурный контракт проверяется позже, чем строится. Это повторение исходной ошибки.

**Второй пропуск — Micrometer Observation API.** Spring Boot 3 строит observability вокруг `ObservationRegistry`. Платформа, идущая напрямую в OTel SDK параллельно со Spring-автоинструментацией, рискует получить два независимых источника truth для одного trace lifecycle — структурно тот же класс дефекта, что R01 («два пути, которые молча расходятся»). Плану нужен ADR с явным решением, не реализация.

Решение: **одобрить redesign-направление; Slice 0A/0B стартуют немедленно; Slice 1A — после ADR по Observation API и sign-off чеклиста §9; Slice 2 — только с bean-singularity и single-boundary гейтами внутри среза.**

---

# 3. What the plan gets right

1. **Правильный диагноз R01** — структурный, не точечный; план прямо отклоняет косметический патч (§1, §11).
2. **Узкий фасад без behavioral defaults** — устраняет весь класс decorator-дефектов, а не один инстанс.
3. **`TracingImplementation.startSpan(SpanSpec)` как единственная точка создания** — правильная теорема; metering, governance и exception policy становятся необходимо-непроходимыми (нужно только доказать её раньше, см. §5).
4. **Evidence-first слайсы 0A/0B** — baseline GREEN + известный дефект RED (`knownDefectTest`) до любого breaking change. Это дисциплина, которой обычно не хватает.
5. **Топологическая матрица** (`ROOT+links` allowed, `DETACHED+links` forbidden, `CHILD+links` forbidden by default) + order-independent final-state validation — семантически согласовано с OTel-моделью parent+links и проверяемо.
6. **`SpanAttributeValue` whitelist + типизированные overloads + запрет `attribute(String, Object)`** — реальная защита от PII/high-cardinality/Object-свалки, а не соглашение.
7. **Governance `spanFromSpec`**: mandatory `SpanSpecReason` без catch-all значений, `TEMPORARY_WORKAROUND` требует `reference`, ArchUnit в платформенном репо, diagnostics by reason.
8. **`TracingState` вместо boolean + mapped diagnostics DTO** — supportability спроектирована, а не приклеена; правило «не экспонировать internal state напрямую» (R20) уже в плане.
9. **Полное удаление `SpanRelation`, `Facade*` builders, post-start `addLink`** — правильное использование pre-production окна; никакой сентиментальности к плохим абстракциям.
10. **Rejected-names таблица + Slice 1A blacklist** — редко встречающаяся защита от возврата стилистического мусора (`rawSpan`, `advanced`, `escapeHatch`, `businessSpan`).

---

# 4. What is still structurally dangerous

Приоритезировано. Первые три — того же класса, что R01.

**D1 (Critical) — Spring bean wiring слой R01 не закрыт.**
R01 родился из `@Primary` + частичный декоратор. План чинит Java-слой, но ничто не мешает будущему `@Primary`/`@Order` бину `TracingImplementation` обойти `MeteredTracingImplementation` целиком. `ApplicationContextRunner` matrix стоит в Slice 7 — после Slice 2 и 6. Bean-singularity theorem-test обязан жить в Slice 2.

**D2 (Critical) — «single creation boundary» заявлена, но доказывается слишком поздно.**
Инвариант «все manual-пути создают span только через `TracingImplementation.startSpan(SpanSpec)`» — фундамент для metering (R07/R08) и governance (R03). В плане enforcement размазан по Slices 4/6. Если Slice 3A-3C построят builders до доказательства инварианта, они построены на недоказанном фундаменте.

**D3 (High) — Micrometer Observation API: решения нет вообще.**
Ни секции, ни ADR-topic в Slice 8. Три из шести проходов (Sonnet, Gemini, Deep Research) независимо пометили это как блокер перед 1A. Нужно одно из двух явных решений: (a) платформа отключает Spring tracing-handlers и берёт полный контроль (документированный operational breaking decision), или (b) `TracingImplementation` — мост в Observation. Без ADR это будет первый вопрос на архитектурном ревью — и заслуженно.

**D4 (High) — `spanFromSpec` governance вне платформенного репо — фикция на день релиза.**
План честно признаёт: «downstream enforcement can be rolled out separately». Это значит, что в день релиза `spanFromSpec` — неограниченный escape hatch для потребителей; `.reason(PLATFORM_EDGE_CASE).reference("N/A")` пройдёт. Нужен runtime-слой: STRICT валидирует формат `reference` (ticket-pattern) для `TEMPORARY_WORKAROUND`; diagnostics считает usage by reason у потребителей, не только в репо платформы.

**D5 (Medium) — Diagnostics DTO — бумажная граница при 1:1 shape.**
`TracingDiagnosticsView {mode, reason, details}` изоморфен `TracingState {mode, reason, details}`. Без правила семантической стабильности (unknown/future `TracingMode` → `"UNKNOWN"` в DTO; новые режимы не появляются в Actuator без ревью контракта) граница декоративная, и Composer реализует passthrough-геттеры.

**D6 (Medium) — ArchUnit-правило не запрещает abstract skeleton classes.**
Формулировка бьёт по «default methods and behavioral static helpers on interfaces». `abstract class BaseTracingImplementation implements TracingImplementation` с частичными телами методов воссоздаёт partial-delegation риск легально. Дыра точечная, закрывается одним правилом.

**D7 (Medium) — недоспецифицированный контракт `SpanSpecBuilder` при повторных вызовах.**
`.child().root().detached()` — что происходит? Last-wins или fail-fast? То же для повторных `reason(...)`/`reference(...)` и дубликатов attribute-ключей. «Final-state validation» без правила формирования final state — двусмысленность, которую Composer разрешит молча и недетерминированно.

**D8 (Low-Medium) — верификация срезов слишком грубая.**
`:platform-tracing-core:test GREEN` для Slice 2 не доказывает routing-теорему. Митигации должны биться на именованные test-группы (`*RoutingTest`, `*BeanTopologyTest`, `*DiagnosticsBoundaryTest`), иначе зелёный бар ничего не значит.

**D9 (Low) — grep-гейт 1B слишком узкий.**
`inSpan|startSpan|addLink` не покрывает `currentTraceId/currentSpanId`, typed shortcuts (`startHttpServer`...), builder factories (`internalSpan()`, `kafkaConsumerSpan()`...), `SpanRelation`, `recordException`. Инвентарь Kimi-прохода даёт полный список — его надо вставить в pre-requisite 1B.

**D10 (Low) — CHILD+links opt-in builders не названы.**
Политика есть, а список конкретных opt-in builders — нет, при том что Kafka batch — флагманский пример links. Надо явно указать: Kafka consumer batch работает как ROOT+links (по плану) или как CHILD+links opt-in — одно из двух, поимённо.

---

# 5. Required plan patches

Обязательные перед указанными срезами; все — Markdown-патчи плана, не реализация.

| # | Патч | Блокирует |
|---|------|-----------|
| P1 | **Slice 2 hard gate — bean singularity:** обязательный `ApplicationContextRunner`-тест в Срезе 2 (не 7): ровно один активный `TracingImplementation`; при Micrometer на classpath он обёрнут в `MeteredTracingImplementation`; конкурирующий `@Primary` бин фейлит тест. | Slice 2 |
| P2 | **Slice 2 hard gate — single creation boundary:** ArchUnit + named test: builders/`SpecifiedSpan`/no-op/metered создают span'ы только через `TracingImplementation.startSpan(SpanSpec)`; прямой `Tracer` доступ вне SPI запрещён. Перенести из Slice 4/6 в Slice 2 как blocker для 3A+. | Slice 2, транзитивно 3A-6 |
| P3 | **ADR «Relationship to Micrometer Observation API»** до Slice 1A: явное решение (отключаем Spring tracing-handlers и документируем / либо мост в Observation), плюс coexistence-тест в Slice 7 (один HTTP-запрос ≠ два несогласованных root-span'а). | Slice 1A |
| P4 | **ArchUnit: запрет abstract skeleton classes в SPI-слое:** `abstract class ... implements TracingImplementation` с телами методов запрещён; только полные конкретные реализации. | Slice 1A/2 |
| P5 | **Контракт `SpanSpecBuilder` при повторных вызовах:** выбрать и зафиксировать — повторные topology-вызовы fail-fast немедленно (рекомендую: строже, читаемее в diff'ах) ЛИБО last-wins с валидацией final state; аналогично для `reason`/`reference`/duplicate attribute keys. Обязательный тест `.child().root()`. | Slice 1A |
| P6 | **`spanFromSpec` downstream runtime enforcement:** STRICT валидирует формат `reference` при `TEMPORARY_WORKAROUND` (regex ticket-pattern); diagnostics считает spec-usage потребителей; убрать «optional/recommended» как финальную формулировку — build-enforcement остаётся отдельным rollout'ом, но runtime-слой входит в Slice 4/5. | Slice 4/5, текст — сейчас |
| P7 | **Diagnostics DTO semantic stability rule:** unknown/future `TracingMode` → `"UNKNOWN"` в DTO; новое внутреннее mode-значение не появляется в Actuator без явного ревью контракта; snapshot-тест JSON-контракта. | Slice 7, правило — до Slice 2 |
| P8 | **Расширить grep-гейт 1B** до полного removed-symbol набора: `currentTraceId`, `currentSpanId`, `startRootSpan`, `startDetachedSpan`, `startChildSpan`, `startSpanWithLinks`, `addLink`, `inSpan`, `startHttpServer|startHttpClient|startDb|startRpcServer|startRpcClient|startInternal`, `internalSpan|httpServerSpan|httpClientSpan|databaseSpan|rpcServerSpan|rpcClientSpan|kafkaProducerSpan|kafkaConsumerSpan`, `SpanRelation`, `recordException` (public path). Отчёт прикрепляется к PR и проверяется вручную. | Slice 1B |
| P9 | **Именованные test-suites в таблице верификации:** каждой митигации риска — минимум одна именованная test-группа (`TracingImplementationRoutingTest`, `BeanTopologyTest`, `DiagnosticsBoundaryTest`, `SpanSpecBuilderFinalStateTest`, `MeteredTopologyMatrixTest`). | Slice 2+ |
| P10 | **Назвать CHILD+links opt-in builders поимённо** (или явно зафиксировать, что Kafka batch = ROOT+links и opt-in список пуст в v1). Удалить или пометить «future extension» примеры `redis()`/`s3()` в §3.4. | Slice 1A |
| P11 | **Уточнить exactly-once policy для nested LIFO:** same-Throwable, propagating через вложенные span'ы — suppress per-span, не per-call-stack; distinct Throwables на одном span — первый записан, последующие как span events (не потеря root cause). | Slice 4 |
| P12 | **STRICT fail-safe default:** незнакомый профиль (`staging` и т.п.) = STRICT; WARN только по явному whitelist локальных/dev-профилей. Одно предложение в §3.10. | Slice 3A/7 |

Не требуется: пересмотр public API shape, пересмотр slice-декомпозиции, компромиссы по backward compatibility.

---

# 6. Public API final judgment

**Форма правильная. Принять как есть (с P5/P10).**

- `traceContext()` + `manual()` — минимальный, честный фасад; auto-instrumentation как режим, а не метод — правильное отсутствие API.
- `operation / transport / spanFromSpec` — трёхуровневая лестница с правильным градиентом трения: лёгкий путь самый дешёвый, escape hatch самый дорогой. Это и есть misuse resistance by design.
- Грамматика `.child()/.root()/.detached()/.linkedTo(...)` на builder + `SpanOptions` как value model — верное разделение ergonomics/ownership; история с отклонением `.options(SpanOptions.root())` в v3.2 была правильным решением.
- Типизированные attribute overloads вместо `Object` — правильные; overload-ловушка `int→long` widening реальна, но приемлема (документировать, не редизайнить).
- `SpecifiedSpan` как immutable terminal surface, `SpanHandle` как минимальный AutoCloseable — верно.
- Замечание не в наименованиях, а в ролевой двусмысленности §4: mapping `startSpan(name, category)` на «`operation(...)` **или** `spanFromSpec(...)`» подаёт governed-путь как равноправный. Разделить на «primary replacement» и «governed exceptional replacement» (входит в редакцию при P6).

**Вердикт: это production-grade platform API, не косметика. 9/10 по форме.**

# 7. Internal architecture final judgment

**Направление правильное; граница недодоказана.**

- `TracingImplementation` (4 метода, fully abstract) — правильный минимальный SPI; интерфейс без defaults заставляет компилятор требовать полную реализацию — это устраняет Java-слой R01.
- `MeteredTracingImplementation` на SPI-границе + `DefaultFacade(Metered(Default))` — правильная точка декорирования.
- `TracingState` + mapped DTO — правильно; при условии P7 граница станет реальной, а не бумажной.
- Дыры: D1 (bean wiring), D2 (boundary proof timing), D6 (abstract skeletons) — все закрываются P1/P2/P4 без изменения архитектуры.
- Alternative-анализ (Gemini): чистый Observation-first вариант проигрывает по governance (5.5/10), мост-вариант (8.2) и прямой OTel SPI (8.0) сопоставимы. Выбор плана допустим — но только как **задокументированное решение** (P3), а не как умолчание.

**Вердикт: принять при P1+P2+P4; без них Slice 2 строит недоказанную границу.**

# 8. Slice strategy final judgment

**Последовательность рациональна, две точки enforcement стоят слишком поздно.**

- 0A/0B (evidence) → 1A (skeleton) → 1B (atomic cutover) → 2 (boundary) → 3 (builders) → 4 (execution) → 5 (topology/links) → 6 (metering) → 7 (wiring/diagnostics) → 8 (docs) — правильный порядок с правильной идеей «доказательства до разрушения».
- Ошибка №1: bean-graph инварианты в Slice 7, а не 2 (P1). Slice 6 иначе тестирует декоратор в вакууме — зелёные тесты, дырявая архитектура.
- Ошибка №2: single-boundary в Slice 4/6, а не 2 (P2).
- 1B — самый опасный срез (атомарный multi-module cutover); grep-гейт должен быть полным (P8), отчёт — проверен человеком до, а не после.
- 0B риск: `FilteredClassLoader` неправильно исключает Micrometer → false-negative RED-тесты. Добавить sanity-assert «Micrometer действительно на classpath» в RED-фикстуру.

**Вердикт: принять с перестановкой enforcement-гейтов (P1/P2); слайсы не перекраивать.**

# 9. Hard vetoes

Подтверждаю все существующие вето плана и добавляю их к финальному контракту. Отклоняется без обсуждения любой PR/предложение, которое:

1. Предлагает deprecate-first migration или compatibility shim для `SpanRelation`/старого фасада.
2. Патчит `MeteredPlatformTracing` как «durable fix».
3. Вводит `attribute(String, Object)` или map-based attribute injection.
4. Возвращает post-start `addLink(...)`.
5. Возвращает `SpanRelation` в любом виде.
6. Вводит public top-level `execute()` / `TracingExecutor`.
7. Ставит `http()/db()/rpc()/kafka()` на `PlatformTracing` top-level.
8. Вводит `rawSpan`/`advanced`/`escapeHatch`/`customSpan` или иные имена из §3.11.
9. Добавляет `OTHER`/`UNKNOWN`/`CUSTOM`/`MISC` в `SpanSpecReason`.
10. Экспонирует OTel SDK-типы в public API или Actuator-контракте.
11. **Новое:** вводит abstract skeleton class с телами методов в SPI-слое (P4).
12. **Новое:** регистрирует второй `@Primary`/`@Order` бин `TracingImplementation`, обходящий metered-цепочку (P1).

---

# Оценочная рубрика (1–10)

| Критерий | Оценка | Обоснование |
|---|---:|---|
| API clarity для application-разработчиков | **9** | Читаемая грамматика, трёхуровневая лестница manual-путей, примеры §3.12 согласованы с интерфейсами |
| Misuse resistance | **8** | Whitelist-атрибуты, mandatory reason, топология fail-fast; −2 за downstream `spanFromSpec` до P6 |
| OTel semantic correctness | **8** | Parent+links модель, pre-start links, semconv-builders; −2 за нерешённый Observation boundary (P3) |
| Decorator/metering safety | **7** | Java-слой закрыт; Spring wiring слой — нет до P1; abstract skeleton дыра до P4 |
| Validation strategy | **8** | STRICT default + final-state validation; −2 за незафиксированный fail-mode неизвестных профилей (P12) и duplicate-call contract (P5) |
| Diagnostics/supportability | **8** | TracingState + mapped DTO — сильная заявка; −2 за бумажную границу до P7 |
| Spring Boot autoconfiguration readiness | **6** | ApplicationContextRunner/FilteredClassLoader есть, но стоят поздно; Observation API не решён |
| Implementation slice safety | **7** | Evidence-first отлично; −3 за поздние enforcement-гейты (P1/P2) и узкий grep 1B (P8) |
| Test strategy strength | **7** | Правильные категории тестов; −3 за грубые верификационные команды без именованных suites (P9) |
| Long-term maintainability | **9** | Узкий фасад + SPI + ArchUnit-гейты радикально дешевле в сопровождении, чем 30 default-методов |
| Codebase cleanup impact | **9** | Удаление 9 Facade*-классов, SpanRelation, typed shortcuts, builder factories — настоящая чистка |
| Architectural courage / non-cosmetic strength | **10** | План прямо отклоняет патч, использует pre-production окно полностью, ломает всё, что должно быть сломано |

**Интегрально: 8.0/10 как архитектурное направление; 6.5/10 как самодостаточный implementation-контракт до применения P1–P12.**

---

# 10. Final go/no-go

| Вопрос | Решение | Условия |
|---|---|---|
| **Can Slice 0A start?** | **GO — немедленно** | Без условий. Baseline-тесты только test-файлы, production-код не трогается. |
| **Can Slice 0B start?** | **GO — немедленно** | Одно техническое условие внутри среза: sanity-assert, что `FilteredClassLoader`-конфигурация действительно даёт «Micrometer present» в RED-сценариях (иначе false-negative). |
| **Before Slice 1A** | **NO-GO до выполнения:** | (1) ADR по Micrometer Observation API (P3); (2) патчи P4, P5, P10 внесены в план; (3) architect sign-off чеклиста §9 v3.3.1; (4) редакция §4 «primary vs governed replacement». |
| **Before Slice 1B** | **NO-GO до выполнения:** | (1) полный grep-инвентарь по расширенному symbol-набору (P8), отчёт прикреплён к PR и проверен архитектором вручную; (2) Slice 1A ArchUnit-гейты зелёные, включая anti-skeleton правило. |
| **Before Slice 2** | **NO-GO до выполнения:** | (1) P1 bean-singularity тест включён в объём среза; (2) P2 single-creation-boundary гейт включён в объём среза; (3) подтверждены internal `TracingState`/`TracingMode` values; (4) P7 diagnostics stability rule зафиксировано (правило — сейчас, реализация — Slice 7). |
| **Before Slice 6/7** | **NO-GO до выполнения:** | (1) Slice 2 гейты P1/P2 зелёные — иначе Slice 6 тестирует декоратор в вакууме; (2) полная topology matrix через decorator (ROOT+links, DETACHED, CHILD, CHILD+links opt-in) с проверкой `parentSpanId`/`isRemote`, не только счётчиков; (3) diagnostics DTO name/fields подтверждены + snapshot-тест JSON-контракта; (4) Observation coexistence тест (из P3) в объёме Slice 7. |

## Итог

План **не over-engineered** — каждый governance-механизм привязан к реальному наблюдённому дефекту или конкретному риску dumping-ground. План **не too conservative** — он ломает всё, что нужно сломать, и правильно пользуется отсутствием production-нагрузки. Оставшийся риск не в архитектуре, а в **дисциплине enforcement'а**: три заявленных инварианта (bean singularity, single creation boundary, diagnostics boundary) пока «правда по обещанию», а не «правда по тесту». Патчи P1–P12 переводят их в проверяемое состояние. После этого план — годный canonical implementation contract.

**0A/0B — стартовать сегодня. 1A — после ADR и патчей. Остальное — по гейтам выше.**
