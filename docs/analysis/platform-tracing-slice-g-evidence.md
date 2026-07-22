# Slice G: Sampling Policy Sealed-Internal Evidence

> Дата: 2026-07-22  
> Ветка: `feature/slice-g-sampling-sealed-internal`  
> Base: `281acda958395ece5c58644179e369560299cbdf` (merge PR #20 / Slice I)  
> Решение: `CP-2 APPROVED — SEALED INTERNAL`

## 1. Executive Verdict

**PASS.** Slice G завершён как verification-first slice. Package topology, модули,
runtime control protocol и нормативный sampling order не изменялись.

Проверка обнаружила два конкретных residual defect:

1. `SamplingPolicyRule` был public и входил в ABI, а
   `ProductionSamplingPolicyChain` возвращал массивы rule instances.
2. `SafeSampler` при исключении platform sampler использовал ratio-based fallback,
   который мог разрешить sampling и тем самым ослабить fail-closed governance.

Исправления ограничены этими доказанными расхождениями и обязательными contract tests.

## 2. Implemented Contract

- `SamplingPolicyRule` package-private и удалён из public ABI allowlist/snapshot.
- Все семь реализаций остаются package-private в `core.sampling.policy`.
- `SamplingPolicyEngine` имеет private constructor и создаётся через
  `productionEngine()`; test foundation factory остаётся package-private.
- Fixed chain не принимает внешние rules и не раскрывает их тип или instances.
- Sampling policy не имеет `ServiceLoader` descriptor, Spring bean composition point
  или application override.
- OTel `CompositeSampler` создаёт platform engine через его factory; adapter contract
  и decision mapping не изменены.
- `SafeSampler` при exception, `null` и открытом degraded-mode breaker возвращает
  `DROP`. Ошибка policy не может увеличить sampling.

## 3. Golden Behavior

Подтверждён неизменный production order:

`kill_switch → hard_drop → force_header → qa_trace → parent_decision → route_ratio → default_ratio`

Characterization покрывает kill switch, hard drop, force/QA, parent decision,
route/default ratios, deterministic reasons и OTel mapping. Hard-drop сохраняет
приоритет над force/QA; parent behavior сохраняет утверждённое положение перед ratios.

Concurrent runtime updates продолжают использовать versioned snapshot/last-known-good
модель. Runtime mutation остаётся за существующим control protocol.

## 4. Public Surface Proof

`SamplingPolicyInternalContractTest` подтверждает:

- rule type является interface, но не `public`;
- public methods fixed chain не содержат rule type в параметрах или return type;
- engine и chain имеют только private constructors;
- descriptor `META-INF/services/<SamplingPolicyRule FQN>` отсутствует.

`ModuleTaxonomyArchRules.SAMPLING_RULE_IMPLS_ONLY_IN_POLICY` больше не разрешает
пустой анализируемый набор и требует размещения всех implementations в policy package.

Intentional ABI delta ограничен удалением `SamplingPolicyRule` и заменой публичных
методов, возвращавших rule arrays, на fixed-chain operations. Compatibility shim и
deprecated alias не добавлялись, поскольку решение ещё не выпускалось в production.

## 5. Verification Results

| Проверка | Результат |
|---|---|
| Core/extension compile + compileTest | PASS |
| Полные `:platform-tracing-core:test` и `:platform-tracing-otel-extension:test` | PASS |
| Golden focused sampling suite | PASS |
| `SamplingPolicyInternalContractTest` | PASS |
| `pr1ModuleTaxonomyVerify pr4ArchitectureFitnessVerify` | PASS |
| `build --no-daemon` | PASS |
| `git diff --check` | PASS |
| Opt-in packaged Agent E2E (`-PrunE2e`, Gentoo Docker) | PASS — 28 suites, 65 tests, 0 failures/errors/skipped |
| Sampling-policy ServiceLoader scan | 0 registrations |
| Package/module move scan | 0 moves, 0 new modules |

Golden command:

```powershell
.\gradlew.bat :platform-tracing-core:test `
  --tests "*ProductionSamplingPolicyChainTest" `
  --tests "*SamplingPolicyEngineTest" `
  --tests "*SamplingPolicyReasonTest" `
  --tests "*SamplingPolicyInternalContractTest" `
  :platform-tracing-otel-extension:test `
  --tests "*Sampling*CharacterizationTest" `
  --tests "*SafeSamplerTest" `
  --tests "*SamplerRuntimeUpdateConcurrencyTest" `
  --tests "*TraceIdRatioParityTest" `
  --tests "*SamplingPolicyDecisionOtelAdapterTest" `
  --no-daemon
```

Architecture and build commands:

```powershell
.\gradlew.bat pr1ModuleTaxonomyVerify pr4ArchitectureFitnessVerify --no-daemon
.\gradlew.bat build --no-daemon
```

Opt-in packaged Agent E2E (Gentoo Docker, mandatory for Slice G closure evidence):

```powershell
$env:DOCKER_HOST='tcp://192.168.100.70:2375'
.\gradlew.bat :platform-tracing-e2e-tests:test -PrunE2e --rerun-tasks --no-daemon
```

Результат (2026-07-22): **BUILD SUCCESSFUL** (~9m 16s); **28 suites**, **65 tests**,
**0 failures**, **0 errors**, **0 skipped**. Docker endpoint `192.168.100.70:2375/_ping` → **200 OK**.

Первый объединённый architecture-gate run получил Windows/JUnit temp-cleanup error
после `PlatformAgentDistributionVerifierTest.rejectsExternalExtensionConfiguration`:
не удалился временный каталог. Изолированный `--rerun-tasks` этого теста прошёл, после
чего полный повтор обоих architecture gates завершился `BUILD SUCCESSFUL`. Это не
sampling failure и не скрыто из итогового evidence.

Полный build и opt-in packaged Agent E2E завершились успешно. Необязательная collector Docker-проверка
ранее не ответила, но не блокировала общий build; opt-in E2E выполнен отдельно после восстановления
маршрута к Gentoo Docker API.

## 6. Scope Audit

Изменены только:

- core sampling engine/policy и их tests/ABI snapshots;
- OTel sampler safety wrapper/builder и tests;
- shared sampling architecture rule;
- CP-2 ADR, действующий layering ADR и данный evidence report.

Не вводились package moves, новый модуль, application SPI, custom-rule registration,
Spring override, произвольный `@Primary`, новый protocol operation или изменение
sampling reason/order contract.

## 7. Closing Status

```text
CP-2 CLOSED
SLICE G CLOSED
SAMPLING SPI SEALED INTERNAL
SLICE J UNBLOCKED (NOT STARTED)
RG-IDENTITY-TRUST OPEN
RG-CONTROLLED-AGENT OPEN
PRODUCTION ROLLOUT FORBIDDEN
```
