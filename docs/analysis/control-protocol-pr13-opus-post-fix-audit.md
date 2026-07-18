# Control Protocol PR #13 Post-Fix Audit

> Аудитор: Opus (Principal Java Platform Architect / adversarial post-fix auditor)
> Дата: 2026-07-18
> Режим: READ-ONLY. Продакшен-код и тесты не менялись, коммитов/пушей не делалось.
> Предыдущий аудит: `docs/analysis/control-protocol-pr13-opus-post-audit.md` (вердикт FAIL).
> Область: только проверка закрытия известных находок (P0-1, P0-2, P1-1, P2-1…P2-4). Широкий архитектурный ре-ревью и решение Option B не пересматриваются.

---

## 1. Executive Verdict

**PASS_WITH_WARNINGS**

Все блокеры предыдущего аудита закрыты, и запускаемые compile/test/arch-гейты зелёные (`build` = BUILD SUCCESSFUL). Архитектура не деградировала. Остаются два НЕблокирующих предупреждения процессного/окруженческого характера (не по коду):

1. **Исправления не закоммичены и не запушены.** Правки существуют только как uncommitted-изменения рабочего дерева локального checkout ветки `feature/runtime-policy-control-handler`. Remote-tip ветки по-прежнему `ac23b77` (pre-fix). PR #13 в его опубликованном состоянии всё ещё содержит старые дефекты — merge возможен только после коммита и пуша этих правок.
2. **e2e wire round-trip тест не исполняется в этом окружении** (`:platform-tracing-e2e-tests:test` → SKIPPED, opt-in gate). Гарнесс мигрирован и **компилируется**, но runtime-прогон e2e здесь не выполнялся → для e2e-runtime — `INSUFFICIENT_EVIDENCE`.

---

## 2. Audit Scope

| Поле | Значение |
|---|---|
| Branch | `feature/runtime-policy-control-handler` |
| Remote tip | `ac23b77` (не сдвинулся с предыдущего аудита) |
| Аудируемое состояние | рабочее дерево локального checkout ветки с uncommitted-фиксами (33 изменённых/удалённых файла + 1 новый тест) |
| Fix commit range | отсутствует — фиксы не закоммичены (working-tree only) |
| Метод | чтение исходников на диске + byte-точный BOM-скан + grep-скан удалённого API + прогон Gradle-гейтов в основном workspace |

Примечание: в дереве присутствует также `docs/analysis/control-protocol-pr13-post-fix-audit.md` (сторонний post-fix отчёт); настоящий отчёт — независимая проверка Opus.

---

## 3. Closure Matrix

| Находка | Ожидаемый фикс | Доказательство | Результат |
|---|---|---|---|
| **P0-1** stale-ссылки на удалённый API в 4 test/consumer файлах | миграция/удаление | `TracingControlProtocolTest` переписан под `current()/version()/decode()`; `TracingControlProtocolFieldTypeTest` удалён (`git status` = D); `SamplingControlClientWireContractTest.WireEvaluateStub` → `current().decode(payload)`; `WireRoundTripTestMBeanImpl` → decode→domain-validate. grep удалённого API по api/core/autoconfigure/e2e = только негативные assert’ы в arch-тесте | FIXED |
| **P0-2** UTF-8 BOM в `KafkaTracing.java`, `OperationSpanBuilder.java` | пересохранить без BOM | byte-скан всех `*.java` → 0 файлов с `EF BB BF`; `:platform-tracing-api:compileJava` проходит; `build` SUCCESSFUL | FIXED |
| **P1-1** empty-mutation принимается как no-op success | core-domain отклонение APPLY без mutation-ключей | `RuntimePolicyControlDomainValidator.collectEmptyMutationViolation` (only APPLY, allowlist из 16 mutation-ключей) → violation `"empty mutation rejected"`; тест `emptyApplyMutationIsRejectedByDomainValidator`; handler-тесты подтверждают `applier.appliedPayloads.isEmpty()` при DOMAIN_REJECTED | FIXED |
| **P2-1** wire-spec §7 READ-ответ заявлял `contractVersion` и типы | привести к реализации | §7 переписан: `contractVersion` не возвращается; все значения String-coerced; список ключей соответствует `ReadAppliedStateHandler` | FIXED |
| **P2-2** wire-spec §9 версия-коды | MISSING/INVALID/UNSUPPORTED корректно | §3 и §9: missing → `MISSING_REQUIRED_KEY`, unparsable → `INVALID_VALUE`, иной numeric major → `UNSUPPORTED_VERSION`; совпадает с `ContractVersionValidator` + `Decoder` | FIXED |
| **P2-3** mode-allowlist расхождение | согласовать доку и код | код `ValidationModePolicyValidator` = `{LOG_ONLY, STRICT}`; wire-spec §6.3 = `"STRICT","LOG_ONLY"` — согласовано | FIXED |
| **P2-4** GoldenWireContractTest отсутствовал | реализовать или явно отложить | добавлен `GoldenWireContractTest` (apply-пример декодируется; READ отклоняет policy-ключ `UNKNOWN_KEY`; missing contractVersion → `MISSING_REQUIRED_KEY`) — зеркалит примеры wire-spec | FIXED |

