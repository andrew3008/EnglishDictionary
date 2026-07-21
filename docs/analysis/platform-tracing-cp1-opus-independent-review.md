# CP-1 Identity Contract — независимый архитектурный ревью (Opus)

> Роль: независимый principal Java platform architect (арбитраж, не имплементация).
> Дата: 2026-07-21
> Репозиторий: `E:\Platform_Traces_CP1`
> Ветка: `feature/cp1-identity-contract-decision`
> HEAD: `3c5f6ba86fec740ca9005bd14343ce84175ffac3` (= CP-1 proposal commit `3c5f6ba`)
> Ревьюируемый пакет: `docs/analysis/platform-tracing-cp1-identity-contract-decision-packet.md` @ `3c5f6ba`
> Метод: попытка фальсифицировать И пакет, И внешний ревью. Изменения production/ABI/ADR/plan не вносились.
>
> **Ревизия R2 (2026-07-21) — итог зафиксирован:** независимый анализ вынес CP-1(d) → CHANGES REQUIRED и выдал 6 блокирующих корректировок (B1–B6). Все они приняты и внесены в авторитетный packet; **владелец платформы утвердил исправленную R2-архитектуру → `CP-1 APPROVED (R2)`**. Разделы §1–§20 сохранены как analysis-stage обоснование, приведшее к R2; финальный консолидированный контракт — в **§21**. Активные формулировки, расходившиеся с R2 (public accessor/binder, `api.context`-размещение binder, `Callable`, «finalized in Slice M», spike-assumption), исправлены на месте в §15/§16/§18/§20/§21.4/приложении, а не спрятаны за приоритетом §21.

Легенда достоверности:
- **[REPO]** — факт, подтверждённый исходным кодом репозитория (с указанием файла/строк).
- **[EXT]** — официальный внешний факт (OTel / W3C / Reactor / Micrometer / Spring).
- **[INFER]** — архитектурный вывод из фактов.
- **[ASSUMPTION]** — неразрешённое предположение (требует подтверждения владельцем платформы).

---

## 1. Executive verdict

**АНАЛИЗ-СТАДИЯ: CP-1(d) CHANGES REQUIRED** → 6 корректировок (B1–B6) внесены → **ФИНАЛ (owner): `CP-1 APPROVED (R2)`.** Разделы ниже сохранены как обоснование; финальный контракт — §21.

Индивидуально (analysis-stage вердикт; финальный статус всех — APPROVED после внесения B1–B6):

| Checkpoint | Вердикт | Комментарий |
|---|---|---|
| CP-1(a) baggage key `platform.correlation.id` | **ACCEPTED** | согласуется с существующим baggage-allowlist/`BaggageSpanProcessor` [REPO]; требуется устранить расхождение имени атрибута проекции (см. §10) |
| CP-1(b) `X-Correlation-ID` opt-in bridge | **ACCEPTED** | непротиворечиво; CP-C2 порт не затрагивается [REPO] |
| CP-1(c) option A (creation-time projection) | **ACCEPTED с корректировкой терминологии** | механизм подтверждён `BaggageSpanProcessor.onStart` [REPO]; термин «ingress-only» неточен (см. §10) |
| CP-1(d) OTel-free API + synchronous scope | **CHANGES REQUIRED** | 4 блокирующих дефекта (§16) |
| CP-1(f) F0 fail-closed | **ACCEPTED** | корректный secure default; требуется отдельный gate RG-IDENTITY-TRUST (§11, §19) |

**Внешний ревью по существу ПОДТВЕРЖДЁН** (частично фальсифицирована только формулировка пункта 4 — см. §3/§4). Четыре из пяти замечаний вскрывают реальные архитектурные дефекты CP-1(d); пятое (F0) — не дефект, а осознанное ограничение trust-модели.

CP-1(d) в текущей редакции **нельзя реализовать связно**, потому что: (1) read-порт (`RequestContextAccessor`) заменяем пользователем и способен разойтись с write-путём (`openCorrelationScope`/MDC); (2) синхронный `CorrelationScope` конструктивно не покрывает обязательный сценарий поздней асинхронной установки `correlationId` в WebFlux; (3) точный OTel-free write/bind seam не определён и не существует в репозитории; (4) «silent no-op scope» из §5 противоречит инварианту §5 «identity остаётся функциональной при выключенной эмиссии».

Slice M может стартовать после внесения блокирующих корректировок §16 и с ограничением: межсервисная **входящая** бизнес-корреляция остаётся нефункциональной до отдельного gate RG-IDENTITY-TRUST.

---

## 2. Repository facts verified

