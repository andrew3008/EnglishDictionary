# Монорепозиторий spring-boot-platform-starters

Набор платформенных Spring Boot стартеров и сопутствующих библиотек с общим релизным циклом. Публикуемые координаты разделены по семействам и начинаются с `space.br1440.platform.starters` (см. `build.gradle` корня и build-файлы модулей).

## Сборка и плагины

- Сборка **только из корня репозитория**: `./gradlew <задача>` или `./gradlew :<путь-проекта>:<задача>`.
- Convention-плагины `platform.*` версии `1.0.4` загружаются из Nexus-репозиториев, настроенных в корневом [`settings.gradle`](./settings.gradle). Локальный composite build в репозитории не подключён. `mavenLocal()` доступен только как локальный opt-in через `-PallowMavenLocal=true` и запрещён в CI.
- Каталог версий для сборки: [`gradle/libs.versions.toml`](./gradle/libs.versions.toml). SpringBoot-приложениям, которые используют стартеры платформы, он не поставляется; опубликованные POM содержат уже разрешённые версии.

## Состав репозитория

### Платформенный BOM

| Модуль | Описание |
|--------|----------|
| [`spring-boot-starter-platform-bom`](./spring-boot-starter-platform-bom) | Bill of Materials: импорт `spring-boot-dependencies` и дополнительные constraints для зависимостей стартеров |

Подключение у потребителя: `api platform("space.br1440.platform.starters:spring-boot-starter-platform-bom:<версия>")` (или эквивалент в Maven).

### Стартеры — отдельные каталоги в корне

| Модуль | Документация |
|--------|---------------|
| [`spring-boot-starter-platform-logging`](./spring-boot-starter-platform-logging) | [README](./spring-boot-starter-platform-logging/README.md) |
| [`spring-boot-starter-platform-metrics`](./spring-boot-starter-platform-metrics) | [README](./spring-boot-starter-platform-metrics/README.md) |
| [`spring-boot-starter-platform-leader-election`](./spring-boot-starter-platform-leader-election) | [README](./spring-boot-starter-platform-leader-election/README.md) |
| [`spring-boot-starter-platform-health-checks`](./spring-boot-starter-platform-health-checks) | [README](./spring-boot-starter-platform-health-checks/README.md) |
| [`spring-boot-starter-platform-security`](./spring-boot-starter-platform-security) | [README](./spring-boot-starter-platform-security/README.md) |
| [`spring-boot-starter-platform-postgres-read-write-split-jdbc`](./spring-boot-starter-platform-postgres-read-write-split-jdbc) | [README](./spring-boot-starter-platform-postgres-read-write-split-jdbc/README.md) |

### Обработка ошибок (`platform-starters-error-handling/`)

Все артефакты семейства публикуются в группе `space.br1440.platform.starters.errorhandling`.

| Gradle-проект | Каталог |
|-----------------|---------|
| `:platform-starters-error-handling:platform-error-handling-core` | [`platform-error-handling-core`](platform-starters-error-handling/platform-error-handling-core) |
| `:platform-starters-error-handling:spring-boot-starter-platform-error-handling-mvc` | [`spring-boot-starter-platform-error-handling-mvc`](platform-starters-error-handling/spring-boot-starter-platform-error-handling-mvc) |
| `:platform-starters-error-handling:spring-boot-starter-platform-error-handling-webflux` | [`spring-boot-starter-platform-error-handling-webflux`](platform-starters-error-handling/spring-boot-starter-platform-error-handling-webflux) |

### Трассировка (`platform-starters-tracing/`)

Публикуемые application-артефакты семейства и controlled javaagent используют группу `space.br1440.platform.starters.tracing`. Внутренние сборочные варианты Maven-координат не имеют.