---

## 4. Public API Regression Check

Без регресса:
- `TracingControlProtocol` — по-прежнему только `current()/version()/decode(Map)` (arch-тест `exactPublicMethodSet` зелёный).
- `schema()` / `validator()` отсутствуют; `READ_SCHEMA` отсутствует (enum = 3).
- Public top-level типы = ровно 7 (whitelist в `ApiProtocolNoImplementationDependencyArchTest` не изменён): `TracingControlProtocol, TracingControlProtocolVersion, TracingControlProtocolOperation, TracingControlProtocolKeys, TracingControlProtocolDecodeResult, TracingControlProtocolViolation, TracingControlProtocolViolationCode`.
- Impl-хелперы package-private: `Decoder, Schema(+RequestSchema), FieldDescriptor, FieldType, FieldTypeSupport, ContractVersionValidator, RouteRatiosNormalizer`.
- Legacy-подпакеты `schema/ validation/ result/ version/` отсутствуют (arch-тест `noLegacySubPackages`).
- В API-протоколе нет Spring/OTel/JMX/Jackson/OpenMBean (arch-тест `API_PROTOCOL_*`, `pr4ArchitectureFitnessVerify` зелёный).
- Domain-правил в API нет: `VALIDATION_MODES`/`ratioBounded` отсутствуют; `RouteRatiosNormalizer` — только shape/coercion.
- `TracingControlProtocolDecodeResult` record-инварианты не менялись.
- Правка `TracingControlProtocolSchema.java` (main) — косметическая (одна строка форматирования), поведение не затронуто.

---

## 5. Decode / Domain / Apply Safety Check

- Pipeline `decode → domain validate → apply` в `RuntimePolicyControlHandler.handle()` неизменен: decode-invalid → `decodeRejected` (apply не вызывается); READ short-circuit; domain-invalid → `domainRejected` (apply не вызывается); apply только когда оба valid.
- `RuntimePolicyControlHandlerTest` покрывает DECODE_REJECTED / DOMAIN_REJECTED (bounds, mode-conflict, unknown-mode, комбинированные) / SUCCESS / READ-short-circuit, и в каждом reject-кейсе ассертит `applier.appliedPayloads.isEmpty()`.
- Empty-mutation теперь domain-invalid (не decoder-уровень): decode структурно valid, domain отклоняет.

---

## 6. Consumer Migration Check

- `WireRoundTripTestMBeanImpl` (e2e jmxWireExtension): `current().decode()` → `validateDomainIfNeeded` (READ short-circuit + `RuntimePolicyControlDomainValidator.validate`), apply не вызывается (гарнесс только оценивает). Импортов `result.*/validation.*/schema.*` нет.
- `SamplingControlClientWireContractTest.WireEvaluateStub`: `current().decode(payload)` + `DecodeResult.valid()/violations()`.
- Компиляция всех затронутых test source-set проходит (`:platform-tracing-api:test`, `:platform-tracing-core:test`, `:platform-tracing-spring-boot-autoconfigure:compileTestJava`, e2e `compileJmxWireExtensionJava`/`compileTestJava` — все успешны).

---

## 7. BOM / Encoding Check

Byte-скан всех `*.java` в дереве: **0** файлов с сигнатурой `EF BB BF`. `:platform-tracing-api:compileJava` и полный `build` компилируются без `illegal character: '\ufeff'`. FIXED.

---

## 8. Docs / Wire Spec Check

