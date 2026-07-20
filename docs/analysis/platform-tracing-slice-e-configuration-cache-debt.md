# TD-SLICE-E-CC-01: Repository-wide Configuration Cache Compatibility

> Класс: P2 technical debt
> Владелец: Platform Build/CI owner
> Блокирует Slice F: нет
> Блокирует production rollout: нет; release pipeline должен использовать проверенный production distribution gate

## Контекст

Production-задачи Controlled Agent distribution (`platformExtendedAgentJar`,
`preparePlatformAgentDistribution`, `verifyPlatformAgentDistribution`) поддерживают Gradle
configuration cache: первый запуск сохраняет entry, повторный использует его.

Полный `build --configuration-cache` пока несовместим по двум независимым группам:

1. Pre-existing production build debt: `agentExtensionJar` обращается к `project` внутри lazy
   `zipTree` closures (`platform-tracing-otel-extension/build.gradle:103-104`).
2. Test-infrastructure debt: старые opt-in E2 fixture tasks и добавленный в Slice E
   `testE2FailureAgentJar` используют execution-time `project`/task access. Новая Slice E часть
   должна учитываться как новая test-infrastructure limitation, а не как исторический долг.

## Scope

- заменить execution-time `project`/task access на serializable providers/file inputs;
- сохранить byte-identical embedded test extensions и Agent manifests;
- не переносить test fixtures в published artifacts;
- не ослаблять `-PrunE2e`, distribution verifier или callback failure matrix.

## Acceptance criteria

1. Два последовательных `build --configuration-cache --no-daemon`: entry stored, затем reused.
2. `platformExtendedAgentJar` и все opt-in E2 fixture JAR tasks сохраняют текущий состав.
3. Closing E2E suite с `-PrunE2e --rerun-tasks --no-build-cache` имеет failures/errors/skipped `0/0/0`.
4. `pr4ArchitectureFitnessVerify`, `pr1ModuleTaxonomyVerify`, `pr0StarterDependencySmoke` GREEN.

## Non-blocking rationale

Основной build GREEN; production distribution отдельно доказала configuration-cache reuse; остаток
ограничен существующей fat-JAR сборкой и opt-in E2 fixtures. Поэтому задача не блокирует Slice F,
но должна быть закрыта до заявления repository-wide configuration-cache compatibility.
