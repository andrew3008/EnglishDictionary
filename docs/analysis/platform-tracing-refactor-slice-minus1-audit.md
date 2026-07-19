# Platform Tracing Refactor: Slice -1 Current-HEAD Rebase Audit

> Дата: 2026-07-19  
> Режим: READ-ONLY по production-коду, тестам и build-конфигурации  
> Ветка: `feature/runtime-control-hardening`  
> HEAD: `5d1dec26babfeb05db8dd1110e6d69631daef449`  
> План: `C:/Users/andrew/.cursor/plans/platform-tracing_refactor_7a676baf.plan.md`

## 1. Verdict

**GO к Slice 0.** Текущий HEAD подтверждает выбранную topology и bounded scope плана. Повторный blanket-refactor `platform-tracing-core`, массовый move в `core.policy/core.adapter` и создание `platform-tracing-otel-runtime`/`platform-tracing-policy` не обоснованы.

Slice -1 не менял production-код, тесты и Gradle. Рабочее дерево перед аудитом было чистым; локальный HEAD совпадал с `english-dict/feature/runtime-control-hardening`.

Две корректировки к prefilled status matrix плана:

1. `ALIGN-11 / RISK-11` переведён из `UNVERIFIED` в **ALREADY CLOSED (verify-only)** для заявленного plan-scope fail-closed runtime control: commit `809ce49` добавил startup mutation policy, rejection до applier и unit/e2e evidence. Внутренний `Map<String,Object>`/string-violation pressure point остаётся известным, но сам по себе не разрешает ещё один control refactor.
2. `RISK-15` закрыт: ранее staged `SemconvKeys` и baseline-файл вошли в commit `5d1dec2`; на старте аудита worktree чист. Изменение `SemconvKeys` комментарий-only и не меняет ключи/ABI.

## 2. Repository Baseline

| Проверка | Результат |
|----------|-----------|
| Current branch | `feature/runtime-control-hardening` |
| HEAD | `5d1dec26babfeb05db8dd1110e6d69631daef449` |
| Remote branch | `english-dict/feature/runtime-control-hardening` на том же commit |
| Worktree | clean |
| Core production Java files | 91 |
| Public top-level core types | 68 |
| Legacy `TracingImplementation` production types | отсутствуют |
| Production `..impl..` packages | отсутствуют |
| `core.utils` accidental-public helpers | 5 файлов, подтверждены |
| API OTel imports | 3 импорта в 2 propagation-control файлах |
| API OTel build dependency | `compileOnly opentelemetry-context`, подтверждена |
| Autoconfigure implementation leak | `api project(':platform-tracing-core')`, подтверждён |

Документ `platform-tracing-core-architecture-discovery.md` является evidence snapshot commit `809ce49`, а не текущего HEAD. Его факты по production architecture остаются применимы, но упоминания staged/unstaged файлов исторические и не описывают состояние `5d1dec2`.

## 3. Status Matrix at HEAD