| Gradle-проект | Каталог |
|-----------------|---------|
| `:platform-starters-tracing:spring-boot-starter-platform-tracing-api` | [`spring-boot-starter-platform-tracing-api`](platform-starters-tracing/spring-boot-starter-platform-tracing-api) |
| `:platform-starters-tracing:platform-tracing-runtime-policy` | [`platform-tracing-runtime-policy`](platform-starters-tracing/platform-tracing-runtime-policy) |
| `:platform-starters-tracing:spring-boot-starter-platform-tracing-otel` | [`spring-boot-starter-platform-tracing-otel`](platform-starters-tracing/spring-boot-starter-platform-tracing-otel) |
| `:platform-starters-tracing:spring-boot-starter-platform-tracing-autoconfigure` | [`spring-boot-starter-platform-tracing-autoconfigure`](platform-starters-tracing/spring-boot-starter-platform-tracing-autoconfigure) |
| `:platform-starters-tracing:spring-boot-starter-platform-tracing-autoconfigure-webmvc` | [`spring-boot-starter-platform-tracing-autoconfigure-webmvc`](platform-starters-tracing/spring-boot-starter-platform-tracing-autoconfigure-webmvc) |
| `:platform-starters-tracing:spring-boot-starter-platform-tracing-autoconfigure-webflux` | [`spring-boot-starter-platform-tracing-autoconfigure-webflux`](platform-starters-tracing/spring-boot-starter-platform-tracing-autoconfigure-webflux) |
| `:platform-starters-tracing:spring-boot-starter-platform-tracing-mvc` | [`spring-boot-starter-platform-tracing-mvc`](platform-starters-tracing/spring-boot-starter-platform-tracing-mvc) |
| `:platform-starters-tracing:spring-boot-starter-platform-tracing-webflux` | [`spring-boot-starter-platform-tracing-webflux`](platform-starters-tracing/spring-boot-starter-platform-tracing-webflux) |
| `:platform-starters-tracing:platform-tracing-testkit` | [`platform-tracing-testkit`](platform-starters-tracing/platform-tracing-testkit) — внутренние тестовые fixtures, не публикуются |
| `:platform-starters-tracing:platform-tracing-otel-javaagent-extension` | [`platform-tracing-otel-javaagent-extension`](platform-starters-tracing/platform-tracing-otel-javaagent-extension) — внутренний модуль исходного кода и тестов; упаковкой управляет distribution, отдельно не публикуется |
| `:platform-starters-tracing:platform-tracing-otel-javaagent-distribution` | [`platform-tracing-otel-javaagent-distribution`](platform-starters-tracing/platform-tracing-otel-javaagent-distribution) — единственный владелец deployable-артефакта `platform-tracing-javaagent` |
| `:platform-starters-tracing:platform-tracing-gradle-plugin` | [`platform-tracing-gradle-plugin`](platform-starters-tracing/platform-tracing-gradle-plugin) — Gradle-плагин `platform.tracing-javaagent`; build tooling, не входит в BOM |

Controlled Platform Agent поставляется артефактом
`space.br1440.platform.starters.tracing:platform-tracing-javaagent:<version>`.

Это один готовый к запуску JAR: полный OpenTelemetry Java Agent `2.30.0` со вложенным
`extensions/platform-tracing-otel-javaagent-extension.jar`. Extension не нужно и нельзя
передавать отдельно через `otel.javaagent.extensions`. Версия агента должна точно совпадать
с версией starter/BOM release train; сам агент не входит в application BOM. Deployment
закрепляет GAV и SHA-256 JAR, проверяет digest до старта JVM и запускает его на Java 21.

Сервис подключает агент одной строкой в `plugins {}`. Версия и SHA-256 принадлежат
релизу плагина и совпадают с версией агента:

```groovy
plugins {
    id 'platform.tracing-javaagent' version '<V>'
}
```

Плагин резолвит агент вне application classpath, сверяет SHA-256 со вшитым pin,
кладёт JAR в `build/platform-tracing/agent/platform-tracing-javaagent-<V>.jar`
и пишет `build/platform-tracing/docker-build-args.env` с `PLATFORM_AGENT_JAR`.
Публичная задача — `prepareControlledAgent`. `bootJar` и `assemble` агент не тянут.
`bootRun` получает `-javaagent` на проверенный файл и `-Dplatform.tracing.controlled-agent.expected-sha256`
из того же вшитого pin — вручную прописывать SHA-256 в JVM Options локально не нужно.