Pre-flight [REPO]:
- Текущая ветка: `feature/cp1-identity-contract-decision`; HEAD `3c5f6ba`.
- Рабочее дерево **чистое** (`git status --porcelain` пуст на момент старта анализа; данный отчёт — новый файл).
- Родитель HEAD = `5215f43` («Merge pull request #15 …») — совпадает с заявленной базой `master@5215f43`.
- **Нюанс:** локальная ветка `master` = `91065b5` (продвинута за пределы `5215f43`), поэтому `git merge-base HEAD master` возвращает `3c5f6ba`. Авторитетной базой считается `5215f43` по истории HEAD, а не текущий указатель `master`. [REPO]
- Коммит пакета: `3c5f6ba docs(tracing): propose CP-1 identity contract`, автор andrew3008, Tue Jul 21 17:16:58 2026 +0300.
- **CP-1 production implementation отсутствует:** типы `CorrelationScope`, `RequestContextAccessor`, `RequestIdentityContext`, метод `openCorrelationScope` в main-исходниках **не найдены** (только в тексте пакета). Подтверждено grep по `*.java`. [REPO]

Baseline-контракты (совпадают с §2 пакета) [REPO]:
- `TraceOperations` содержит только `traceContext()` и `spans()` — `platform-tracing-api/.../api/TraceOperations.java:21-29`.
- `ActiveTraceContextView` = `traceId()/spanId()/correlationId()` — `.../api/span/builder/ActiveTraceContextView.java:10-21`.
- `DefaultActiveTraceContextView.correlationId()` **всегда** `Optional.empty()`; `requestId()` отсутствует — `platform-tracing-core/.../core/context/DefaultActiveTraceContextView.java:32-36`.
- `RequestTraceContextSnapshot` — record из **трёх** полей `(correlationId, traceId, spanId)` — `.../api/context/RequestTraceContextSnapshot.java:15-17`. ABI baseline фиксирует 3-арг конструктор — `platform-tracing-core/src/test/resources/abi/platform-tracing-api-core.txt:59-60`.
- `TracingMdcKeys.CORRELATION_ID = "correlation_id"` (snake_case), синхронизирован с `ErrorHandlingMdcKeys#CORRELATION_ID` — `.../api/mdc/TracingMdcKeys.java:33-38`. Фактически несёт requestId.
- `RequestIdSupport`: allowlist `[A-Za-z0-9_-]`, `MAX_LENGTH=128` (reject, не truncate), **trim-and-accept**, генерация UUIDv4 — `platform-tracing-core/.../core/propagation/RequestIdSupport.java:28-80`. Javadoc ошибочно называет requestId «correlation id» (строки 10, 30).
- CP-C2 порт несёт только control-заголовки и **requestId**, не correlation: `OutboundPropagationDecision(propagateForceTrace, propagateQaTrace, propagateRequestId)` — `.../api/propagation/control/OutboundPropagationDecision.java:9-11`; `OutboundPropagationHeaders(forceTrace, qaTrace, requestId)` — `OutboundPropagationHeaders.java:12-24`; `PlatformOutboundPropagation.resolve(...)` — `PlatformOutboundPropagation.java:13-23`.
- `PlatformHeaders`: есть `X_REQUEST_ID`, `X_TRACE_ID`, `X_TRACE_ON`, `X_QA_TRACE`; **нет** `X_CORRELATION_ID` — `.../api/propagation/PlatformHeaders.java:12-48`.
- `PlatformAttributes`: есть `PLATFORM_REQUEST_ID = "platform.request_id"`; **нет** `PLATFORM_CORRELATION_ID` — `.../api/attributes/PlatformAttributes.java:88-95`.

Адаптеры и запись идентичности [REPO]:
- Servlet: `TraceResponseHeaderServletFilter` резолвит requestId на ingress через `RequestIdBoundarySupport.resolve(...)`, кладёт его в MDC (`mdcConfig.getRequestIdKey()`), пишет атрибут `platform.request_id` на `Span.current()`, чистит MDC в `finally`, ставит `X-Request-Id`/`X-Trace-Id` — `platform-tracing-autoconfigure-webmvc/.../autoconfigure/servlet/TraceResponseHeaderServletFilter.java:64-111`.
- `RequestIdBoundarySupport` — мост в `autoconfigure.support`, делегирует в `core.propagation.RequestIdSupport` — `.../autoconfigure/support/RequestIdBoundarySupport.java:14-29`.
- WebFlux: `TraceResponseHeaderWebFilter` вычисляет requestId на request-thread в `filter()`, ставит заголовки в `beforeCommit`, чистит в `doFinally` — `platform-tracing-autoconfigure-webflux/.../autoconfigure/reactive/TraceResponseHeaderWebFilter.java:37-61`. **correlationId не обрабатывается вовсе.**
- Reactor context bridge (существующий паттерн): `RemoteServiceReactorContext` — immutable значение в Reactor `Context.of(...)` + чтение из `ContextView` (`RemoteServiceReactorContext.java:16-47`); `RemoteServiceContextPropagation` — регистрация Micrometer `ContextRegistry.registerThreadLocalAccessor(...)` (`RemoteServiceContextPropagation.java:23-61`); `TracingReactorContextPropagationStartupRunner` — требует `spring.reactor.context-propagation=AUTO` (`TracingReactorContextPropagationStartupRunner.java:20-31`).
- Error snapshot: `RequestTraceContextSnapshotSupplier` читает `Span.current().getSpanContext()` (trace/span) и **`MDC.get(TracingMdcKeys.CORRELATION_ID)`** (correlationId) — `platform-tracing-spring-boot-autoconfigure/.../autoconfigure/errorhandling/RequestTraceContextSnapshotSupplier.java:44-60`. Регистрируется всегда, даже при `platform.tracing.enabled=false`, через `@ConditionalOnMissingBean(name=...)` — `RequestContextSupplierAutoConfiguration.java:37-45`.
- **Scheduler adapter в production отсутствует** — упоминания `@Scheduled/TaskScheduler` только в `samples`/`e2e`/tests. [REPO]
- Kafka: отдельного identity-write адаптера не обнаружено; есть `KafkaOutboundNoSpanArchTest` (гейт «нет span на egress»). [REPO]

Agent extension (Agent CL) [REPO]:
- Inbound sanitizer: `BaggagePropagationCustomizer` оборачивает W3C baggage-propagator в `FilteringBaggagePropagator` с allowlist + deny-patterns, только если baggage включён — `platform-tracing-otel-extension/.../otel/extension/propagation/BaggagePropagationCustomizer.java:10-22`.
- Projection owner: `BaggageSpanProcessor.onStart(...)` проецирует allowlisted baggage-ключи в атрибуты `baggage.<key>` **на создании каждого span** (родителя и детей) — `.../otel/extension/processor/BaggageSpanProcessor.java:39-71`.
- Classloader boundary гейт: `AUTOCONFIGURE_MAIN_NO_OTEL_EXTENSION_IMPL` — App CL autoconfigure не должен зависеть от `otel.extension..` — `platform-tracing-test/.../test/arch/ModuleTaxonomyArchRules.java:83-90`.

Архитектурные гейты [REPO] (`ModuleTaxonomyArchRules.java`):
- `API_MAIN_NO_OTEL_API` — `..api..` не зависит от `io.opentelemetry.api..` (строки 316-320).
- `WEB_AUTOCONFIGURE_MAIN_NO_CORE_IMPL` — пакеты `autoconfigure.servlet..`/`autoconfigure.reactive..` не зависят от `..core..` (строки 95-101). Доступ к core — только через мост `autoconfigure.support.*BoundarySupport`.
- `WEBMVC_MAIN_NO_WEBFLUX_STACK` / `WEBFLUX_MAIN_NO_SERVLET_STACK` (строки 106-124).
- `API_NO_SERVICE_LOADER` (60-64), `CONTROL_IMPLS_ONLY_IN_CORE` (366-378), `API_PROPAGATION_CONTROL_NO_CONCRETE_IMPL` (396-403).
- ABI snapshot гейт: `AbiSnapshotTest` рендерит публичный ABI `api`+`core` и сверяет с ресурсом `/abi/platform-tracing-api-core.txt` — `platform-tracing-core/.../core/arch/AbiSnapshotTest.java:38-74`.

Конвенция checked-исполнения [REPO]:
- В API уже есть `space.br1440.platform.tracing.api.util.ThrowingSupplier<T>` с `T get() throws Exception` — `.../api/util/ThrowingSupplier.java:14-16`. Используется в `SpanExecution.callChecked` (`SpanExecution.java:71`), `ManualSpanBuilder.callChecked` (`ManualSpanBuilder.java:40`), `PlatformContextPropagation.wrap` (`PlatformContextPropagation.java:60`).

Disabled/NoOp [REPO]:
- `NoopTraceOperations` делегирует `traceContext()` в `NoOpTracingRuntime.currentTraceContext()` — `platform-tracing-core/.../core/facade/NoopTraceOperations.java:12-38`.
- `DefaultTraceOperations` умеет переключать runtime (`setFacadeEnabled`) на `NoOpTracingRuntime.disabledByConfiguration(...)` — `.../core/facade/DefaultTraceOperations.java:50-54`.
- `TracingMode` enum и `NoOpTracingRuntime` — `.../core/runtime/state/TracingMode.java`, `.../core/runtime/NoOpTracingRuntime.java`.

---

## 3. Claims falsified

Что удалось фальсифицировать (полностью или частично):

1. **Пакет §5 самопротиворечив** (не внешний ревью, а сам пакет). Утверждение «Identity … remains available when telemetry emission is disabled» (§5, п.1-2) **несовместимо** с «A no-op `CorrelationScope` is permitted … it still validates input and closes idempotently» (§5, п.4). Если scope — no-op, то внутри него `current().correlationId()` не вернёт присвоенное значение, т.е. identity **не** функциональна. Это фальсифицирует внутреннюю связность §5. [REPO/INFER]

2. **Терминология CP-1(c) «ingress-only projection» неточна.** `BaggageSpanProcessor.onStart` проецирует baggage в атрибут при создании **любого** span, включая child-спаны под поздним scope (`BaggageSpanProcessor.java:39-49`). Значит проекция не «только ingress», а «birth-time для каждого span». Пакет сам это признаёт в §7.2 п.5, но заголовок термина остаётся вводящим в заблуждение. [REPO]

3. **Формулировка внешнего замечания №4 («silent no-op … violates the assignment API postcondition») — частично неверна как абсолют.** No-op сам по себе не нарушает постусловие, если контракт API честно объявляет assignment как *best-effort/unavailable* в отсутствие runtime. Дефект не в «no-op», а в том, что пакет одновременно обещает функциональную identity при disabled (§5) и разрешает no-op — это противоречие пакета, а не универсальный запрет no-op. Итог: замечание указывает на реальный дефект, но по неверной причинной формулировке. [INFER]

4. **Неявное допущение пакета о существовании scheduler-адаптера** (§4, строка Scheduler; §8.2; §11) **не подтверждается**: production scheduler-интеграции в репозитории нет. Строка Scheduler в §4 — аспирационная. [REPO]

Не удалось фальсифицировать (устояли под проверкой): CP-1(a), CP-1(b), CP-1(c)-механизм option A, CP-1(f) F0, семантические инварианты `traceId≠requestId≠correlationId`.

---

## 4. Claims confirmed

Подтверждённые замечания внешнего ревью (все — по CP-1(d)/(f)):

1. **Split source of truth (замечание №1) — ПОДТВЕРЖДЕНО.** §3.2 разрешает пользовательскому бину **полностью заменить** default `RequestContextAccessor` (read-путь). Write-путь (`openCorrelationScope`/MDC/BoundarySupport) остаётся платформенным. Уже сегодня `RequestTraceContextSnapshotSupplier` читает `MDC` напрямую (`RequestTraceContextSnapshotSupplier.java:47`), а не через view. При пользовательской замене accessor читатель и писатель гарантированно расходятся. [REPO]

2. **Синхронный scope не покрывает позднюю reactive-установку (замечание №2) — ПОДТВЕРЖДЕНО.** §3.3 явно запрещает переносить lifetime scope через `Publisher/Mono/Flux/CompletionStage` и требует same-thread create+close. Обязательный сценарий Analysis E (correlationId определяется асинхронно из аутентифицированных доменных данных, затем должен попасть в child-спаны/логи/egress) в пакете **не имеет определённого API**. §4 делегирует «reactive lifetime» адаптерам, но конкретный reactive-механизм не задан. [REPO]

3. **Не определён OTel-free write/bind seam (замечание №3) — ПОДТВЕРЖДЕНО.** Такого порта в репозитории нет; сегодня Servlet-фильтр пишет requestId напрямую в MDC + span-атрибут (`TraceResponseHeaderServletFilter.java:76-84`). §4 перечисляет «writers» словами, но не задаёт тип/модуль/сигнатуры. Для реализуемости Slice M это блокирующий пробел. [REPO]

4. **Противоречие no-op scope / функциональной identity (замечание №4) — ПОДТВЕРЖДЕНО** (по существу; см. §3 о формулировке). [REPO]

5. **F0 не даёт межсервисную бизнес-корреляцию до verifier (замечание №5) — ПОДТВЕРЖДЕНО и является дизайном.** Точка санитизации baggage — Agent-propagator до создания server-span (`BaggagePropagationCustomizer`), а прикладные Spring-фильтры выполняются уже после закрытия SERVER-span агентом (см. javadoc `TraceResponseHeaderServletFilter.java:37-45`). Значит trusted inbound требует agent-side verifier. [REPO/INFER]

---

## 5. Identity state and ownership model (Analysis A)

**A1. Источник правды requestId.** Сейчас — эфемерный: резолв на ingress (`RequestIdBoundarySupport.resolve`) с записью в MDC + span-атрибут; единого immutable-носителя нет [REPO]. Целевой (рекомендуемый): **один платформенный execution-scoped identity store** (OTel `Context` ключ в otel-runtime + Reactor `Context` в WebFlux), из которого читают и `ActiveTraceContextView.requestId()`, и snapshot supplier. requestId генерируется при отсутствии на ingress и стабилен по цепочке/ретраям.

**A2. Источник правды correlationId.** Сейчас — не существует (`DefaultActiveTraceContextView.correlationId()==empty`; MDC `correlation_id` фактически несёт requestId) [REPO]. Целевой: тот же единый store; correlationId **валидируется, но не генерируется** платформой (бизнес-владение). Транспорт — baggage `platform.correlation.id`.

**A3. Хранить вместе или как композитную view?** Рекомендуется **единый immutable value** `RequestIdentity{requestId?, correlationId?}` в одном store, а наружу — read-only проекция через `ActiveTraceContextView`. Раздельные носители для requestId и correlationId запрещены (риск рассинхронизации; ровно этот риск в замечании №1).

**A4. Чтение/запись по режимам:**

| Режим | Запись | Чтение / cleanup |
|---|---|---|
| Синхронный | ingress-адаптер биндит requestId; `openCorrelationScope` биндит correlationId (LIFO) | accessor/ view; `close()` восстанавливает предыдущее |
| Servlet async dispatch | bind на входе; проекция в span при birth | cleanup в `finally` фильтра; повторный dispatch не должен утекать |
| Reactor subscription | immutable-значение в Reactor `Context` **на subscription** (паттерн `RemoteServiceReactorContext`) | чтение через Micrometer bridge; `doFinally` (complete/error/cancel) |
| Kafka listener | новый requestId на сообщение при отсутствии | scope закрывается после success/error |
| Kafka retry/redelivery | requestId стабилен для логической единицы; без утечки между сообщениями | per-message scope |
| Scheduler | **не определено — адаптера нет** [REPO] | — |
| Disabled | минимальный функциональный store (identity — инфраструктура) | без внешней эмиссии baggage |
| Agent-only | facade/accessor отсутствуют (§4) | проекция baggage→span делает Agent |

**A5. Расхождения возможны с:** MDC (пишется отдельно фильтром), span-атрибутами (пишутся отдельно; `platform.request_id` vs `baggage.*`), outbound (CP-C2 несёт только requestId). Единый store устраняет расхождение с `ActiveTraceContextView`; MDC/span/baggage становятся производными проекциями store, а не независимыми истинами. [INFER]

**A6. Тип `RequestIdentityContext`.** Публичный контракт чтения — `interface` с `Optional<String>` (как в §3.1) корректен; реализация — **immutable value** (record) internal. Не делать record публичным value-типом с прямыми конструкторами (иначе приложение сможет фабриковать «чужую» identity). [INFER]

**A7. `RequestTraceContextSnapshot` и моделирование отсутствия.** Nullable-поля record (§3.6) допустимы (это DTO для error-mapping, а не логика). Но supplier обязан читать из единого store/view, **не из MDC** (`RequestTraceContextSnapshotSupplier.java:47`), иначе снова split source. Рекомендация: 4 поля `(requestId, correlationId, traceId, spanId)`, заполнение из view. [REPO/INFER]

**Невалидные/недостижимые/неоднозначные состояния:**
- *Неоднозначное:* accessor заменён пользователем, но scope пишет платформенный store → read≠write (замечание №1). [REPO]
- *Противоречивое:* disabled + no-op scope, но обещана функциональная identity (§5). [REPO]
- *Частично инициализированное:* requestId установлен, correlationId нет — валидно (оба `Optional`).
- *Недостижимое (после фикса):* correlationId != null при requestId == null допустимо семантически (correlationId может жить вне request), поэтому не запрещать.

---

## 6. RequestContextAccessor arbitration (Analysis B)

Оценка моделей (единый SoT / когерентность с `openCorrelationScope()` / Spring-топология / прямая композиция / тестируемость / риск misuse / расширяемость / ясность API-SPI / classloader / disabled / сложность):

- **A. User-replaceable через `@ConditionalOnMissingBean`** (предложение пакета §3.2): SoT — **нарушается** (read заменяем, write — нет); misuse-риск высокий; ясность низкая (application API притворяется портом). **Отклоняется.**
- **B. Platform-owned + fail-fast при коллизии бинов:** SoT сохранён; расширяемости нет; сложность низкая. Хорошо.
- **C. Platform-owned + отдельный contributor/resolver SPI (feeds store на ingress):** SoT сохранён (SPI кормит store, не подменяет чтение); расширяемость есть; сложность средняя. Хорошо для будущего.
- **D. Cohesive replaceable provider (read+write вместе):** заменяемость всего провайдера сохраняет внутреннюю когерентность, но открывает подмену write-путём → высокий риск для pre-production; отклоняется как публичная точка.
- **E. Нет публичного accessor; приложение читает только через `ActiveTraceContextView`:** SoT максимально прост; минимальная поверхность; тестируемость хорошая (мокать view); расширяемости нет. Очень хорошо для pre-production.
- **F. (из репо-фактов) Internal accessor + `ActiveTraceContextView` наружу + `autoconfigure.support` мост для записи:** совпадает с существующим паттерном `RequestIdBoundarySupport`/boundary-support. [REPO]

**Рекомендация: E как публичная поверхность + F как внутренняя реализация; C — как отложенная точка расширения, если появится доказанная потребность.** Публичный `RequestContextAccessor` для замены приложением **не вводить**.

Явные ответы:
- **Может ли пользовательский override наблюдать platform-opened `CorrelationScope`?** — **Нет.** `openCorrelationScope` пишет платформенный store (OTel/Reactor Context), а пользовательский accessor читает собственный источник; связь между ними в пакете не определена и не существует. Доказательство: write-путь идёт через internal store/MDC (`TraceResponseHeaderServletFilter.java:76-84`, планируемый `openCorrelationScope`), read-путь — произвольная реализация бина. Мост отсутствует. [REPO/INFER]
- **Классификация серьёзности:** **P0 (блокирующая)** для CP-1(d) — раскол источника правды напрямую ломает CP-1(c)/логи/error-model.
- **Что такое `RequestContextAccessor`?** — это **infrastructure read port**. **[Уточнено в §21 R2-C1]** его **не следует** размещать в `platform-tracing-api` (иначе он становится public ABI и доступен приложению — внутреннее противоречие). Финальное решение: публичный тип accessor/binder в API **не вводится вовсе**; единый immutable store принадлежит implementation-плоскости, публичное чтение — только через `ActiveTraceContextView`.
- **Замена приложением в pre-production:** **не поддерживать.** YAGNI + высокий misuse-риск + отсутствие доказанного потребителя.

---

## 7. Infrastructure write/bind seam (Analysis C)

Существующего подходящего OTel-free порта записи identity **нет** [REPO]. Ближайший паттерн — мост `autoconfigure.support.RequestIdBoundarySupport` (web→core без нарушения `WEB_AUTOCONFIGURE_MAIN_NO_CORE_IMPL`). Проектирую минимальный связный seam, опираясь на него.

> **[R2-C2, приоритет §21]** Ниже показанный `RequestIdentityBinder` **не должен** попадать в `platform-tracing-api`: публичный тип в API = public ABI + экспонирование requestId-mutation приложению (что прямо запрещено). Финальное размещение: internal manager/store — в implementation-плоскости; `RequestIdentityBoundarySupport` — в `platform-tracing-spring-boot-autoconfigure`; WebMVC/WebFlux/Kafka обращаются **только** к boundary support; application API для requestId-mutation отсутствует; application-присвоение correlationId — через `TraceOperations` (sync) и reactive-модуль (§9/§21). Код ниже сохранён как иллюстрация сигнатур store, но его пакет — **implementation**, не `api`.

**Тип:** `RequestIdentityScope` (носитель) + internal-порт `RequestIdentityBinder` (писатель).

```java
// implementation-плоскость (например platform-tracing-core, пакет core.context) — НЕ platform-tracing-api
// OTel-free на уровне контракта; только JDK + jakarta.annotation
public interface RequestIdentityScope extends AutoCloseable {
    @Override void close();               // idempotent, LIFO restore, не бросает checked
}

interface RequestIdentityBinder {          // internal, не публичный ABI
    @Nonnull RequestIdentityScope bindRequestId(@Nonnull String requestId);
    @Nonnull RequestIdentityScope bindCorrelationId(@Nonnull String correlationId);
}
```

- **Модуль/пакет:** контракт и реализация — **implementation** (`platform-tracing-core`, `core.context`), поверх единого store (OTel `Context` ключ в otel-runtime). Web-модули вызывают **не** core напрямую, а мост `autoconfigure.support.RequestIdentityBoundarySupport` (симметрично `RequestIdBoundarySupport`), удовлетворяя `WEB_AUTOCONFIGURE_MAIN_NO_CORE_IMPL`. [REPO]
- **Видимость:** `RequestIdentityBinder` — **internal** infrastructure port (не application API, не в ABI); методы записи requestId **не** экспонируются приложению (см. запрет «arbitrary requestId mutation»).
- **Scope/lifetime:** `AutoCloseable`, dynamic extent текущего execution; LIFO-восстановление.
- **Ownership:** платформа; ровно один bean на composition root.
- **Idempotency:** `close()` повторно — no-op (паттерн `OwningSpanScope` с `AtomicBoolean`, `OwningSpanScope.java:84-101`). [REPO]
- **Nesting:** вложенные scope восстанавливают предыдущий immutable identity.
- **Same-thread vs async:** биндер работает same-thread; для async значение **не** переносится через объект scope, а кладётся в Reactor `Context` (WebFlux) — см. §9.
- **Invalid value:** `IllegalArgumentException` до мутации store. **[R2-C6]** binder **не** переиспользует `RequestIdSupport.sanitizeOrNull` (тот делает trim-and-accept): для уже canonical identity binder принимает значение как есть, **без trim и нормализации**, и бросает `IllegalArgumentException` при неканоническом входе. Ingress-parsing (толерантный) и internal binding (строгий) — разные операции.
- **Cleanup:** обязательный `close()` в `finally`/`doFinally`.
- **Disabled:** функционален (минимальный store), без внешней эмиссии baggage.
- **Bean cardinality:** один; конкурирующие — fail-fast (не `@Primary`).
- **Связь с `RequestContextAccessor`/`TraceOperations`:** accessor/ view читают **тот же** store, что пишет binder; `TraceOperations.openCorrelationScope` делегирует в `binder.bindCorrelationId`. Единый store — ключ к устранению замечания №1.
- Не вводить generic `Object`-carrier/`Map` (соблюдено).

---

## 8. Synchronous API decision (Analysis D)

Решения:

1. **Все три метода?** Достаточно `openCorrelationScope` + один execution-хелпер. `Runnable`-перегрузка — приемлемое удобство. Отдельная `Callable`-перегрузка **избыточна**.
2. **Callable vs Supplier vs checked-интерфейс?** Использовать **`ThrowingSupplier`** — это **уже конвенция репозитория** (`SpanExecution.callChecked`, `ManualSpanBuilder.callChecked`, `PlatformContextPropagation.wrap`; `ThrowingSupplier.java:14-16`) [REPO]. `java.util.concurrent.Callable` в §3.4 **противоречит** собственной конвенции платформы и вводит второй checked-стиль. **Блокирующая замена.**
3. **Cross-thread close:** **бросать `IllegalStateException`** и не трогать чужой context (как в §3.3). Согласен.
4. **Может ли close маскировать исключение приложения?** **Нет.** Ошибки самого `close()` — глотать/логировать; исключение action — пробрасывать без обёртки (паттерн `ScopedExecution.java:18-25`, `OwningSpanScope.close` глотает свои ошибки). [REPO]
5. **Silent no-op assignment валиден?** **Нет.** Либо минимальный функциональный store (значение читается внутри scope), либо честно документированная недоступность. «Тихий» no-op, принимающий значение, но не отражающий его в чтении, — запрещён (противоречие §5).
6. **`TraceOperations` без функциональной identity-реализации?** **Нет.** identity — инфраструктура; должна работать и в NoOp-режиме (`NoopTraceOperations` обязан иметь работающий `openCorrelationScope` поверх минимального store, а не бросать/no-op). [REPO]
7. **Работа identity при span emission DISABLED?** **Да, обязательно** (§5 п.1-2, после устранения противоречия с no-op).

**Рекомендуемый контракт (замена §3.3/§3.4):**

```java
// TraceOperations delta
@Nonnull CorrelationScope openCorrelationScope(@Nonnull String correlationId);
void withCorrelationId(@Nonnull String correlationId, @Nonnull Runnable action);
<T> T withCorrelationId(@Nonnull String correlationId,
                        @Nonnull ThrowingSupplier<T> action) throws Exception; // не Callable
```

Javadoc-семантика: `null` → NPE; невалидный correlationId → `IllegalArgumentException` до изменения контекста; action ровно один раз; scope закрывается в `finally`; исключения action не оборачиваются; nested — LIFO-восстановление logical context + baggage + MDC; возвращаемый deferred-объект **не** получает распространение lifetime; `close()` idempotent, не бросает checked; cross-thread `close()` → `IllegalStateException` без мутации чужого context; при DISABLED — scope функционален (значение видно через `current()` внутри extent), но baggage наружу не эмитится.

---

## 9. Reactive/WebFlux API decision (Analysis E) — блокирующая зона

Обязательный сценарий (поздняя аутентифицированная установка correlationId) синхронным API **не покрывается**: try-with-resources scope закрывается при возврате из assembly-метода, до исполнения отложенных стадий (`flatMap`/`publishOn`) → child-спаны/логи/egress значение не получат. [EXT: Reactor — операторы исполняются на subscription, а не на assembly.]

Оценка вариантов:

| Вар. | Вердикт | Обоснование |
|---|---|---|
| A. Синхронный `openCorrelationScope` внутри reactive-callback | **Отклонён** | scope закрывается на границе callback; не переживает `publishOn`/`flatMap`; риск утечки Scope через async |
| B. `Mono/Flux` из `withCorrelationId` в API | **Отклонён** | вносит Reactor-типы в `platform-tracing-api` (нарушает инвариант API OTel/Reactor-free) |
| C. Отдельный `ReactiveCorrelationOperations` в webflux-модуле | Возможен | но интерфейс избыточен, если хватает оператора |
| **D. Reactor Context operator/transformer в webflux** | **ПРИНЯТ** | совпадает с существующим паттерном `RemoteServiceReactorContext` + Micrometer `ContextRegistry` + `AUTO` [REPO] |
| E. Framework-neutral mutation token | Отклонён | скрытая магия; дублирует Reactor Context |
| F. Запрет поздней reactive-установки (ingress-only) | Отклонён | убирает валидный доменный сценарий без снижения ingress-риска |
| G. Иное | — | не требуется |

Концептуальная проверка варианта D по осям: cold/hot publishers — значение в `Context` доступно downstream независимо от источника; assembly vs subscription — запись через `contextWrite` применяется на subscription (корректно); `Mono.defer`/`deferContextual` — читаем `ContextView` детерминированно; repeat/retry/resubscribe — каждая подписка несёт свой immutable `Context` (изоляция); nested — верхний `contextWrite` перекрывает вложенный локально; `publishOn`/`subscribeOn`/parallel — Micrometer `ThreadLocalAccessor` восстанавливает ThreadLocal на смене планировщика (паттерн `RemoteServiceContextPropagation.java:23-61`) при `spring.reactor.context-propagation=AUTO` (`TracingReactorContextPropagationStartupRunner.java`); cancellation/complete/error — cleanup в `doFinally`; context loss — предотвращается `AUTO`; Micrometer — используется как штатный мост; OTel Agent — child-спаны получают значение через baggage/birth-projection (`BaggageSpanProcessor.onStart`); MDC — через `ThreadLocalAccessor`; видимость в child-span — да, на их создании. [REPO/EXT]

**Reactor-специфичный API.**

> **[R2-C3, приоритет §21]** Сценарий прикладной (приложение асинхронно определяет correlationId), значит нужен **поддерживаемый application API** в WebFlux-модуле, а не набор internal-static-хелперов. Ранее предложенные `Context writeCorrelationId(String)` / `readCorrelationId(ContextView)` недостаточны: они не выражают преобразование существующего Reactor `Context` и не задают участок pipeline, получающий correlationId. Финальная форма — операторный application API:

```java
// space.br1440.platform.tracing.autoconfigure.reactive — public application API WebFlux-модуля
// Reactor-типы допустимы здесь и остаются ВНЕ platform-tracing-api
public interface ReactiveCorrelationOperations {
    <T> reactor.core.publisher.Mono<T> withCorrelationId(String correlationId, reactor.core.publisher.Mono<T> execution);
    <T> reactor.core.publisher.Flux<T> withCorrelationId(String correlationId, reactor.core.publisher.Flux<T> execution);
}
```

Пример использования:

```java
return resolveBusinessProcessId()
        .flatMap(id -> reactiveCorrelationOperations.withCorrelationId(id, executeBusinessOperation()));
```

- Внутри оператор пишет **immutable String** в Reactor `Context` (`contextWrite`), **не** OTel `Scope`. OTel `Context`/`Scope` материализуется синхронно на каждом hop’е Micrometer-мостом и немедленно закрывается — Scope не переживает async-границу. [REPO/EXT]
- **Blocking runtime-spike обязателен** до фиксации точных сигнатур: `publishOn`/`subscribeOn`, retry/repeat, nested assignment, concurrent subscribers, complete/error/cancel, видимость baggage/MDC/child-span, отсутствие переноса Scope через async-границу. Уровень теста — `ReactorContextPropagationIntegrationTest` [REPO].

---

## 10. Span projection decision (Analysis F)

Сравнение:
- **A. birth-time проекция, родитель неизменяем** — детерминизм высокий; конкурентные поздние присвоения не мутируют родителя (нет CAS/lock над `Span`); согласуется с неудаляемостью span-атрибутов; child получает значение при своём создании. [REPO: `BaggageSpanProcessor.onStart`]
- B. last-write-wins мутация активного span — недетерминизм при конкуренции; отклонён.
- C. first-write-wins мутация — требует координации/CAS над OTel `Span`; отклонён.
- D. запрет поздней установки — убирает валидную child-корреляцию; отклонён.
- E. новый child-span при смене correlationId — искажает топологию трассы; отклонён.

**Вывод: option A подтверждён.** Механизм уже существует (`BaggageSpanProcessor.onStart` проецирует на birth каждого span). 

**Терминология:** «ingress-only projection» **неточна**, т.к. child-спаны под поздним scope получают значение на своём birth. Рекомендуемое имя политики: **«birth-time projection with immutable parent and child inheritance»** (кратко — *birth-time projection*). 

**Замечание по имени атрибута:** пакет вводит `PlatformAttributes.PLATFORM_CORRELATION_ID = "platform.correlation_id"` (§3/§9), но существующий `BaggageSpanProcessor` проецирует allowlisted baggage в `baggage.<key>` → `baggage.platform.correlation.id` (`BaggageSpanProcessor.java:70`). Необходимо явно решить: (i) выделенная проекция в `platform.correlation_id`, ИЛИ (ii) переиспользование baggage-проекции `baggage.platform.correlation.id`. Двойной атрибут для одной сущности запрещать. [REPO] — **[R2-C4] переклассифицировано в BLOCKING** (было non-blocking N2). Финальное решение фиксируется до Slice M: **canonical span attribute = `platform.correlation_id`**; generic baggage-проекция (`baggage.platform.correlation.id`) для этого ключа **подавляется**; прочие allowlisted baggage-записи продолжают использовать `baggage.*`.

---

## 11. Trust and propagation decision (Analysis G)

Ответы:
1. **F0 — корректный secure default?** **Да.** Нет доказанного authenticated trust-signal (§8.1); заголовки/IP/hostname/`X-Forwarded-*`/topic — не аутентификация. [REPO/INFER]
2. **Где санитизация до Agent-спанов?** В **Agent-extension propagator/allowlist** (`BaggagePropagationCustomizer`→`FilteringBaggagePropagator`, `BaggageSpanProcessor.onStart`) — до создания server/consumer-span. [REPO]
3. **Могут ли Spring-фильтры приложения обеспечить это достаточно рано?** **Нет.** Agent закрывает SERVER-span на выходе из `HttpServlet.service()` **раньше**, чем управление доходит до прикладного фильтра (javadoc `TraceResponseHeaderServletFilter.java:37-45`). Прикладные фильтры для pre-span санитизации опаздывают. [REPO]
4. **Что принадлежит Agent-extension?** Inbound-фильтрация baggage по allowlist, отказ от непроверенного `platform.correlation.id`, projection на span. [REPO]
5. **Делает ли F0 межсервисную бизнес-корреляцию нефункциональной?** **Да — для входящей** непроверенной корреляции (by design). Egress через trusted internal baggage и programmatic scope остаются рабочими.
6. **Может ли Slice M всё же идти?** **Да**, кроме trusted inbound: контракт идентичности, intra-service, programmatic, trusted-egress-baggage реализуемы под F0.
7. **Отдельный release gate?** Да — до production нужен верификатор транспортного доверия.
8. **RG-IDENTITY-TRUST или часть RG-CONTROLLED-AGENT?** **Отдельный `RG-IDENTITY-TRUST`.** Хотя enforcement — agent-side, это самостоятельный security-control со своим негативным evidence (spoof/missing-verifier/verifier-exception/ambiguous). Смешивать с RG-CONTROLLED-AGENT не следует.
9. **Какое authenticated evidence для HTTP/Kafka?** Проверенная mTLS workload-identity / подписанный токен (не сырой заголовок), с owning-модулем verifier, источником конфигурации и поведением при ротации/сбое (§8.2 п.1-5). Для Kafka — аутентифицированный producer identity, не topic name.
10. **Осмысленна ли scheduler «trusted job metadata» под F0 без verifier?** **Нет** — и scheduler-адаптера в репозитории нет [REPO]. correlationId для job — только из programmatic app-scope, не из «доверенной метадаты».

Никакого доверия из IP/hostname/заголовков/topic/конфигурации — соблюдено.

---

## 12. Value-format decision (Analysis H)

Предложенный формат (`1..128`, ASCII, `[A-Za-z0-9_-]`, case preserved, без trim/replace/truncate) **безопасен и достаточен**:
- W3C Baggage [EXT]: значения percent-кодируются; `[A-Za-z0-9_-]` — подмножество token-safe, безопасно.
- HTTP-заголовки [EXT]: все символы — из token-набора RFC 7230; нет CR/LF → нет CWE-113 (совпадает с инвариантом `RequestIdSupport`, `RequestIdSupport.java:14-16`).
- Kafka-заголовки: произвольные байты; ASCII-подмножество безопасно.
- MDC/логи: нет разделителей/инъекций.
- Span-атрибуты: строка; high-cardinality — как атрибут допустимо, как метрика-dimension **запрещено** (ср. `PlatformAttributes.java:88-95`).

**Нужен ли `.`?** **Нет.** `.` не добавляет реальной выразительности для opaque-идентификатора и рискует конфликтовать с иерархическим парсингом ключей. Allowlist оставить минимальным.

**Расхождение по trim:** `RequestIdSupport` использует **trim-and-accept** (`RequestIdSupport.java:19-21,63-79`), а пакет §7.1 требует «programmatic input is not silently trimmed». Для correlationId рекомендация: **programmatic input — reject-not-trim** (`IllegalArgumentException` при ведущих/хвостовых пробелах), OWS допустимы только на HTTP-границе штатным парсером. Это устраняет неоднозначность канонического значения. [REPO]

**Каноника и генерация:**
- correlationId платформа **не генерирует** (бизнес-владение) — только валидирует; при невалидном ingress — drop, без подстановки. 
- Если приложению нужен генератор — UUIDv4/ULID, opaque, **без PII/секретов/бизнес-данных** (совпадает с генерацией requestId, `RequestIdSupport.java:42-43`).
- requestId — генерируется платформой при отсутствии (как сейчас).

---

## 13. Disabled/NoOp state matrix (Analysis I)

Обозначения: ✔ доступно/функционально; ✖ отсутствует/NoOp; → значение.

| Состояние | TraceOperations | RequestContextAccessor (internal) | openCorrelationScope | ActiveTraceContextView | MDC | baggage | requestId gen | outbound identity | span emission |
|---|---|---|---|---|---|---|---|---|---|
| enabled + Agent READY | ✔ | ✔ (store) | ✔ | ✔ requestId+correlationId | ✔ | ✔ (trusted egress) | ✔ ingress | ✔ (CP-C2 requestId; correlation baggage) | ✔ |
| `sdk.mode=DISABLED` | ✔ (facade) | ✔ (store) | ✔ (функц., реверсивен) | ✔ (identity), trace/span empty | ✔ | ✖ внешне | ✔ | requestId да / correlation baggage нет | ✖ NoOp |
| `platform.tracing.enabled=false` | ✖ (фильтры/`TraceOperations` отсутствуют) | ✔ только default snapshot supplier (§5) | ✖/через минимальный store, если бин присутствует | пусто, если нет адаптера | supplier читает MDC (сегодня) | ✖ | ✖ (Slice M не добавляет молча) | ✖ | ✖ |
| direct/manual composition | ✔ или NoOp | ✔ (минимальный store) | ✔ (функц.); **не** silent-no-op | ✔ | зависит | ✖ | по адаптеру | ✖ | по runtime |
| Agent-only | ✖ facade/accessor (§4) | ✖ | ✖ | ✖ | Agent-side | ✔ Agent projection | Agent | Agent | ✔ Agent |
| invalid composition (2 accessor-бина) | fail-fast startup | — | — | — | — | — | — | — | — |
| missing identity impl | NoOp | пустой store | **[R2-C5]** если `TraceOperations` присутствует — assignment функционален; иначе тип отсутствует (не silent-no-op) | empty | — | ✖ | ✖ | ✖ | по runtime |
| competing/custom beans | fail-fast | — | — | — | — | — | — | — | — |

**Инвариант [R2-C5]:** *If `TraceOperations` is present, correlation assignment is functional.* При `sdk.mode=DISABLED`: spans — NoOp; identity store — функционален; nested scopes работают; `ActiveTraceContextView` видит присвоенное значение; внешняя baggage-propagation выключена. При некорректной composition — **fail-fast**, а не silent degradation. Формулировку «functional no-op only if documented as unavailable» из более ранней редакции **удалить**.

**Противоречия пакета:**
1. §5 п.1-2 (identity функциональна при disabled) ⨯ §5 п.4 (silent no-op scope). Разрешить в пользу функционального минимального store. [REPO]
2. §5 «default `RequestContextAccessor` … remain registered» при `enabled=false`, но при этом снапшот сегодня читает MDC, а не accessor → снова split. Требуется единый store/view как источник для supplier. [REPO]

---

## 14. Alternative architectures and scoring (Analysis J)

Кандидаты:
1. **Packet** — как в §3-§9 пакета (user-replaceable accessor, synchronous-only, Callable, no-op при disabled).
2. **External-review corrections** — accessor не заменяем, добавлен reactive-механизм, определён write-seam, запрет no-op.
3. **Minimal public API + platform-owned internal identity service** — публично только `ActiveTraceContextView` + `openCorrelationScope`; всё остальное internal.
4. **Strongest independent (рекомендуемый)** — единый platform-owned store; read через `ActiveTraceContextView` (accessor internal); write-seam `RequestIdentityBinder` + `autoconfigure.support` мост; reactive-оператор в webflux; `ThrowingSupplier`; no-op запрещён; F0 + RG-IDENTITY-TRUST; birth-time projection.

Веса (обоснование: pre-production, security- и correctness-first, breaking changes разрешены → выше вес семантики/безопасности/границ, ниже — extensibility/cognitive load; сумма = 100):

| Критерий | Вес |
|---|---|
| Semantic coherence | 15 |
| Reactive correctness | 12 |
| Security | 14 |
| API usability | 7 |
| API/SPI clarity | 9 |
| Module boundaries | 10 |
| Agent compatibility | 8 |
| Testability | 8 |
| Operational usefulness | 6 |
| Implementation risk | 5 |
| Future extensibility | 3 |
| Cognitive load | 3 |

Оценки (1-10):

| Критерий (вес) | 1.Packet | 2.Ext | 3.Minimal | 4.Independent |
|---|---|---|---|---|
| Semantic coherence (15) | 4 | 8 | 8 | 9 |
| Reactive correctness (12) | 3 | 8 | 6 | 9 |
| Security (14) | 6 | 8 | 8 | 9 |
| API usability (7) | 7 | 7 | 6 | 8 |
| API/SPI clarity (9) | 5 | 7 | 8 | 9 |
| Module boundaries (10) | 6 | 8 | 8 | 9 |
| Agent compatibility (8) | 7 | 8 | 8 | 8 |
| Testability (8) | 5 | 8 | 8 | 9 |
| Operational usefulness (6) | 6 | 7 | 6 | 8 |
| Implementation risk (5, выше=безопаснее) | 6 | 6 | 7 | 6 |
| Future extensibility (3) | 6 | 7 | 5 | 7 |
| Cognitive load (3, выше=проще) | 6 | 6 | 8 | 7 |

Взвешенные суммы (веса в сумме = 100):
- **1. Packet:** 4·15+3·12+6·14+7·7+5·9+6·10+7·8+5·8+6·6+6·5+6·3+6·3 = **532**.
- **2. External-review:** 8·15+8·12+8·14+7·7+7·9+8·10+8·8+8·8+7·6+6·5+7·3+6·3 = **759**.
- **3. Minimal:** 8·15+6·12+8·14+6·7+8·9+8·10+8·8+8·8+6·6+7·5+5·3+8·3 = **736**.
- **4. Independent:** 9·15+9·12+9·14+8·7+9·9+9·10+8·8+9·8+8·6+6·5+7·3+7·3 = **852**.

**Победитель: №4 (Strongest independent), затем №2.** Пакет (№1) — заметно ниже из-за семантической связности и reactive correctness.

**Sensitivity analysis:** при обнулении весов Security и Reactive correctness (−26): №4 = 852−9·14−9·12 = **618**, №2 = 759−8·14−8·12 = **551**, №3 = 736−8·14−6·12 = **552**, №1 = 532−6·14−3·12 = **412**. №4 остаётся первым; №2/№3 сближаются, но не обгоняют №4. При утроении весов usability+extensibility+cognitive №3/№4 остаются впереди №1. Порядок «№4 > {№2,№3} > №1» **устойчив**; веса не подгонялись.

---

## 15. Exact CP-1(a-d,f) resolution

- **CP-1(a):** ACCEPTED. Baggage-ключ `platform.correlation.id` canonical. Устранить расхождение имени span-атрибута (§10).
- **CP-1(b):** ACCEPTED. `X-Correlation-ID` — opt-in boundary bridge, response по умолчанию off; CP-C2 не трогается.
- **CP-1(c):** ACCEPTED с переименованием политики в *birth-time projection* (§10). Механизм option A подтверждён `BaggageSpanProcessor.onStart`.
- **CP-1(d):** analysis-stage → CHANGES REQUIRED (блокирующие корректировки §16, теперь **B1–B6**); после их внесения — **APPROVED (R2)** (см. §21.2/§21.3).
- **CP-1(f):** ACCEPTED. F0 fail-closed + отдельный `RG-IDENTITY-TRUST` до production trusted inbound.

---

## 16. Blocking corrections (только по CP-1(d))

> **Актуальный список — B1–B6 в §21.2** (внесены и утверждены). Ниже — исходные формулировки, приведённые в соответствие с R2.

**B1. Единый источник правды; никакого публичного accessor.**
- Публичного `RequestContextAccessor` не вводить (не в `platform-tracing-api`, не заменяемый приложением). Публичная поверхность чтения — `ActiveTraceContextView.requestId()/correlationId()` поверх единого internal store, в который пишет `openCorrelationScope`. Конкурирующие identity-компоненты — fail-fast.

**B2. Internal OTel-free write/bind seam.**
- `RequestIdentityBinder` + `RequestIdentityScope` — **internal (implementation-плоскость `platform-tracing-core`), НЕ `platform-tracing-api`**; web-доступ через `autoconfigure.support.RequestIdentityBoundarySupport` в `platform-tracing-spring-boot-autoconfigure`. Без `Object`/`Map`-carrier; без application API для мутации requestId.

**B3. Reactive application API поздней установки correlationId.**
- `ReactiveCorrelationOperations` (`Mono`/`Flux`) в `platform-tracing-autoconfigure-webflux` (пакет `space.br1440.platform.tracing.webflux`), поверх Micrometer `ContextRegistry` + Reactor `Context` + `AUTO`. Reactor-типы вне `platform-tracing-api`; immutable String, не OTel `Scope`. **Сигнатуры доказаны spike и зафиксированы CP-1** (§21.4).

**B4. Запрет no-op при disabled; `ThrowingSupplier` вместо `Callable`.**
- «silent no-op» запрещён: если `TraceOperations` присутствует — assignment функционален; при DISABLED identity функциональна, invalid composition → fail-fast.
- `TraceOperations.withCorrelationId(...)` использует `ThrowingSupplier<T>`, не `Callable<T>`.

**B5. Canonical span attribute.**
- `platform.correlation_id`; generic baggage-проекция для `platform.correlation.id` подавляется; дубля `baggage.platform.correlation.id` нет.

**B6. Canonical binding без trim.**
- Binder принимает только canonical correlationId без trim/нормализации (не `RequestIdSupport.sanitizeOrNull`); `IllegalArgumentException` до мутации store.

Точные заменяющие контракты — §21.1/§21.2/§21.3.

---

## 17. Non-blocking improvements

- N1. Исправить javadoc `RequestIdSupport` (называет requestId «correlation id») — `RequestIdSupport.java:10,30`.
- ~~N2. Решить именование span-атрибута проекции~~ — **[R2-C4] повышено до BLOCKING** (см. §10, §16 B5).
- N3. Programmatic correlationId: reject-not-trim (§12), в отличие от trim-and-accept requestId.
- N4. `RequestTraceContextSnapshotSupplier` перевести на чтение из view/store вместо `MDC` (`RequestTraceContextSnapshotSupplier.java:47`) в рамках Slice M.
- N5. Явно задокументировать отсутствие scheduler-адаптера; убрать/пометить строку Scheduler в §4 пакета как future.
- N6. Зафиксировать точные property-имена в M3-review (как и требует §9 пакета).

---

## 18. Slice M entry criteria

Slice M может стартовать при выполнении:
1. Внесены **B1–B6** (§21.2) в редакцию CP-1(d) и зафиксирована committee-резолюция (§21.3). ✔ выполнено.
2. Определён и утверждён write/bind seam (§7) и reactive application API (§21.1) — WebFlux spike исполнен и пройден (§21.4). ✔ выполнено.
3. Обновлён ABI baseline `/abi/platform-tracing-api-core.txt` под новые типы/методы; `AbiSnapshotTest` зелёный; CP-C2 символы байт-в-байт неизменны.
4. Единый store как источник для `ActiveTraceContextView` и snapshot supplier (без чтения MDC как истины).
5. Ограничение зафиксировано: trusted **inbound** business correlation вне scope Slice M (под F0).

---

## 19. Production release gates

- **RG-IDENTITY-TRUST (новый, отдельный):** до production trusted inbound business correlation. Требует: (1) canonical authenticated signal (mTLS workload identity / signed token, не заголовок); (2) verifier + owning-модуль; (3) источник конфигурации, ротация, fail-behavior; (4) точка pre-span/pre-consumer enforcement в Agent-extension; (5) негативные тесты: spoof, missing verifier, verifier exception, ambiguous credentials. (§8.2 пакета п.1-5).
- **RG-CONTROLLED-AGENT (существующий, OPEN):** остаётся отдельным; не смешивать с RG-IDENTITY-TRUST.
- **PRODUCTION ROLLOUT FORBIDDEN** сохраняется до закрытия обоих релевантных gate.

---

## 20. Final committee resolution text

> **СУПЕРСЕДЕД §21.3.** Ниже — исходная analysis-stage резолюция (historical). Авторитетная финальная резолюция — `CP-1 APPROVED (R2)` в §21.3 и в §12 авторитетного packet.

```text
[HISTORICAL / SUPERSEDED BY §21.3]
CP-1 CHANGES REQUIRED
Revision: 3c5f6ba86fec740ca9005bd14343ce84175ffac3
Accepted: CP-1(a), CP-1(b), CP-1(c) [birth-time projection], CP-1(f) [F0 fail-closed]
Rejected sections: d
Blocking corrections (CP-1(d)): B1–B6 — см. §21.2 (актуальная формулировка).
Trust choice: F0 fail-closed; trusted inbound deferred to RG-IDENTITY-TRUST.
```

---

## 21. Ревизия R2 — принятые корректировки и финальный контракт CP-1(d)

По итогам разбора systems-аналитиком приняты 6 корректировок. Как независимый ревьюер **согласен со всеми шестью** — каждая устраняет реальное внутреннее противоречие в предложенных мной контрактах (не в вердикте). Этот раздел **имеет приоритет** над §6/§7/§9/§10/§13/§17 там, где они расходятся.

| # | Корректировка | Статус | Влияние |
|---|---|---|---|
| R2-C1 | `RequestContextAccessor` не может быть одновременно public (в `api`) и internal | ПРИНЯТО | публичный accessor в API **не вводится вовсе** |
| R2-C2 | `RequestIdentityBinder` нельзя помещать в `api` (экспонирует requestId-mutation) | ПРИНЯТО | binder/store — implementation; web — только через boundary support |
| R2-C3 | Reactive API должен быть **поддерживаемым application API**, а не internal-static | ПРИНЯТО | `ReactiveCorrelationOperations` в WebFlux-модуле (`space.br1440.platform.tracing.webflux`); сигнатуры доказаны spike и зафиксированы CP-1 (§21.4) |
| R2-C4 | Конфликт span-атрибутов — **blocking**, не non-blocking | ПРИНЯТО | canonical `platform.correlation_id`; generic baggage-проекция ключа подавляется |
| R2-C5 | Из disabled-матрицы убрать «functional no-op if documented unavailable» | ПРИНЯТО | инвариант: `TraceOperations` present ⇒ assignment functional; иначе fail-fast |
| R2-C6 | Binder не использует `RequestIdSupport.sanitizeOrNull()` (trim-and-accept) | ПРИНЯТО | binder: только canonical, без trim/нормализации, IAE до мутации |

### 21.1 Финальная обязательная архитектура CP-1(d)

- Публичный `RequestContextAccessor` **не добавляется**.
- Публичный `RequestIdentityBinder` **не добавляется**.
- Публичный `RequestIdentityContext` **удаляется**, если для него не найден отдельный прикладной use case (по умолчанию — не вводить).
- Единый **internal immutable per-execution identity store** (implementation/application plane).
- `ActiveTraceContextView` — **единственный публичный read-view** (`requestId()`/`correlationId()` читают store).
- `TraceOperations` — synchronous correlation assignment (`openCorrelationScope` + `withCorrelationId(Runnable)` + `withCorrelationId(ThrowingSupplier)` — **не `Callable`**).
- Internal `autoconfigure` boundary support для requestId/framework-binding (Servlet/WebFlux/Kafka вызывают только его).
- Отдельный **поддерживаемый reactive assignment API** в WebFlux-модуле (`ReactiveCorrelationOperations`, пакет `space.br1440.platform.tracing.webflux`; Reactor-типы вне `platform-tracing-api`; сигнатуры доказаны spike и зафиксированы CP-1).
- Functional identity при `sdk.mode=DISABLED`; при некорректной composition — fail-fast.
- Canonical span attribute — только `platform.correlation_id`; generic baggage-атрибут для этого ключа подавляется.
- `RG-IDENTITY-TRUST` открывается отдельно.

### 21.2 Обновлённый список блокирующих корректировок (замещает §16)

- **B1** — единый источник правды; никакого публичного accessor; публичное чтение только через `ActiveTraceContextView`; custom accessor beans не поддерживаются.
- **B2** — internal write/bind seam (`RequestIdentityScope` + internal `RequestIdentityBinder`) в implementation; web — через `autoconfigure.support.RequestIdentityBoundarySupport`; никакого requestId-mutation в application API.
- **B3** — поддерживаемый reactive assignment API (`ReactiveCorrelationOperations`) в WebFlux-модуле; сигнатуры доказаны spike (§21.4) и зафиксированы CP-1.
- **B4** — запрет silent no-op; инвариант «TraceOperations present ⇒ assignment functional»; `ThrowingSupplier` вместо `Callable`.
- **B5 (новый)** — canonical span attribute `platform.correlation_id`; подавление generic baggage-проекции для `platform.correlation.id`.
- **B6 (новый)** — binder принимает только canonical correlationId без trim/нормализации (не `sanitizeOrNull`); IAE до мутации store.

### 21.3 Финальный текст резолюции (замещает §20)

```text
CP-1 CHANGES REQUIRED
Revision reviewed: 3c5f6ba86fec740ca9005bd14343ce84175ffac3

APPROVED:
  CP-1(a) — platform.correlation.id
  CP-1(b) — opt-in X-Correlation-ID boundary bridge
  CP-1(c) — birth-time projection with immutable parent
  CP-1(f) — F0 fail-closed

CP-1(d) — REVISION REQUIRED:
  - public RequestContextAccessor NOT added
  - public RequestIdentityBinder NOT added
  - public RequestIdentityContext removed unless a distinct application use case exists
  - single internal immutable per-execution identity store
  - ActiveTraceContextView is the only public read view
  - TraceOperations provides synchronous correlation assignment (ThrowingSupplier, not Callable)
  - internal autoconfigure boundary support for requestId/framework binding
  - separate supported reactive assignment API in the WebFlux module
  - functional identity when sdk.mode=DISABLED; invalid composition = fail-fast
  - canonical span attribute = platform.correlation_id; generic baggage attribute for this key suppressed
  - RG-IDENTITY-TRUST opened separately

STATUS:
  SLICE H CLOSED
  CP-1(a,b,c,f) APPROVED
  CP-1(d) REVISION REQUIRED
  SLICE M BLOCKED
  RG-IDENTITY-TRUST OPEN
  RG-CONTROLLED-AGENT OPEN
  PRODUCTION ROLLOUT FORBIDDEN

NEXT LIMITED STEP:
  1) Correct the CP-1 decision packet per CP-1(d) revision above.
  2) Run the WebFlux reactive-API runtime spike only.
  No further large investigation required.
```

### 21.4 Результат WebFlux runtime spike (B3)

Проведён ограниченный исполняемый spike (только WebFlux reactive API), без реализации Slice M. Артефакты — в **test-sources** (не production ABI):
- `platform-tracing-autoconfigure-webflux/src/test/java/.../reactive/spike/ReactiveCorrelationOperations.java` — проверяемая форма API;
- `.../reactive/spike/ReactorCorrelationSupport.java` — реализация поверх Reactor `Context` + Micrometer `ThreadLocalAccessor` (immutable String, без переноса OTel `Scope`);
- `.../reactive/spike/ReactiveCorrelationOperationsSpikeTest.java` — матрица семантики.

Команда: `./gradlew :platform-tracing-autoconfigure-webflux:test --tests "space.br1440.platform.tracing.autoconfigure.reactive.spike.*" --no-daemon` → **BUILD SUCCESSFUL**.

Подтверждено исполнением [REPO]:

| Ось проверки | Результат |
|---|---|
| downstream visibility (`deferContextual`) | ✔ значение видно downstream |
| отсутствие привязки | ✔ пустой контекст, без утечки |
| concurrent subscribers | ✔ изоляция (A/B не смешиваются) |
| retry (ресубскрипция) | ✔ привязка сохраняется |
| repeat | ✔ привязка на всех повторах |
| nested assignment | ✔ LIFO: внутренняя перекрывает только своё поддерево |
| publishOn | ✔ контекст не теряется на смене scheduler |
| subscribeOn | ✔ контекст сохраняется |
| error path (`onErrorResume`) | ✔ значение видно |
| Flux-вариант | ✔ проекция на все элементы |
| тип в Context | ✔ immutable `String`, не `Scope` |
| cancel/timeout | ✔ нет глобального состояния после отмены |
| MDC/ThreadLocal на worker-thread (Micrometer bridge + `Hooks.enableAutomaticContextPropagation`) | ✔ восстанавливается |
| **child-span birth-time projection** (OTel SDK, child создан под поздней привязкой на другом потоке) | ✔ атрибут `platform.correlation_id=biz-777` |
| cleanup ThreadLocal после завершения | ✔ очищается на caller-thread |

**Вывод spike:** форма `ReactiveCorrelationOperations.withCorrelationId(String, Mono<T>/Flux<T>)` семантически корректна и реализуема поверх существующего в репозитории паттерна (Reactor `Context` + Micrometer `ContextRegistry` + automatic context propagation), OTel `Scope` через async-границу не переносится. **Сигнатуры доказаны spike и зафиксированы CP-1** (packet §3.7, пакет `space.br1440.platform.tracing.webflux`); Slice M реализует их без пересмотра формы. Замечание deprecation (`ThreadLocalAccessor.setValue/reset`) — унаследовано от существующего `RemoteServiceContextPropagation`; на production-этапе перейти на `restore/save` API Micrometer.

---

### Приложение: неразрешённые предположения [ASSUMPTION]
- Точное поведение Kafka retry/redelivery для requestId-стабильности не подтверждено кодом (production Kafka identity-адаптер не найден).
- Отсутствие scheduler-адаптера означает, что строка Scheduler в пакете — future, а не текущий факт.

_(Ранее числившееся предположение о необходимости WebFlux-spike снято: spike исполнен и пройден — §21.4.)_
