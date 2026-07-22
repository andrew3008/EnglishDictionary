# CP-3 R2: evidence атомарной миграции Java package `core` -> `otel`

> **Статус: CLOSED / PASS** — CP-3 R2 package migration завершена; mandatory packaged Agent E2E зелёный.
> **Решение:** CP-3 R2 — RENAME APPROVED по прямому указанию владельца проекта; Slice K KEEP `core.*` superseded.

## 1. Baseline

| Поле | Значение |
|---|---|
| Base remote master | `dfeea89621d945595fae26e525497d75a4a55c5b` |
| Implementation HEAD | `83d54c0` — `Renaming core to otel` (merged to `master`) |
| Evidence closure | docs commit on `master` (post-`83d54c0`) |
| Branch (historical) | `feature/rename-tracing-core-package-to-otel` |
| Worktree (historical) | `E:\Platform_Traces_Package_Rename` |
| Old prefix | `space.br1440.platform.tracing.core` |
| New prefix | `space.br1440.platform.tracing.otel` |

Slice L присутствует в master через merge `022ef7c` (PR #24). Текущий base также включает
последующие master-коммиты `6aa7464` и `dfeea89`.

## 2. Почему CP-3 пересмотрен

CP-3 в Slice K закрылся решением KEEP: тогда repository evidence не доказывал обязательность
package rename. CP-3 R2 не является выводом из старого evidence: это новое явное решение владельца
проекта. Implementation artifact уже называется `platform-tracing-otel`, а R2 требует, чтобы его
implementation namespace также явно обозначал OpenTelemetry ownership. Кодовая база pre-production,
поэтому migration намеренно ломает FQCN без aliases, forwarding classes и compatibility package.

## 3. Initial inventory

Поиск выполнялся по рабочему дереву с исключением `build/**` и `.gradle/**`.

| Категория | Результат |
|---|---:|
| Файлы с old prefix | 314 |
| Строки с old prefix | 1499 |
| Java-файлы с упоминанием | 271 |
| Markdown-файлы с упоминанием | 38 |
| Java package declarations под old prefix | 169 |
| Existing Java declarations под `space.br1440.platform.tracing.otel.*` | 286 |
| Public implementation types в ABI allowlist | 65 |

Package declarations под old prefix находятся только в двух source roots:

- `platform-tracing-otel/src/main/java/space/br1440/platform/tracing/core`;
- `platform-tracing-otel/src/test/java/space/br1440/platform/tracing/core`.

Ссылки присутствуют в `platform-tracing-otel`, Java Agent extension, Spring Boot
autoconfigure, WebMVC/WebFlux, E2E, bench, shared test architecture rules, root Gradle gate,
ABI snapshots и нескольких вспомогательных test/tooling sources.

Прямых old-prefix записей в production `META-INF/services`, Spring metadata, native-image
configuration или Agent descriptors не обнаружено. Runtime-sensitive non-Java references:

- root `build.gradle` negative consumer check;
- `platform-tracing-otel-public-types.txt`;
- `platform-tracing-api-otel.txt`;
- current architecture PlantUML diagram.

Patch archive и historical evidence содержат old prefix как исторический факт и не являются
runtime input.

## 4. Collision analysis

Для каждого из 169 source FQCN вычислен target путём замены только prefix, suffix сохранён.

```text
SOURCE_FQCN_COUNT=169
TARGET_FQCN_COUNT=169
COLLISION_COUNT=0
```

Existing `space.br1440.platform.tracing.otel.*` declarations принадлежат отдельному
`platform-tracing-otel-javaagent-extension` и находятся под `otel.javaagent.*`. Ни один target
FQCN с ними не пересекается. Mapping one-to-one выполним без redesign.

## 5. Migration scope

Будут изменены package declarations, imports/FQCN references, tests, architecture gates,
ABI snapshots и current documentation. Будут физически перемещены main/test sources внутри
`platform-tracing-otel` с сохранением suffix tree.

Не изменяются:

- `space.br1440.platform.tracing.api.*`;
- `space.br1440.platform.tracing.otel.javaagent.*` topology;
- Gradle module names;
- CP-C2, CP-1, CP-2 и CP-4 semantics;
- historical evidence body и archived patch;
- release-gate status.

### Migration outcome

- физически перенесено 169 Java-файлов с сохранением suffix tree;
- обновлено 1 053 old-prefix references: 1 048 dotted и 5 slash-form;
- итоговый atomic diff на этапе verification: 294 файла;
- изменённые модули: `platform-tracing-otel`, Java Agent extension, API tests/Javadocs,
  Spring Boot autoconfigure, WebMVC, WebFlux, E2E fixtures, benchmarks, shared architecture
  tests и repository tooling, которое содержит FQCN fixtures;
- Gradle module names, dependency topology и BOM coordinates не изменялись.

## 6. ABI impact

65 публичных implementation types меняют FQCN с `core.*` на `otel.*`. Это intentional
pre-production ABI break. Public API module types не перемещаются и compatibility aliases
запрещены. Final snapshots и negative gates должны отразить новый prefix.

## 7. Verification results

### Статические проверки

| Проверка | Результат |
|---|---:|
| Java package declarations под old prefix | 0 |
| Java imports old prefix | 0 |
| Java-файлы в старом source path | 0 |
| Коллизии file-level FQCN | 0 |
| Java UTF-8 BOM | 0 |
| Новые wildcard imports | 0 (`27` до и `27` после с учётом untracked moved sources) |
| Абсолютные локальные Markdown links в изменённых документах | 0 |
| Изменения `.cursor/**` | 0 |
| `git diff --check` | PASS |

### Gradle verification

| Команда / gate | Результат |
|---|---|
| `:platform-tracing-test:test :platform-tracing-otel:test` | PASS, 11 tasks |
| Focused matrix: API, OTel implementation, Agent extension, Spring autoconfigure, WebMVC, WebFlux | PASS, 42 tasks |
| `pr0StarterDependencySmoke` | PASS |
| `pr1ModuleTaxonomyVerify` | PASS |
| `pr4ArchitectureFitnessVerify` | PASS |
| `cp3LegacyPackageVerify` | PASS |
| `sliceJStaleNameVerify` | PASS |
| `build --no-daemon` | PASS, 127 tasks |

Первый focused run выявил 11 stale ArchUnit package patterns `..core.*..`. Они были
исправлены только заменой package prefix; правила не ослаблялись и `allowEmpty` не добавлялся.
Первый aggregate run также выявил четыре старых slash-path ожидания в
`verifyAgentJarContents`; после их замены gate и полный build прошли.

### Mandatory packaged Agent E2E

| Запуск | Команда | Результат |
|---|---|---|
| Blocked (2026-07-22, ранний) | `DOCKER_HOST=tcp://192.168.100.70:2375` `-PrunE2e` `--rerun-tasks` | 43 tests: 25 passed, 18 `initializationError` (Docker API unavailable) |
| **Closure (2026-07-22)** | `:platform-tracing-e2e-tests:test -PrunE2e --rerun-tasks --no-daemon` | **PASS** — 28 suites, **65 tests**, 0 failures / 0 errors / 0 skipped; BUILD SUCCESSFUL (~9m 32s) |

Closure run выполнен владельцем проекта после восстановления Gentoo Docker
(`DOCKER_HOST=tcp://192.168.100.70:2375`). Migration verdict: **PASS**.

## 8. Packaged artifact inspection

Проверены runtime-артефакты:

| JAR | Entries | Old package entries | `otel.*` entries | Duplicate entries |
|---|---:|---:|---:|---:|
| `platform-tracing-otel-0.1.0-SNAPSHOT.jar` | 153 | 0 | 147 | 0 |
| thin Java Agent extension JAR | 208 | 0 | 197 | 0 |
| self-contained `*-agent.jar` | 527 | 0 | 343 | 0 |

Распакованные class/resource files дополнительно просканированы по dotted и slash form old
prefix: 0 совпадений во всех трёх JAR. Четыре `META-INF/services` descriptor указывают на:

- `space.br1440.platform.tracing.otel.javaagent.PlatformAutoConfigurationCustomizer`;
- `space.br1440.platform.tracing.otel.javaagent.propagation.InboundTraceControlPropagatorProvider`;
- `space.br1440.platform.tracing.otel.javaagent.resource.SafeResourceProvider`;
- `space.br1440.platform.tracing.otel.javaagent.sampler.PlatformSamplerProvider`.

Self-contained Agent JAR намеренно включает implementation classes как часть изолированного
Agent composition plane. Внутри каждого packaged artifact duplicate ZIP entries отсутствуют;
application-side injection или cross-classloader cast не добавлялись.

## 9. Remaining old-prefix references

Финальный scan active tree на текущем этапе показывает 493 строки в 37 Markdown-файлах и
0 совпадений в production/test source, Gradle metadata, service registration или packaged JAR.

Оставшиеся Markdown-упоминания разделены так:

- canonical migration docs и CP-3 R2 ADR: old prefix используется только как запрещённый или
  superseded namespace;
- Slice J/K/L и C3 evidence, architecture inventories и analysis reports: исторические
  snapshots прежнего состояния;
- superseded ADR и удалённый legacy stack: исторический контекст решения;
- research/refactoring materials и archived changed-source reports: неизменяемые
  исследовательские артефакты.

Два старых документа в `docs/architecture/Migration Plan/` дополнительно помечены как
`HISTORICAL / SUPERSEDED` и ссылаются на canonical final architecture. Исторические bodies
не переписывались механически.

## 10. Findings and release gates

### Findings

- **P0 production/code:** не обнаружены.
- **P1 architecture:** не обнаружены; focused gates, aggregate build и mandatory E2E зелёные.
- **P2:** не обнаружены новые package-migration defects.
- **ENV-1:** CLOSED — mandatory packaged Agent E2E PASS (28 suites / 65 tests / 0/0/0).

**CP-3 R2 verdict:** CLOSED — `space.br1440.platform.tracing.core.*` → `space.br1440.platform.tracing.otel.*`
migration complete on `master@83d54c0`. Slice K KEEP decision superseded by owner-directed R2.

Независимо от результата миграции:

```text
RG-IDENTITY-TRUST OPEN
RG-CONTROLLED-AGENT OPEN
PRODUCTION ROLLOUT FORBIDDEN
```
