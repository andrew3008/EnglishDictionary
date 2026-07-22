# Slice J: Coordinated OTel Artifact and Java Agent Extension Rename

> Статус: IN PROGRESS
> Дата старта: 2026-07-22
> Ветка: `feature/slice-j-coordinated-artifact-rename`
> Base: `efd7cc3eb4adeb87e4431e91e9c46f4d15010dab`
> Base commit: merge PR #21 (`feature/slice-g-sampling-sealed-internal`)

## 1. Prerequisite Verification

Local `master`, tracking ref `english-dict/master` и remote `refs/heads/master`
совпадают на `efd7cc3eb4adeb87e4431e91e9c46f4d15010dab`. Base содержит:

- Slice C3;
- Slice M, merge PR #19;
- Slice I, merge PR #20;
- Slice G / CP-2 SEALED INTERNAL, merge PR #21.

Исходный `E:\Platform_Traces` был clean. Slice J выполняется в отдельном worktree
`E:\Platform_Traces_Slice_J`.

## 2. Pre-Rename Module Graph

`gradlew projects` подтвердил 15 subprojects. Переименовываемые узлы:

```text
:platform-tracing-core
:platform-tracing-otel-extension
```

Целевые узлы:

```text
:platform-tracing-otel
:platform-tracing-otel-javaagent-extension
```

Java packages `space.br1440.platform.tracing.core.*` намеренно сохраняются.

## 3. Pre-Rename Publication Coordinates

Общие координаты задаются root build:

```text
group   = space.br1440.platform.tracing
version = 0.1.0-SNAPSHOT
```

До rename project-name-derived artifact IDs:

```text
space.br1440.platform.tracing:platform-tracing-core:0.1.0-SNAPSHOT
space.br1440.platform.tracing:platform-tracing-otel-extension:0.1.0-SNAPSHOT
```

`platform-tracing-bom` не содержит constraints на внутренние platform artifacts;
он выравнивает внешние Spring/OTel/test dependencies. Эта политика сохраняется.

## 4. Complete Tracked Reference Inventory

Инвентаризация выполнена до первого rename командами `git grep` по всем tracked files.

| Pattern | Active build | Production | Test/E2E | Current docs | Historical | Tooling | Other | Total files |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| `platform-tracing-core` | 8 | 7 | 2 | 47 | 58 | 17 | 13 | 152 |
| `platform-tracing-otel-extension` | 9 | 8 | 23 | 94 | 49 | 12 | 17 | 212 |
| `space.br1440.platform.tracing.otel.extension` | 1 | 150 | 158 | 19 | 7 | 0 | 3 | 338 |
| `space/br1440/platform/tracing/otel/extension` | 0 | 0 | 0 | 14 | 4 | 0 | 3 | 21 |

Классификация:

- **ACTIVE BUILD REFERENCE:** `settings.gradle`, root/subproject build scripts,
  `gradle/c3-published-consumer`, publication/distribution/verifier wiring.
- **ACTIVE PRODUCTION REFERENCE:** module-name Javadocs, Agent package declarations/imports,
  SPI resources, reflective/resource literals.
- **ACTIVE TEST/E2E REFERENCE:** tests, fixtures/source sets, benchmarks, perf harness,
  controlled-Agent child-JVM wiring.
- **SPI FQN:** четыре descriptors в Agent extension (см. §5).
- **MANIFEST/PUBLICATION METADATA:** extension `Implementation-Title`, project-derived
  Maven artifact IDs, agent/distribution archive names.
- **DISTRIBUTION/VERIFIER REFERENCE:** extension build, distribution verifier,
  launchers, E2E/perf copied artifact names and allowlists.
- **CURRENT DOCUMENTATION:** README/CHANGELOG, ADRs, architecture, tracing and runbooks;
  обновляются на актуальные имена.
- **HISTORICAL EVIDENCE:** `docs/analysis`, `docs/refactoring`, committed perf-result
  snapshots, PR-0 baselines and historical patches; старые имена сохраняются, если
  описывают прежний HEAD.
- **TOOLING:** tracked `.cursor` rules/skills; обновляются только нормативные current
  references, исторические примеры не переписываются автоматически.
- **GENERATED/BUILD OUTPUT:** исключён из tracked inventory и не коммитится.

Активные Gradle owners старого core project path:

```text
settings.gradle
build.gradle
gradle/c3-published-consumer/build.gradle
platform-tracing-bench/build.gradle
platform-tracing-e2e-tests/build.gradle
platform-tracing-otel-extension/build.gradle
platform-tracing-spring-boot-autoconfigure/build.gradle
platform-tracing-test/build.gradle
```

Активные Gradle owners старого extension project path/name:

```text
settings.gradle
build.gradle
gradle/c3-published-consumer/build.gradle
platform-tracing-bench/build.gradle
platform-tracing-e2e-tests/build.gradle
platform-tracing-otel-extension/build.gradle
platform-tracing-perf-harness/build.gradle
platform-tracing-perf-tests/build.gradle
platform-tracing-spring-boot-autoconfigure/build.gradle
```

## 5. Pre-Rename Agent SPI Map

| Provider interface | Implementation before J-commit-3 |
|---|---|
| `AutoConfigurationCustomizerProvider` | `space.br1440.platform.tracing.otel.extension.PlatformAutoConfigurationCustomizer` |
| `ResourceProvider` | `space.br1440.platform.tracing.otel.extension.resource.SafeResourceProvider` |
| `ConfigurableSamplerProvider` | `space.br1440.platform.tracing.otel.extension.sampler.PlatformSamplerProvider` |
| `ConfigurablePropagatorProvider` | `space.br1440.platform.tracing.otel.extension.propagation.InboundTraceControlPropagatorProvider` |