| ID | Final status | Evidence / minimal delta | Owner |
|----|--------------|--------------------------|-------|
| API-02 / RISK-01 | **DELETE** | `OtelTraceparentReaders` статически вызывает `ServiceLoader`; удалить holder после Spike A proof | C1 |
| API-02 `fromTraceparent` | **BEHAVIOR CHANGE REQUIRED** | API/core builders вызывают static reader discovery; убрать builder path, оставить explicit `SpanFactory` composition | C1 |
| API-01 / ALIGN-03 OTel in API | **MOVE-ONLY** | `TraceControlHeaderInjector` и `PlatformTraceContextKeys` дают 3 OTel imports; move только после CP-C2 exact ABI | C2 |
| API-01 Gradle metadata | **DELETE** | удалить API `compileOnly opentelemetry-context` после C2 move | C2 |
| Gradle leak | **BEHAVIOR CHANGE (build)** | autoconfigure `api core` должен стать `implementation`; published-metadata fixture обязателен | C3 |
| IDENT-3 | **BEHAVIOR CHANGE REQUIRED** | `DefaultActiveTraceContextView.correlationId()` возвращает `Optional.empty()`; `requestId()` отсутствует | M |
| IDENT-1 / IDENT-5 | **BEHAVIOR CHANGE REQUIRED** | `requestIdKey="correlation_id"`, legacy MDC key/read path подтверждены | M |
| ALIGN-10 / RISK-06 | **BEHAVIOR CHANGE REQUIRED** | process-global unbounded `ConcurrentHashMap`, cleanup remove-only | H |
| ALIGN-04 / API-04 / RISK-03 | **BEHAVIOR CHANGE REQUIRED** | `DefaultSpanFactory.fromSpec()` достигает `setAllAttributes` без `AttributePolicy`; semantic builders используют policy | D |
| ALIGN-12 / ALIGN-01 public surface | **DELETE** | пять `core.utils.*Utils` существуют и являются accidental public surface | B |
| ALIGN-02 | **ALREADY CLOSED (verify-only)** | `TracingRuntime`, `OtelTracingRuntime`, package/dependency ArchUnit существуют; не переписывать | E |
| ALIGN-05 / API-03 / RISK-02/13 | **UNVERIFIED** | lifecycle уже переработан, но same-thread/create-activate-close residual требует Slice 0 characterization | F conditional |
| ALIGN-06 | **UNVERIFIED** | `TracingState` существует; residual facade-local state требует characterization | F conditional |
| ALIGN-07 | **KEEP AS-IS + CP-2** | sampling packages/characterization существуют; repackaging запрещён | G gate-only |
| ALIGN-09 | **UNVERIFIED** | `SemanticSpanSpecs` уже отделён; multi-responsibility evidence без behavioral defect не разрешает decomposition | D conditional |
| ALIGN-11 / RISK-11 | **ALREADY CLOSED (verify-only)** | decode/domain/mutation-policy/apply fail-closed pipeline и tests присутствуют; не переоткрывать PR-B | E gate-only |
| ALIGN-08 | **BEHAVIOR CHANGE REQUIRED** | mixed static propagation path подтверждён; bounded C1/C2 delta | C1/C2 |
| ALIGN-01 mass repackage | **KEEP AS-IS** | 24 смысловых production packages и ArchUnit уже существуют; broken edge для 91-file move не доказан | I reduced |
| RISK-10 | **KEEP AS-IS (verify)** | real forked-JVM `ClassLoaderVisibilityE2ETest`, packaged extension tasks и SPI checks существуют | Slice 0/J |
| RISK-15 | **ALREADY CLOSED** | concurrent files committed in `5d1dec2`, worktree clean, Semconv change comment-only | Slice 0 snapshot check |

## 4. Existing Fitness and Characterization Evidence

Подтверждены существующие gates:

- `pr1ModuleTaxonomyVerify`, `pr4ArchitectureFitnessVerify`;
- API/core/web/autoconfigure/extension ArchUnit suites;
- `CoreRuntimeVersionedArchTest`, `FacadeOtelIsolationArchTest`, `CoreMdcRemoteArchTest`, `CorePolicyPackagePurityArchTest`;
- core `SpanEnricherV3CharacterizationTest` и `DefaultTraceOperationsBaselineTest`;
- extension processor/sampler/scrubbing characterization suites;
- `agentExtensionJar`, `verifyAgentJarContents`, `verifyExtensionSpiRegistration`;
- real child-JVM `ClassLoaderVisibilityE2ETest` with `-javaagent`.

Fresh green execution **не заявляется** в Slice -1: это задача Slice 0.

## 5. Slice 0 Gap Inventory

На HEAD отсутствуют два именованных deliverable плана:

- `AbiSnapshotTest`;
- `PackagedAgentClassloaderCharacterizationTest`.

При этом `ClassLoaderVisibilityE2ETest` уже реализует реальный packaged-agent child-JVM probe и должен быть переиспользован/расширен, а не продублирован новым фиктивным classloader test.

Инфраструктура `knownDefectTest` существует в core/autoconfigure, но:

- descriptions всё ещё называют тесты expected-red;
- `r01-red`/`known-defect` test tags в Java tests не найдены;
- Slice 0 должен перевести модель в green characterization, добавить defect ID + owner и запретить orphan tags.

Mode-detection inventory подтверждён: существуют `OtelAgentDetector`, `SdkMode`, `SdkModeResolver`, property `platform.tracing.sdk.mode` и unit/context tests. Slice 0 фиксирует current behavior; архитектурный PASS выдаёт только Spike A.

## 6. Guardrails for Subsequent Work

1. Не выполнять production changes до завершения Slice 0 и Spike A gates.
2. Не переоткрывать control-protocol/runtime-mutation architecture commits `4373cdf`/`809ce49`; только verification.
3. Не выполнять mass core repackage и не создавать отклонённые модули.
4. C2 остаётся blocked до CP-C2 exact signatures + multi-transport evidence.
5. M остаётся blocked до CP-1(a-d,f), F verify-or-implement gate и H.
6. J остаётся blocked до C3 publication verification и M completion.

## 7. Completion

Slice -1 завершён: current-HEAD evidence зафиксирован, stale statuses скорректированы, conditional scopes сохранены, production-код не изменён.

**Next action:** Slice 0 baseline/characterization. Начать с green test infrastructure и ABI snapshot, затем выполнить canonical packaged-agent gate. Любой residual behavioral defect сначала получает green characterization test и owner slice; реализация до этого запрещена.