`docs/control-protocol-wire-spec-v1.md`:
- §3/§9 версия-семантика согласована с `ContractVersionValidator` (MISSING/INVALID/UNSUPPORTED).
- §7 READ-ответ: `contractVersion` не заявляется; значения String-coerced; ключи соответствуют `ReadAppliedStateHandler.read()` + `mapToCompositeData`.
- §6.3 validation.mode = `STRICT`/`LOG_ONLY` — соответствует коду.
- 3 операции, 21 ключ, отказ от `READ_SCHEMA`, pipeline — без изменений.

---

## 9. Golden Wire Test Check

`GoldenWireContractTest` реализован: зеркалит golden-примеры wire-spec и ассертит поведение декодера (valid apply; READ отклоняет policy-ключ через `UNKNOWN_KEY`; отсутствие `contractVersion` → `MISSING_REQUIRED_KEY`). Тест использует программные примеры, эквивалентные таблицам spec (не парсит .md), что удовлетворяет требованию «reads or mirrors examples».

---

## 10. Verification Commands

| Команда | Результат | Примечание |
|---|---|---|
| `:platform-tracing-api:compileJava` | PASS | BOM устранён; компилируется |
| `:platform-tracing-api:test` | PASS | BUILD SUCCESSFUL |
| `:platform-tracing-core:test` | PASS | включает empty-mutation + handler-тесты |
| `:platform-tracing-spring-boot-autoconfigure:compileTestJava` | PASS | UP-TO-DATE (ранее собрано) |
| `:platform-tracing-spring-boot-autoconfigure:test --tests *SamplingControlClientWireContract*` | PASS | зелёный |
| `pr4ArchitectureFitnessVerify` | PASS | зелёный |
| `pr1ModuleTaxonomyVerify` | PASS | зелёный |
| `:platform-tracing-api:javadoc` | PASS | 1 pre-existing warning (propagation `@see`, вне scope) |
| `:platform-tracing-e2e-tests:test --tests *WireRoundTrip*` | SKIPPED | opt-in e2e gate; компилируется, runtime не исполнен → INSUFFICIENT_EVIDENCE для e2e-runtime |
| `build` | PASS | BUILD SUCCESSFUL in ~1m12s; docker-warning в `collector-config` не завалил сборку |
| BOM-скан `*.java` | PASS | 0 совпадений |
| grep удалённого API (api/core/autoconfigure/e2e) | PASS | только негативные assert’ы в arch-тесте |

---

## 11. Residual Findings

### P0
Нет.

### P1
Нет по коду. **Процессный блокер для merge:** фиксы не закоммичены/не запушены — remote-ветка PR #13 всё ещё в pre-fix состоянии `ac23b77`. Пока правки не попадут в ветку, merge закроет дефекты только формально.

### P2
- **P2-e2e (warning):** e2e wire round-trip runtime не прогонялся (SKIPPED). Рекомендуется однократно прогнать e2e-профиль (или CI-джобу с opt-in) для runtime-подтверждения гарнесса.
- **P2-doc (nit):** javadoc-warning в `TraceControlHeaderInjector` (`@see` на core-класс) — вне scope протокола, косметика.

---

## 12. Final Recommendation

**ready after commit/push** (эквивалент «ready after minor fix», где fix — это версионирование, не код).

Кодовая база закрывает все находки предыдущего аудита, гейты зелёные, архитектура цела. Перед merge необходимо: (1) закоммитить и запушить текущие working-tree фиксы в ветку `feature/runtime-policy-control-handler`; (2) прогнать e2e wire round-trip в его opt-in окружении/CI для runtime-подтверждения. После этого PR можно переводить в merge.

---

## 13. Codex Fix Prompt

Кодовых дефектов не осталось — Codex-правки не требуются. Требуется только версионирование (ручное, вне scope READ-ONLY аудита):

```
On branch feature/runtime-policy-control-handler, commit the current working-tree fixes
(post-audit remediation: BOM removal in span builders, migration of TracingControlProtocolTest /
SamplingControlClientWireContractTest / WireRoundTripTestMBeanImpl to decode()/domain-validate,
deletion of TracingControlProtocolFieldTypeTest, empty-mutation precondition in
RuntimePolicyControlDomainValidator + tests, GoldenWireContractTest, wire-spec §3/§7/§9 alignment)
in cohesive commits, then push. Do NOT weaken any ArchUnit rule. After push, run the opt-in e2e
profile so :platform-tracing-e2e-tests:test executes the *WireRoundTrip* case at runtime, and
confirm it is green before merge.
```
