# Platform Tracing Refactor: Slice 0 Progress

> Дата: 2026-07-19  
> Ветка: `feature/runtime-control-hardening`  
> Baseline HEAD: `5d1dec26babfeb05db8dd1110e6d69631daef449`  
> Статус: **IN PROGRESS**

## 1. Текущий вердикт

Slice 0 начат после завершения обязательного Slice -1. Архитектурные и packaged-agent gates на текущем HEAD зелёные, реальный child-JVM тест с `-javaagent` выполнен и не был пропущен.

Slice 0 пока не завершён. Отсутствуют обязательный ABI snapshot и green known-defect inventory; существующие задачи `knownDefectTest` не содержат тегированных тестов и поэтому их зелёный результат сам по себе не закрывает gate.

Переход к Spike A до закрытия этих deliverables запрещён.

## 2. Выполненные проверки

| Проверка | Результат | Evidence |
|----------|-----------|----------|
| Core, extension и known-defect tasks | PASS | `BUILD SUCCESSFUL`; задачи `knownDefectTest` не обнаружили тегированных тестов |
| Agent JAR contents и SPI registration | PASS | `verifyAgentJarContents`, `verifyExtensionSpiRegistration` |
| Architecture fitness | PASS | `pr4ArchitectureFitnessVerify`, `pr1ModuleTaxonomyVerify` |
| Реальный packaged-agent child JVM | PASS | `ClassLoaderVisibilityE2ETest`: tests=1, skipped=0, failures=0, errors=0 |
| RISK-15 | CLOSED | baseline HEAD чист до запуска; `SemconvKeys` уже зафиксирован в `5d1dec2` |

Нормативная agent-команда:

```powershell
.\gradlew.bat :platform-tracing-e2e-tests:test -PrunE2e --tests "*ClassLoaderVisibilityE2ETest" --rerun-tasks --no-daemon
```

Результат XML: `tests="1" skipped="0" failures="0" errors="0"`; child JVM завершился успешно за 2.221 секунды. Все 35 Gradle-задач запуска были выполнены заново.

## 3. Classloader baseline

Существующий `ClassLoaderVisibilityE2ETest` уже является реальным packaged-agent characterization test: он запускает отдельную JVM через `ProcessBuilder`, передаёт настоящий `-javaagent`, packaged extension JAR и probe extension JAR, проверяет `ExtensionClassLoader`, SPI-маркеры, timeout и exit code.

Создавать второй тест только ради имени `PackagedAgentClassloaderCharacterizationTest` не требуется. Следующее расширение должно добавлять недостающие assertions в существующий fixture, а не дублировать запуск агента.

## 4. Mode-detection inventory

Текущая модель:

- `OtelAgentDetector` проверяет наличие `io.opentelemetry.javaagent.OpenTelemetryAgent` через `ClassUtils.isPresent`;
- `SdkModeResolver` для `AUTO` использует приоритет `agentPresent || globalFunctional -> AGENT`, затем `userBeanPresent -> EXTERNAL`, иначе `STARTER`;
- любое явно заданное значение, включая `DISABLED` и `STARTER`, возвращается без проверки mismatch с фактической средой;
- unit-тест покрывает marker absent и synthetic resolver inputs, но положительное обнаружение marker в packaged agent JVM пока не связано с Spring mode-resolution assertion;
- Slice 0 только фиксирует это поведение. Надёжность marker и mismatch/fail-fast semantics принадлежат исключительно Spike A.

## 5. Открытые deliverables

1. Добавить точный ABI snapshot для публичной поверхности `platform-tracing-api` и `platform-tracing-core`.
2. Перевести `knownDefectTest` с пустого historical `r01-red` набора на обязательную green characterization model.
3. Добавить defect inventory с ID и owner slice для `fromSpec` policy bypass, lifecycle/state, mirror и identity baseline; запретить orphan tags.
4. Добавить report-mode проверку public allowlist/no-split-package без изменения production-кода.
5. Расширить существующий packaged-agent fixture недостающими assertions плана, если они не покрыты отдельными E2E.

## 6. Finding по воспроизводимости

Запуск architecture verification механически перезаписал дату и SHA в `docs/architecture/baselines/pr-0/starter-dependency-smoke.txt`. Изменение не отражало архитектурный delta и было точечно возвращено к `HEAD`.

Verification-задача, обновляющая tracked baseline при обычном прогоне, создаёт грязный worktree и снижает воспроизводимость. Исправление build-задачи следует выполнить отдельно и не смешивать с production architecture.

## 7. Следующий шаг

Следующая работа в Slice 0: ABI snapshot и green known-defect lifecycle. До их зелёного выполнения статус остаётся `IN PROGRESS`; Spike A не разблокирован.
