# Control Protocol PR #13 Post-Audit

> Аудитор: Opus (Principal Java Platform Architect / adversarial post-implementation reviewer)
> Дата: 2026-07-17
> Режим: READ-ONLY. Продакшен-код и тесты не менялись, коммитов/пушей не делалось.
> Метод верификации: отдельный detached git worktree на `english-dict/feature/runtime-policy-control-handler` (tip `ac23b77`), без изменения рабочего дерева master.

---

## 1. Executive Verdict

**FAIL** (не готов к merge as-is).

Уточнение: ядро реализации (production main-код `platform-tracing-api` / `platform-tracing-core` / `platform-tracing-otel-extension`) **архитектурно полностью соответствует плану** `control_protocol_schema_validation_d7627eff.plan.md` (Option B). Возражение комитета («удаление валидации схемы») закрыто корректно: схемная валидация сохранена и усилена, удалена только публичная интроспекция.

Однако PR **не собирается** в нескольких test/consumer source-set из-за незамигрированных ссылок на удалённый API. Заявление Perplexity «75 tests pass / ADR checklist PASS» этим опровергается: соответствующие исходники не компилируются, а значит зелёного прогона в текущем состоянии tip быть не может.

---

## 2. What Was Audited

| Поле | Значение |
|---|---|
| Branch | `feature/runtime-policy-control-handler` (remote `english-dict`) |
| Tip commit | `ac23b77 docs: add control-protocol wire spec v1 and update PR description` |
| Commits ahead of `master` | 38 |
| Base plan | `control_protocol_schema_validation_d7627eff.plan.md` |
| PR метаданные (#13, число коммитов) | `INSUFFICIENT_EVIDENCE` — `gh` не установлен, GitHub API недоступен; верифицировано содержимое ветки напрямую |
| `Perplexity_Implementation_Plan.md` | `INSUFFICIENT_EVIDENCE` — файл с таким именем в дереве не найден; заявления верифицированы по коду |

Локальный `master` при этом остаётся в pre-implementation dual-state (и flat-, и legacy-подпакеты присутствуют); вся работа PR #13 живёт только в ветке.

---

## 3. Plan Compliance Matrix

| # | Требование плана | Доказательство | Результат |
|---|---|---|---|
| A1 | Фасад: только `current()`, `version()`, `decode(Map)` | `TracingControlProtocol.java` — ровно 3 public-метода; ArchUnit `exactPublicMethodSet` | PASS |
| A2 | Убрана публичная интроспекция (`schema()`, `validator()`, public `Schema`, legacy validator) | Методов нет; `TracingControlProtocolSchema` — `final class` (package-private) | PASS |
| A3 | `READ_SCHEMA` удалён (enum/ключ/доки/тесты) | Enum из 3 значений; в `Keys` нет `OPERATION_READ_SCHEMA`; wire-spec §1 явно отказывает; arch-тест `operationEnumHasThreeValues` | PASS |
| A4 | Legacy-подпакеты `schema/ validation/ result/ version/` удалены | `git ls-tree` по main flat-пакету: 14 файлов, подпакетов нет | PASS |
| A5 | Точная public surface = 7 типов | `Protocol, Version, Operation, Keys, DecodeResult, Violation, ViolationCode` — все `public`; остальные 7 package-private | PASS |
| A6 | Impl-хелперы package-private | `Decoder, Schema(+RequestSchema), FieldDescriptor, FieldType, FieldTypeSupport, ContractVersionValidator, RouteRatiosNormalizer` — все без `public` | PASS |
| A7 | Контракт `DecodeResult` (инварианты, `failure` без payload, `OPERATION_NOT_ALLOWED` вместо `UNKNOWN_OPERATION`) | `TracingControlProtocolDecodeResult` compact-ctor; `failure(...)` кладёт `Map.of()`; кода `UNKNOWN_OPERATION` нет | PASS |
| A8 | Operation-first схемы; READ отклоняет policy-ключи; unknown/non-string/enum отклоняются; routeRatios shape в API | `Schema.v1()` READ = envelope+diagnostics; `Decoder` + `FieldTypeSupport` + `RouteRatiosNormalizer` | PASS |
| A9 | Domain-валидация в core (empty-mutation, mode allowlist, ratio bounds, core-owned коды) | `RuntimePolicyControlDomainValidator` + `ValidationModePolicyValidator` + делегирование в `SamplingPolicyPropertiesValidator`; **но empty-mutation precondition отсутствует** | PARTIAL (см. P1-1) |
| A10 | Потребители мигрированы | Production MBean/handler/applier — да; **`WireRoundTripTestMBeanImpl` и `SamplingControlClientWireContractTest` — НЕТ** | FAIL (см. P0-1) |
| A11 | Apply только после valid decode И valid domain | `RuntimePolicyControlHandler.handle()` шаги 1–4; READ short-circuit; applier defensive-guard | PASS |
| A12 | JMX/MBeans: 7 бинов, batch+rollback, classloader-neutral Map-граница, без OpenMBean в api | `PlatformTracingJmxRegistrar.registerAllOrRollback()` (7 бинов, rollback); `PlatformControlProtocolMBean` держит OpenMBean только в otel | PASS |
| A13 | Wire-spec doc | `docs/control-protocol-wire-spec-v1.md`: 3 операции, 21 ключ, pipeline, без READ_SCHEMA | PASS (мелкие drift — P2) |
| A14 | ArchUnit-правила | `ApiProtocolNoImplementationDependencyArchTest` + `ApiProtocolPackagePurityArchTest` | PASS |
| A15 | Тесты (decoder/negative/domain/e2e/mbean/wiring/golden) | Присутствуют обширно, **но часть test source-set не компилируется**; golden-wire spec-as-test не найден | FAIL (см. P0-1, P2-4) |

---

## 4. Perplexity Claim Verification

| Заявление | Доказательство | Результат |
|---|---|---|
| 20+ коммитов на ветке | 38 коммитов ahead of master | PASS |
| PR #13 существует | `gh` недоступен | INSUFFICIENT_EVIDENCE |
| `RuntimePolicyControlHandler` | `core/control/protocol/RuntimePolicyControlHandler.java` | PASS |
| `RuntimePolicyApplier` SPI | `core/control/protocol/RuntimePolicyApplier.java` (interface) | PASS |
| `JmxRuntimePolicyApplier` | `otel/extension/control/JmxRuntimePolicyApplier.java` | PASS |
| `ReadAppliedStateHandler` | `otel/extension/control/ReadAppliedStateHandler.java` | PASS |
| `PlatformControlProtocolMBean` | `otel/extension/jmx/control/PlatformControlProtocolMBean.java` (+ MXBean) | PASS |
| `PlatformTracingJmxRegistrar.setControlHandler()` | строка 139 | PASS |
| 7-й MBean в batch-wiring | `registerAllOrRollback` регистрирует sampling/scrubbing/validation/export/processorMetrics/diagnostics/controlProtocol | PASS |
| `PlatformSpanProcessorFactory.wireControlProtocolHandler()` | строки 119/177/192 | PASS |
| `docs/control-protocol-wire-spec-v1.md` | присутствует | PASS |
| «75 tests exist and run (pass)» | Тесты существуют; **компиляция test-source падает** → «pass» опровергнуто | FAIL |

---

## 5. Public API Surface Audit

7 public-типов ровно по whitelist; 7 impl-типов package-private. `TracingControlProtocol` имеет ровно `current/version/decode` (ArchUnit `containsExactlyInAnyOrder`). Lombok в protocol-пакете отсутствует (grep `@Getter|@Setter|@Accessors|@UtilityClass|@RequiredArgsConstructor|lombok` → 0). Соответствует плану.

---

## 6. DecodeResult Contract Audit

`TracingControlProtocolDecodeResult` (record) в compact-конструкторе принудительно проверяет:
- `valid && operation.isEmpty()` → исключение;
- `valid && !violations.isEmpty()` → исключение;
- `!valid && violations.isEmpty()` → исключение;
- `!valid && !normalizedPayload.isEmpty()` → исключение;
- `normalizedPayload` оборачивается в `unmodifiableMap(new LinkedHashMap<>())` → **иммутабелен**;
- `failure(...)` физически кладёт `Map.of()` — payload сохранить нельзя;
- `success(...)` требует non-null operation.

Кода `UNKNOWN_OPERATION` нет; неизвестная операция → `OPERATION_NOT_ALLOWED` (`Decoder.decodeOperation`). Полное соответствие плану.

---

## 7. Schema Validation Retention Audit

Схемная валидация **сохранена и усилена**, удалена только интроспекция:
- internal `TracingControlProtocolSchema.v1()` (package-private) — единственный source-of-truth, operation-first (`APPLY`/`VALIDATE` = envelope+runtimePolicy+diagnostics; `READ` = envelope+diagnostics);
- строгий unknown-key reject (`UNKNOWN_KEY`), non-String ключи (`TYPE_MISMATCH`), enum-инстансы (`TYPE_MISMATCH`), missing required (`MISSING_REQUIRED_KEY`), версия (`UNSUPPORTED_VERSION`/`INVALID_VALUE`);
- open-type нормализация в `FieldTypeSupport` (Long→Integer, Int/Long/Float→Double, String[]), routeRatios shape в `RouteRatiosNormalizer`;
- **никаких доменных правил в API**: `ratioBounded()` и `VALIDATION_MODES` в main-исходниках отсутствуют (grep по main = 0; см. P0-1 про тест).
- Ключей ровно **21**, топологических ключей нет.

Возражение комитета закрыто.

---

## 8. Domain Validation / Apply Safety Audit

- Pipeline `decode → domain validate → apply` реализован в `RuntimePolicyControlHandler.handle()`: при `!decode.valid()` — `decodeRejected`; READ short-circuit; при `!domain.valid()` — `domainRejected`; apply только когда оба valid.
- `JmxRuntimePolicyApplier` — defensive guard на не-APPLY операцию; partial-update семантика (отсутствующие ключи не трогаются).
- Domain-нарушения — core-owned (`TracingControlDomainValidationResult` со списком строк), не маскируются под wire `TYPE_MISMATCH`.
- Ratio/route bounds делегированы в `SamplingPolicyPropertiesValidator`; validation.mode — `ValidationModePolicyValidator` (allowlist `{LOG_ONLY, STRICT}` + cross-field mode↔strict).

**Отклонение (P1-1):** плановый **empty-mutation precondition отсутствует**. `RuntimePolicyControlDomainValidator.validate()` не отклоняет APPLY без единого runtime-policy ключа; `toSamplingPolicyProperties` подставляет `defaultRatio=1.0` по умолчанию, а `JmxRuntimePolicyApplier` при отсутствии полей делает no-op. Итог: пустой APPLY → `SUCCESS` (no-op), а не domain-invalid, как требовал Slice 4 плана и red-team.

---

## 9. JMX / MBean / E2E Audit

- `PlatformControlProtocolMBean.applyPolicy(CompositeData)` → `compositeToMap` → `decoder.decode(wire)` → `handler.handle(decoded)`. Raw `Map` существует только на JMX-границе до decode — это разрешённое планом исключение; apply достижим только через handler.
- OpenMBean-типы (`CompositeData`, `SimpleType`, ...) остаются в `otel-extension`, в `api.control.protocol` их нет (arch-правило `API_PROTOCOL_NO_OPENMBEAN`).
- Registrar: 7 бинов, all-or-rollback, `unregisterAllMBeans`, идемпотентная перерегистрация.
- **E2E harness `WireRoundTripTestMBeanImpl` НЕ мигрирован** (см. P0-1) — импортирует удалённые `result.*`/`validation.*`, использует `current().validator()`.

---

## 10. Architecture Fitness / ArchUnit Audit

`ApiProtocolNoImplementationDependencyArchTest` (reflection/ClassFileImporter) закрывает: точный public-method set, отсутствие `schema()/validator()/find()/min/maxSupportedVersion`, whitelist 7 типов (subset + containsAll), отсутствие legacy-подпакетов и `api.control.wire`, 6 violation-кодов, 3 операции без `READ_SCHEMA`, инварианты record. `ApiProtocolPackagePurityArchTest` закрывает JDK-only, no-implementation-modules, no-OpenMBean, unified naming. Правила соответствуют §12 плана. Замечание: сами arch-тесты компилируются, но их прогон блокирован общей ошибкой сборки (P0-1/P0-2).

---

## 11. Docs / Wire Spec Audit

`docs/control-protocol-wire-spec-v1.md` — сильный документ: 3 операции, 21 ключ (envelope 3 + sampling 7 + scrubbing 3 + validation 3 + feature-flags 3 + diagnostics 2), pipeline decode→domain→apply, явный отказ от `READ_SCHEMA`, таблица violation-кодов, JMX open-type mapping. Расхождения (P2):
- §7 READ-ответ заявляет `contractVersion:Integer` и типизированные значения, но `ReadAppliedStateHandler.read()` не кладёт `contractVersion`, а `mapToCompositeData` приводит все значения к String.
- §9 утверждает, что отсутствующий `contractVersion` → `UNSUPPORTED_VERSION`, тогда как decoder выдаёт `MISSING_REQUIRED_KEY` (и `INVALID_VALUE` для нечислового).

---

## 12. Verification Commands

| Команда | Результат | Примечание |
|---|---|---|
| `:platform-tracing-api:compileJava` (worktree) | FAIL | `illegal character: '\ufeff'` в `KafkaTracing.java`, `OperationSpanBuilder.java` (span-пакет, вне scope протокола) — блокирует всю сборку локально |
| `:platform-tracing-api:compileTestJava` | Не достигнуто | заблокировано ошибкой main-компиляции выше |
| grep удалённого API по `*.java` | Найдено 4 «живых» нарушителя | см. P0-1 (плюс закономерные упоминания в arch-тестах/доках) |
| grep Lombok в protocol-пакете | 0 совпадений | PASS |
| `git ls-tree` flat-пакет | 14 файлов, без подпакетов | PASS |
| BOM-проверка (`ReadAllBytes`) | `EF BB BF` на диске в master и в ветке; в committed-blob через `git show` BOM не виден | см. P0-2 |

> Полный `build` / `test` / `javadoc` / `pr4ArchitectureFitnessVerify` в данном окружении выполнить не удалось: сборка падает на BOM в span-файлах ещё до control-protocol модулей. Поэтому compile-провалы протокольных test-source доказаны **статически** (ссылки на несуществующие типы/методы), а не прогоном.

---

## 13. Findings

### P0 — blockers

**P0-1. Незамигрированные test/consumer-исходники ссылаются на удалённый API → компиляция падает.** Ветка удалила подпакеты `schema/ validation/ result/ version/` и методы `schema()/validator()/find()/min|maxSupportedVersion()`, но 4 файла всё ещё их используют:
- `platform-tracing-api/src/test/.../control/protocol/TracingControlProtocolTest.java` — импортирует `version.*`, `schema.*`, `validation.TracingControlProtocolValidator`; вызывает `minSupportedVersion()`, `maxSupportedVersion()`, `isSupported()`, `find()`, `schema()`, `validator()`.
- `platform-tracing-api/src/test/.../control/protocol/TracingControlProtocolFieldTypeTest.java` — вызывает `TracingControlProtocolFieldType.ratioBounded()`, которого нет во flat-enum.
- `platform-tracing-spring-boot-autoconfigure/src/test/.../sampling/SamplingControlClientWireContractTest.java` (line 89, `WireEvaluateStub`) — `TracingControlProtocol.current().validator().validateRuntimePolicy(...)`.
- `platform-tracing-e2e-tests/src/jmxWireExtension/.../wire/WireRoundTripTestMBeanImpl.java` — импортирует `result.TracingControlProtocolValidationResult`, `result.TracingControlProtocolViolation`, `schema.TracingControlProtocolKeys`, `validation.TracingControlProtocolValidator`; поле `current().validator()`.

Следствие: `:platform-tracing-api:compileTestJava`, `:platform-tracing-spring-boot-autoconfigure:compileTestJava` и e2e `jmxWireExtension` source-set не компилируются. Заявление «75 tests pass» в текущем tip недостоверно.

**P0-2. UTF-8 BOM (`EF BB BF`) в начале `platform-tracing-api/.../span/builder/KafkaTracing.java` и `OperationSpanBuilder.java`** ломает `javac` (`illegal character: '\ufeff'`) и блокирует сборку всего модуля. BOM присутствует на диске и в master, и в ветке (в `git show` через PowerShell визуально скрыт). Вне scope протокольного рефакторинга, вероятно pre-existing/побочный эффект патча, но без устранения ни собрать, ни прогнать тесты нельзя.

### P1 — must fix before merge

**P1-1. Отсутствует empty-mutation precondition (плановый Slice 4 / red-team).** `RuntimePolicyControlDomainValidator` не отклоняет APPLY без runtime-policy ключей; пустой APPLY завершается `SUCCESS` (no-op). План требовал domain-invalid.

### P2 — should fix before release

- **P2-1.** Wire-spec §7 vs `ReadAppliedStateHandler`/`mapToCompositeData`: `contractVersion` в READ-ответе не выдаётся; значения приводятся к String вопреки заявленным типам.
- **P2-2.** Wire-spec §9: missing `contractVersion` документирован как `UNSUPPORTED_VERSION`, фактически `MISSING_REQUIRED_KEY`.
- **P2-3.** Allowlist validation.mode = `{LOG_ONLY, STRICT}` (в плане иллюстративно был `{STRICT, WARN, DISABLED}`). Реализация согласована с `ValidationSnapshot` и приемлема — зафиксировать в ADR, чтобы снять расхождение с текстом плана.
- **P2-4.** `GoldenWireContractTest` (spec-as-test из Slice 6 / S-4 плана) в дереве ветки не найден — либо реализовать, либо явно пометить как deferred follow-up.

---

## 14. Required Fixes

**P0-1 (Codex-fixable):**
- `TracingControlProtocolTest.java`: переписать под flat-API — импорт `...protocol.TracingControlProtocolVersion`; удалить проверки `min/maxSupportedVersion/isSupported/find/schema/validator`; заменить на `current()/version()/decode(...)` инварианты (или удалить файл, т.к. покрытие уже есть в `ApiProtocolNoImplementationDependencyArchTest`).
- `TracingControlProtocolFieldTypeTest.java`: удалить (enum больше не несёт `ratioBounded()`); bounds покрыты core-тестами.
- `SamplingControlClientWireContractTest.java`: `WireEvaluateStub.evaluateWirePayload` перевести на `TracingControlProtocol.current().decode(payload)` + маппинг `decodeResult.valid()/violations()`.
- `WireRoundTripTestMBeanImpl.java`: убрать импорты `result.*`/`validation.*`/`schema.*`; заменить `validator().validateRuntimePolicy` на `current().decode(...)`, добавить шаг `RuntimePolicyControlDomainValidator.validate(decodeResult.normalizedPayload())` перед формированием ответа (см. E-4 плана).

**P0-2:** пересохранить `KafkaTracing.java` и `OperationSpanBuilder.java` в UTF-8 **без BOM** (и проверить весь `span/builder/` grep’ом на `\ufeff`).

**P1-1 (Codex-fixable):** в `RuntimePolicyControlDomainValidator.validate` добавить: если operation-контекст = мутирующий и в payload нет ни одного runtime-policy ключа → добавить violation «empty mutation rejected». (Либо проверять в `RuntimePolicyControlHandler` до domain-шага.)

**P2:** привести wire-spec §7/§9 в соответствие с `ReadAppliedStateHandler`/`ContractVersionValidator`; зафиксировать mode-allowlist в ADR; добавить `GoldenWireContractTest` или пометить deferred.

---

## 15. Final Recommendation

**not ready** (близко к «ready after fixes»).

Архитектурное ядро реализовано на высоком уровне и полностью снимает возражение комитета о «валидации схемы». Блокеры локальны и механически исправимы: 4 незамигрированных test/consumer-файла (P0-1), BOM в двух span-файлах (P0-2) и отсутствующий empty-mutation precondition (P1-1). После их устранения и зелёного прогона `build` + `pr4ArchitectureFitnessVerify` PR можно переводить в «ready to merge».

---

## 16. Codex Fix Prompt

```
You are fixing PR #13 (branch feature/runtime-policy-control-handler) so it compiles and matches the approved plan. Do NOT change the approved production architecture of platform-tracing-api/src/main control/protocol. Scope is strictly the findings below.

CONTEXT: The flat package space.br1440.platform.tracing.api.control.protocol no longer has subpackages schema/validation/result/version, and TracingControlProtocol exposes ONLY current(), version(), decode(Map). Domain validation lives in platform-tracing-core.

TASK 1 (P0 — make test/consumer sources compile):
1. platform-tracing-api/src/test/.../control/protocol/TracingControlProtocolTest.java — rewrite against the flat API: import TracingControlProtocolVersion from the root protocol package; REMOVE all assertions using minSupportedVersion(), maxSupportedVersion(), isSupported(), find(), schema(), validator(); keep only current()/version()/decode() behavior. If nothing meaningful remains beyond ArchUnit coverage, delete the file.
2. platform-tracing-api/src/test/.../control/protocol/TracingControlProtocolFieldTypeTest.java — DELETE it (flat TracingControlProtocolFieldType has no ratioBounded(); ratio bounds are core-domain).
3. platform-tracing-spring-boot-autoconfigure/src/test/.../sampling/SamplingControlClientWireContractTest.java — in WireEvaluateStub.evaluateWirePayload replace current().validator().validateRuntimePolicy(payload) with TracingControlProtocol.current().decode(payload); map result.valid() and result.violations().size().
4. platform-tracing-e2e-tests/src/jmxWireExtension/.../wire/WireRoundTripTestMBeanImpl.java — remove imports of protocol.result.* / protocol.validation.* / protocol.schema.*; replace the validator field and validateRuntimePolicy call with the full pipeline: TracingControlProtocolDecodeResult r = TracingControlProtocol.current().decode(payload); if invalid return violations; else run RuntimePolicyControlDomainValidator.validate(r.normalizedPayload()); never call apply in this harness.

TASK 2 (P0 — encoding): re-save platform-tracing-api/src/main/java/.../span/builder/KafkaTracing.java and OperationSpanBuilder.java as UTF-8 WITHOUT BOM; grep the whole span/builder package for a leading U+FEFF and strip any found.

TASK 3 (P1 — empty mutation): in platform-tracing-core RuntimePolicyControlDomainValidator.validate(Map), reject a mutating payload that carries no runtime-policy key (only envelope/diagnostics) with a domain violation "empty mutation rejected"; add a core unit test empty APPLY -> domain-invalid.

TASK 4 (P2 — docs): reconcile docs/control-protocol-wire-spec-v1.md §7 (READ response: no contractVersion, values are String-coerced) and §9 (missing contractVersion -> MISSING_REQUIRED_KEY, not UNSUPPORTED_VERSION) with the actual code.

VERIFY: :platform-tracing-api:compileTestJava, :platform-tracing-spring-boot-autoconfigure:compileTestJava, e2e jmxWireExtension compile, then :platform-tracing-api:test :platform-tracing-core:test and pr4ArchitectureFitnessVerify pr1ModuleTaxonomyVerify are green. Do not weaken any ArchUnit rule. Do not reintroduce schema()/validator()/READ_SCHEMA or legacy subpackages.
```
