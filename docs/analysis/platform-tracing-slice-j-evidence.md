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

Выполнен механический перенос корневого Java package только для Agent extension:

```text
space.br1440.platform.tracing.otel.extension.*
    -> space.br1440.platform.tracing.otel.javaagent.*
```

Package declarations и imports обновлены в main/test/E2E/benchmark/perf source sets,
ArchUnit roots, reflection/resource literals и активной документации. Package
`space.br1440.platform.tracing.core.*` в `platform-tracing-otel` намеренно сохранён:
92 main declarations, 0 ошибочных declarations под `space.br1440.platform.tracing.otel.*`.

Финальный inventory Agent package:

| Проверка | Результат |
|---|---|
| main declarations `...otel.javaagent.*` | 144 |
| main declarations `...otel.extension.*` | 0 |
| classes в Agent extension JAR под `otel/javaagent` | 165 |
| classes в Agent extension JAR под `otel/extension` | 0 |

Финальная SPI map:

| Provider interface | Implementation after J-commit-3 | Class in JAR |
|---|---|---|
| `AutoConfigurationCustomizerProvider` | `space.br1440.platform.tracing.otel.javaagent.PlatformAutoConfigurationCustomizer` | yes |
| `ResourceProvider` | `space.br1440.platform.tracing.otel.javaagent.resource.SafeResourceProvider` | yes |
| `ConfigurableSamplerProvider` | `space.br1440.platform.tracing.otel.javaagent.sampler.PlatformSamplerProvider` | yes |
| `ConfigurablePropagatorProvider` | `space.br1440.platform.tracing.otel.javaagent.propagation.InboundTraceControlPropagatorProvider` | yes |

`verifyExtensionSpiRegistration` проверяет точное содержимое descriptors и наличие
implementation classes в JAR. Дополнительно добавлен `sliceJStaleNameVerify`, который
запрещает старые artifact/project/package names в active build, source, resources и
нормативных ADR/target documents; исторические evidence/snapshots не входят в gate.

JMX/wire audit подтвердил сохранение 9 logical ObjectName literals относительно
J-commit-2 (`set equal = true`). Имена домена `space.br1440.platform.tracing`, Open-Type
контракты, readiness capability names и control protocol не менялись.

Mechanical-source audit: для 308 изменённых Java/resource файлов current content после
обратной подстановки `otel.javaagent -> otel.extension` точно совпадает с J-commit-2;
non-mechanical source/resource diffs = 0. Изменения вне sources ограничены package paths,
SPI FQNs, ArchUnit roots, build verification и актуальными ссылками документации.

## 8. Final Verification

### Publication and controlled distribution

- isolated C3 metadata consumer: GREEN;
- `platform-tracing-otel` и `platform-tracing-otel-javaagent-extension` опубликованы под
  новыми coordinates; каталоги старых artifacts отсутствуют;
- extension POM artifactId и Gradle module component равны
  `platform-tracing-otel-javaagent-extension`;
- C3 compile/runtime boundary сохранён, implementation не утёк в consumer compile classpath;
- controlled ZIP содержит ровно `VERSION`, `checksums.sha256`, `manifest.json`, pinned
  `opentelemetry-javaagent.jar`, два launcher, verifier и
  `platform-tracing-otel-javaagent-extension.jar`;
- два forced distribution rebuild после package rename имеют одинаковый SHA-256
  `86217F926ED3A776BCC9C6174767F4EE605904C0B3E1DCDB10245A756AAF62D9`;
- production distribution configuration cache: первая сборка сохранила entry, повторная
  сборка сообщила `Configuration cache entry reused`.

### Packaged Agent E2E

Docker подтверждён по `tcp://192.168.100.70:2375`: server 28.0.4, Linux/amd64.
Полный canonical opt-in прогон выполнен командой:

```powershell
$env:DOCKER_HOST='tcp://192.168.100.70:2375'
.\gradlew.bat :platform-tracing-e2e-tests:test -PrunE2e --rerun-tasks --no-daemon
```

Результат: GREEN за 9m24s; 28 XML suites, 65 tests, 0 failures, 0 errors,
0 skipped. До полного прогона также GREEN выбранный packaged subset classloader,
attestation, Spring composition, Reactor, fail-closed security, WebMVC identity, Kafka и
JMX/control wire roundtrip (3m35s).

### Static and ABI checks

| Проверка | Результат |
|---|---|
| focused API wire / CP-C2 propagation | GREEN |
| `AbiSnapshotTest`, identity/public-surface allowlists | GREEN |
| Slice G sampling sealed-public-surface / golden chain | GREEN |
| extension adapter, JMX/readiness and full extension tests | GREEN |
| `sliceJStaleNameVerify` | GREEN |
| active code/build/resource stale-name scan | 0 (historical allowlist сохранён) |
| Java BOM scan | 1163 files, 0 BOM |
| wildcard imports | 27 before / 27 after, delta 0; pre-existing, cleanup вне Slice J |
| `git diff --check` | GREEN |

### Findings

- P0: none.
- P1: none.
- P2: pre-existing 27 wildcard imports; Slice J не добавляет новые и не смешивает cleanup
  с coordinated rename.
- Один transient Windows/JUnit `@TempDir` cleanup failure на J-commit-1 был опровергнут
  isolated rerun и последующими полными GREEN runs; production delta не потребовался.

Финальный aggregate gate (`projects`, JAR/SPI/distribution verifiers, PR-0/PR-1/PR-4,
C3 consumer и `build`) завершён GREEN: 149 actionable tasks, 0 failures. Обычная
`build`-задача ожидаемо пропустила opt-in E2E; обязательный отдельный `-PrunE2e` прогон
выше выполнен без skips. Clean-worktree status фиксируется после третьего commit;
push/PR разрешены только при сохранении GREEN состояния.

## 9. Current Release Gates

```text
RG-IDENTITY-TRUST OPEN
RG-CONTROLLED-AGENT OPEN
PRODUCTION ROLLOUT FORBIDDEN
```

CP-3 сохраняет `KEEP space.br1440.platform.tracing.core.*`; CP-4 сохраняет `KEEP void`.
По dependency graph Slice K является кандидатом на no-op closure, но не считается
реализованным или закрытым без явного authoritative решения.
