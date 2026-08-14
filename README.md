# Монорепозиторий spring-boot-platform-starters

Набор платформенных Spring Boot стартеров и сопутствующих библиотек с единой координатой **`space.br1440.platform`** и общим релизным циклом (см. `build.gradle` корня).

## Сборка и плагины

- Сборка **только из корня репозитория**: `./gradlew <задача>` или `./gradlew :<путь-проекта>:<задача>`.
- В корневом [`settings.gradle`](./settings.gradle) в блоке `pluginManagement` подключён **`includeBuild('build-logic')`** — оттуда берутся convention-плагины вида `platform.java-library-publish`, `platform.java-platform-bom` и др. Без этой строки Gradle не найдёт плагины `platform.*`.
- Каталог версий для сборки: [`gradle/libs.versions.toml`](./gradle/libs.versions.toml). SpringBoot-приложениям, которые используют стартеры платформы, он не поставляется; опубликованные POM содержат уже разрешённые версии.

## Состав репозитория

### Платформенный BOM

| Модуль | Описание |
|--------|----------|
| [`spring-boot-starter-platform-bom`](./spring-boot-starter-platform-bom) | Bill of Materials: импорт `spring-boot-dependencies` и дополнительные constraints для зависимостей стартеров |

Подключение у потребителя: `api platform("space.br1440.platform:spring-boot-starter-platform-bom:<версия>")` (или эквивалент в Maven).

### Стартеры — отдельные каталоги в корне

| Модуль | Документация |
|--------|---------------|
| [`spring-boot-starter-platform-logging`](./spring-boot-starter-platform-logging) | [README](./spring-boot-starter-platform-logging/README.md) |
| [`spring-boot-starter-platform-metrics`](./spring-boot-starter-platform-metrics) | [README](./spring-boot-starter-platform-metrics/README.md) |
| [`spring-boot-starter-platform-leader-election`](./spring-boot-starter-platform-leader-election) | [README](./spring-boot-starter-platform-leader-election/README.md) |
| [`spring-boot-starter-platform-health-checks`](./spring-boot-starter-platform-health-checks) | [README](./spring-boot-starter-platform-health-checks/README.md) |
| [`spring-boot-starter-platform-security`](./spring-boot-starter-platform-security) | [README](./spring-boot-starter-platform-security/README.md) |
| [`spring-boot-starter-platform-postgres-read-write-split-jdbc`](./spring-boot-starter-platform-postgres-read-write-split-jdbc) | [README](./spring-boot-starter-platform-postgres-read-write-split-jdbc/README.md) |

### Обработка ошибок (каталог `spring-boot-starter-platform-error-handling/`)

Физически лежат в одной папке; в Gradle каждый артефакт — отдельный проект с префиксом `spring-boot-starter-platform-error-handling-`:

| Gradle-проект | Каталог |
|-----------------|---------|
| `:spring-boot-starter-platform-error-handling-model` | `error-handling-model/` |
| `:spring-boot-starter-platform-error-handling-core` | `error-handling-core/` |
| `:spring-boot-starter-platform-error-handling-mvc` | `error-handling-mvc/` |
| `:spring-boot-starter-platform-error-handling-webflux` | `error-handling-webflux/` |

### Трассировка (`platform-starters-tracing/`)

Все артефакты семейства публикуются в группе `space.br1440.platform.starters.tracing`.

| Gradle-проект | Каталог |
|-----------------|---------|
| `:platform-starters-tracing:spring-boot-starter-platform-tracing-api` | [`spring-boot-starter-platform-tracing-api`](platform-starters-tracing/spring-boot-starter-platform-tracing-api) |
| `:platform-starters-tracing:spring-boot-starter-platform-tracing-otel` | [`spring-boot-starter-platform-tracing-otel`](platform-starters-tracing/spring-boot-starter-platform-tracing-otel) |
| `:platform-starters-tracing:spring-boot-starter-platform-tracing-autoconfigure` | [`spring-boot-starter-platform-tracing-autoconfigure`](platform-starters-tracing/spring-boot-starter-platform-tracing-autoconfigure) |
| `:platform-starters-tracing:spring-boot-starter-platform-tracing-autoconfigure-webmvc` | [`spring-boot-starter-platform-tracing-autoconfigure-webmvc`](platform-starters-tracing/spring-boot-starter-platform-tracing-autoconfigure-webmvc) |
| `:platform-starters-tracing:spring-boot-starter-platform-tracing-autoconfigure-webflux` | [`spring-boot-starter-platform-tracing-autoconfigure-webflux`](platform-starters-tracing/spring-boot-starter-platform-tracing-autoconfigure-webflux) |
| `:platform-starters-tracing:spring-boot-starter-platform-tracing-mvc` | [`spring-boot-starter-platform-tracing-mvc`](platform-starters-tracing/spring-boot-starter-platform-tracing-mvc) |
| `:platform-starters-tracing:spring-boot-starter-platform-tracing-webflux` | [`spring-boot-starter-platform-tracing-webflux`](platform-starters-tracing/spring-boot-starter-platform-tracing-webflux) |
| `:platform-starters-tracing:platform-tracing-testkit` | [`platform-tracing-testkit`](platform-starters-tracing/platform-tracing-testkit) — внутренние тестовые fixtures, не публикуются |
| `:platform-starters-tracing:platform-tracing-otel-javaagent-extension` | [`platform-tracing-otel-javaagent-extension`](platform-starters-tracing/platform-tracing-otel-javaagent-extension) |

Controlled Platform Agent поставляется classifier-артефактом
`platform-tracing-otel-javaagent-extension:<version>:controlled-agent@jar`.

[`platform-tracing-collector-config`](platform-starters-tracing/platform-tracing-collector-config)
временно хранится в Git только для передачи SRE. Это не Gradle-проект и не часть сборки,
BOM или публикации tracing-платформы.

### Общие стартеры (каталог `spring-boot-starter-platform-common/`)

Корневого `build.gradle` у каталога **нет**: модули объявлены в корневом `settings.gradle`, как и для error-handling.

| Gradle-проект | Каталог | Документация |
|----------------|---------|----------------|
| `:spring-boot-starter-platform-common-uid1440` | `spring-boot-starter-uid1440/` | [README](platform-starters-common/spring-boot-starter-uid1440/README.md) |
| `:spring-boot-starter-platform-common-header-based-routing` | `spring-boot-starter-header-based-routing/` | [README](platform-starters-common/spring-boot-starter-header-based-routing/README.md) |
| `:spring-boot-starter-platform-common-x-trace-id` | `spring-boot-starter-x-trace-id/` | [README](platform-starters-common/spring-boot-starter-x-trace-id/README.md) |

## Публикация

Задачи в корневом `build.gradle`:

- `publishAllToNexus` — публикация всех подпроектов, у которых настроен `publish`;
- `publishAllToMavenLocal` — то же в локальный Maven.

Конфигурация репозиториев Nexus задаётся в convention-плагине `platform.java-library-publish` / в BOM-модуле.

## Полный список Gradle-проектов

Актуальный перечень: `./gradlew projects`.
