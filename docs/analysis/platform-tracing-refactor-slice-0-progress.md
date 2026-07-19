# Platform Tracing Refactor: Slice 0 Progress

> Дата: 2026-07-19  
> Ветка: `feature/runtime-control-hardening`  
> Baseline HEAD: `3679fb5e49c0fce46762d40f0b51abc6fadbc8c7`
> Статус: **PASS**

## 1. Текущий вердикт

Технические deliverables Slice 0 реализованы, проверены и зафиксированы без изменения production-кода. ABI snapshot, green known-defect inventory, mode-detection evidence и реальные packaged-agent/classloader gates зелёные.

## 2. Выполненные проверки

| Проверка | Результат | Evidence |
|----------|-----------|----------|
| Core, extension и known-defect tasks | PASS | 3 known-defect теста: core=2, autoconfigure=1; skipped=0, failures=0 |
| ABI snapshot | PASS | 876 строк публичных типов, полей, конструкторов и методов API+core; UTF-8 без BOM |
| Agent JAR contents и SPI registration | PASS | `verifyAgentJarContents`, `verifyExtensionSpiRegistration` |
| Architecture fitness | PASS | `pr4ArchitectureFitnessVerify`, `pr1ModuleTaxonomyVerify` |
| Реальный packaged-agent child JVM | PASS | `ClassLoaderVisibilityE2ETest`: tests=1, skipped=0, failures=0, errors=0 |
| JMX classloader-neutral wire | PASS | `MapWireRoundTripE2ETest`: tests=1, skipped=0, failures=0, errors=0 |
| RISK-15 | CLOSED | baseline HEAD чист до запуска; `SemconvKeys` уже зафиксирован в `5d1dec2` |

Нормативная agent-команда:

```powershell
.\gradlew.bat :platform-tracing-e2e-tests:test -PrunE2e --tests "*ClassLoaderVisibilityE2ETest" --rerun-tasks --no-daemon
```

Результат XML: `tests="1" skipped="0" failures="0" errors="0"`; child JVM завершился успешно за 2.576 секунды. Все 35 Gradle-задач запуска были выполнены заново.

## 3. Classloader baseline

Существующий `ClassLoaderVisibilityE2ETest` расширен как реальный packaged-agent characterization test: он запускает отдельную JVM через `ProcessBuilder`, передаёт настоящий `-javaagent`, packaged extension JAR и probe extension JAR, проверяет `ExtensionClassLoader`, SPI-маркеры, timeout и exit code.

Дополнительно доказано:

- API в application plane загружен `AppClassLoader`, embedded API в extension plane — отдельным `ExtensionClassLoader`;
- application launcher и extension probe взаимно невидимы, cross-CL cast/reference не используется;
- `Span.current()` в application plane видит валидный context через agent-owned `GlobalOpenTelemetry`;
- отдельный `MapWireRoundTripE2ETest` подтверждает границу App CL → JMX open types → Agent ExtensionClassLoader.

Создавать второй тест только ради имени `PackagedAgentClassloaderCharacterizationTest` не требуется. Следующее расширение должно добавлять недостающие assertions в существующий fixture, а не дублировать запуск агента.

## 4. Mode-detection inventory

Текущая модель:

- `OtelAgentDetector` проверяет наличие `io.opentelemetry.javaagent.OpenTelemetryAgent` через `ClassUtils.isPresent`;
- `SdkModeResolver` для `AUTO` использует приоритет `agentPresent || globalFunctional -> AGENT`, затем `userBeanPresent -> EXTERNAL`, иначе `STARTER`;
- любое явно заданное значение, включая `DISABLED` и `STARTER`, возвращается без проверки mismatch с фактической средой;
- unit-тест покрывает marker absent и synthetic resolver inputs, но положительное обнаружение marker в packaged agent JVM пока не связано с Spring mode-resolution assertion;
- Slice 0 только фиксирует это поведение. Надёжность marker и mismatch/fail-fast semantics принадлежат исключительно Spike A.

## 5. Реализованные deliverables

1. `AbiSnapshotTest` и reviewable baseline публичной поверхности API+core.
2. Закрытый типизированный реестр `KnownDefectId` и meta-аннотация `@KnownDefect`; произвольные orphan ID не компилируются.
3. Green characterization для `ALIGN-04` (`fromSpec` policy bypass), `ALIGN-10` (unbounded mirror), `IDENT-1/5` (legacy identity key).
4. `knownDefectTest` переведён с historical expected-red semantics на тег `known-defect`, добавлен в `check` обоих owning modules.
5. Existing ABI/architecture reports и module-taxonomy gates подтверждают public allowlist/no-split-package baseline без production delta.
6. Existing packaged-agent fixture расширен class-identity/current-context assertions; JMX boundary подтверждена отдельным real-agent wire E2E.

Lifecycle и facade-state characterization остаются обычными зелёными baseline-тестами: residual defect на текущем HEAD не подтверждён. Это не создаёт known-defect entry и сохраняет Slice F как verify-only gate.

## 6. Finding по воспроизводимости

Запуск architecture verification механически перезаписал дату и SHA в `docs/architecture/baselines/pr-0/starter-dependency-smoke.txt`. Изменение не отражало архитектурный delta и было точечно возвращено к `HEAD`.

Verification-задача, обновляющая tracked baseline при обычном прогоне, создаёт грязный worktree и снижает воспроизводимость. Исправление build-задачи следует выполнить отдельно и не смешивать с production architecture.

## 7. Следующий шаг

Slice 0 завершён. Следующий обязательный этап — Spike A: composition proof и единственный blocking mode-detection decision gate. Production C1 остаётся заблокированным до PASS Spike A.