Это build-time верификация. Runtime-проверка SHA-256 до старта JVM, mount path
и delivery mechanism остаются за deployment/GitOps-слоем и в этом репозитории
не реализованы.

Стартовая проверка в `spring-boot-starter-platform-tracing-autoconfigure`
(`AgentStartupVerifier`) сопоставляет прочитанный readiness snapshot агента с флагом
`platform.tracing.enabled`. Если `platform.tracing.enabled=false`, а readiness snapshot
уже прочитан и классифицирован не как `DISABLED` (агент версионно несовместим, terminal
`FAILED` с непустым `failureCode` и т.д.), итоговая причина отказа — `CONTROLLED_AGENT_FORBIDDEN`;
она перекрывает и `failureCode` агента, и reason классификатора. При `platform.tracing.enabled=true`
приоритет прежний: сначала `failureCode` агента, затем reason классификатора. Правило ограничено
веткой обработки уже прочитанного snapshot и не распространяется на `DUAL_SDK_COLLISION` и на
ошибки чтения readiness-эндпоинта.

Контракт ownership, развёртывание и release/rollback:

- [`06-adr-javaagent-distribution-ownership.md`](../Platform_Traces_Docs/architecture/agent-runtime/06-adr-javaagent-distribution-ownership.md);
- [`07-controlled-javaagent-deployment-contract.md`](../Platform_Traces_Docs/architecture/agent-runtime/07-controlled-javaagent-deployment-contract.md);
- [`08-controlled-javaagent-release-and-rollback-runbook.md`](../Platform_Traces_Docs/architecture/agent-runtime/08-controlled-javaagent-release-and-rollback-runbook.md).

До назначения fleet/SRE owner, фиксации реального chart/GitOps path, подтверждения Nexus
stage/promote и прохождения Gentoo qualification 54/54 первый stable имеет статус **NO-GO**.

[`platform-tracing-collector-config`](platform-starters-tracing/platform-tracing-collector-config)
временно хранится в Git только для передачи SRE. Это не Gradle-проект и не часть сборки,
BOM или публикации tracing-платформы.

### Общие модули (`platform-starters-common/`)

Имена Gradle-проектов не совпадают с каталогами, поэтому для них задан явный `projectDir`.

| Gradle-проект | Каталог | Документация |
|----------------|---------|----------------|
| `:platform-starters-common:platform-starters-common-util` | [`platform-starters-common-util`](platform-starters-common/platform-starters-common-util) | [README](platform-starters-common/platform-starters-common-util/README.md) |
| `:platform-starters-common:spring-boot-starter-platform-common-uid1440` | [`spring-boot-starter-uid1440`](platform-starters-common/spring-boot-starter-uid1440) | [README](platform-starters-common/spring-boot-starter-uid1440/README.md) |
| `:platform-starters-common:spring-boot-starter-platform-common-header-based-routing` | [`spring-boot-starter-header-based-routing`](platform-starters-common/spring-boot-starter-header-based-routing) | [README](platform-starters-common/spring-boot-starter-header-based-routing/README.md) |
| `:platform-starters-common:spring-boot-starter-platform-common-x-trace-id` | [`spring-boot-starter-x-trace-id`](platform-starters-common/spring-boot-starter-x-trace-id) | [README](platform-starters-common/spring-boot-starter-x-trace-id/README.md) |

## Публикация

Задачи в корневом `build.gradle`:

- `publishAllToNexus` — публикация всех подпроектов, у которых настроен `publish`;
- `publishAllToMavenLocal` — то же в локальный Maven.

Конфигурация репозиториев Nexus и агрегаторы `publishAllToNexus` / `publishAllToMavenLocal`
задаются convention-плагином `platform.java-library-publish`, для BOM — BOM-модулем.
Controlled javaagent публикуется той же convention-публикацией `mavenJava`: в неё
подставляется собранный `platform-tracing-javaagent` JAR, без отдельного extension-артефакта.

## Полный список Gradle-проектов

Актуальный перечень: `./gradlew projects`.