Provider interface filenames не меняются. Только implementation FQNs будут заменены
в J-commit-3.

## 6. Pre-Rename Controlled-Agent Ownership

Agent extension project владеет:

- `agentExtensionJar`;
- `platformExtendedAgentJar`;
- `verifyAgentJarContents`;
- `verifyExtensionSpiRegistration`;
- `preparePlatformAgentDistribution`;
- `verifyPlatformAgentDistribution`;
- Maven publication standard/agent/distribution artifacts.

Controlled distribution уже использует конечное имя
`platform-tracing-otel-javaagent-extension.jar` для вложенного и отдельного extension
artifact, но project/artifact publication и manifest title пока имеют старое имя.
Встроенные `space/br1440/platform/tracing/core/**` paths сохраняются после J-commit-1.

## 7. Commit Evidence

### J-commit-1

Выполнен coordinated artifact rename:

```text
Gradle project: :platform-tracing-core -> :platform-tracing-otel
Maven artifact: platform-tracing-core -> platform-tracing-otel
Java packages:  space.br1440.platform.tracing.core.* (без изменений)
```

Обновлены `settings.gradle`, project dependencies, C3 isolated-consumer fixture,
publication/architecture checks, активные ADR и документация. Исторические evidence-файлы
не переписывались. ABI allowlist resources переименованы вместе с модулем; содержимое
public type snapshot не изменено.

Проверки до коммита:

| Проверка | Результат |
|---|---|
| `gradlew projects` | GREEN: 15 subprojects, присутствует `:platform-tracing-otel`, старый project отсутствует |
| generated Maven POM / Gradle module metadata | GREEN: `space.br1440.platform.tracing:platform-tracing-otel:0.1.0-SNAPSHOT` |
| `c3PublishedMetadataConsumerVerify` | GREEN: runtime dependency использует новый artifactId, compile boundary сохранена |
| `:platform-tracing-otel:test` | GREEN |
| `pr0StarterDependencySmoke` | GREEN: оба starter graph используют `platform-tracing-otel` |
| `pr1ModuleTaxonomyVerify` | GREEN |
| `pr4ArchitectureFitnessVerify` | GREEN |
| `build` | GREEN |
| active Gradle/source scan `platform-tracing-core` | 0 совпадений |
| package scan `space.br1440.platform.tracing.otel.*` внутри нового модуля | 0 ошибочных declarations |

Первый совмещённый прогон получил один Windows/JUnit cleanup failure при удалении
`@TempDir` после `PlatformAgentDistributionVerifierTest.rejectsCorruptManifest`.
Изолированный `--rerun-tasks` этого теста и последующий полный extension suite прошли.
Production-код для этого transient cleanup не менялся.

Удалённый Docker endpoint `tcp://192.168.100.70:2375` во время прогонов был недоступен;
`validateCollectorConfigs` задокументировал ошибку Docker, но существующая задача завершилась
успешно. Это не считается доказательством opt-in E2E; обязательный packaged E2E остаётся
финальным gate J-commit-3.

### J-commit-2

Выполнен artifact/project rename без package rename:

```text
Gradle project: :platform-tracing-otel-extension
             -> :platform-tracing-otel-javaagent-extension
Maven artifact: platform-tracing-otel-extension
             -> platform-tracing-otel-javaagent-extension
Java packages:  space.br1440.platform.tracing.otel.extension.* (временно сохранены)
```

Обновлены project dependencies, E2E/perf/benchmark wiring, classpath guards,
manifest `Implementation-Title`, publication metadata и controlled-Agent distribution.
Логические JMX ObjectNames, wire protocol и SPI implementation FQNs не менялись.

Проверки до коммита:

| Проверка | Результат |
|---|---|
| `gradlew projects` | GREEN: присутствует только `:platform-tracing-otel-javaagent-extension` |
| generated POM / Gradle module metadata | GREEN: новый artifactId |
| isolated C3 Maven publication | GREEN: новый artifact и classifiers опубликованы; старые координаты отсутствуют |
| `:platform-tracing-otel-javaagent-extension:test` | GREEN |
| `verifyAgentJarContents` | GREEN |
| `verifyExtensionSpiRegistration` | GREEN; FQNs пока намеренно старые до J-commit-3 |
| `verifyPlatformAgentDistribution` | GREEN |
| `c3PublishedMetadataConsumerVerify` | GREEN |
| `pr1ModuleTaxonomyVerify` | GREEN |
| `pr4ArchitectureFitnessVerify` | GREEN |
| `build` | GREEN |
| active Gradle/source scan старого extension artifact | 0 совпадений |
| package declaration scan | 286 `...otel.extension.*`; 0 `...otel.javaagent.*` |

Controlled distribution archive содержит `platform-tracing-otel-javaagent-extension.jar`
и не содержит старого имени. Два последовательных `--rerun-tasks` rebuild дали одинаковый
SHA-256:

```text
FFCC7D4C28430864FB117C90F0853E3BB6B0D9935D19FA9B9B067EA86ABD15BD
```

Удалённый Docker endpoint оставался недоступен; обязательный opt-in E2E по-прежнему
не заявляется как выполненный на этом этапе.

### J-commit-3

PENDING.

## 8. Final Verification

PENDING.

## 9. Current Release Gates

```text
RG-IDENTITY-TRUST OPEN
RG-CONTROLLED-AGENT OPEN
PRODUCTION ROLLOUT FORBIDDEN
```
