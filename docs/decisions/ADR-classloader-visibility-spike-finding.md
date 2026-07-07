# ADR: ClassLoader Visibility Spike — custom SensitiveDataRule loading

| Поле | Значение |
|------|----------|
| Статус | **Принято (Вариант B); production spike удалён** |
| Дата | 2026-06-07; migration 2026-06-17 |
| Probe (historical) | `ClassLoaderVisibilitySpikeE2ETest`, `ClassLoaderVisibilitySpikeProbe` (удалены) |
| Probe (current) | `ClassLoaderVisibilityTestProbe` (test-only extension JAR, `platform-tracing-e2e-tests/src/testExtension`) |
| Среда | Windows (local JVM) + Gentoo Linux x86_64 (Docker на `192.168.100.70:2375`, `eclipse-temurin:21-jdk`), OTel Java Agent 2.27.0 |

## Проблема

`PlatformSpanProcessorFactory.appendSpiRulesFromExtensions()` использует `URLClassLoader` поверх путей из `otel.javaagent.extensions`, чтобы найти `SensitiveDataRule` в sibling extension JAR. Это решает видимость, но может приводить к **двойной загрузке** JAR, уже загруженных OTel Agent как extensions.

## Метод spike

Agent-runtime E2E (без Testcontainers):

```
java -javaagent:opentelemetry-javaagent.jar
     -Dotel.javaagent.extensions=<dir-with-extension.jar+custom-rule.jar>
     -Dplatform.tracing.spike.classloader.visibility=true
     -cp <test-runtime> ClassLoaderVisibilitySpikeMain
```

Probe в `PlatformSpanProcessorFactory` (только при spike-флаге) проверяет 4 варианта `ServiceLoader`:

1. `ServiceLoader.load(SensitiveDataRule.class)` — effective TCCL
2. `ServiceLoader.load(..., Thread.currentThread().getContextClassLoader())`
3. `ServiceLoader.load(..., PlatformSpanProcessorFactory.class.getClassLoader())`
4. `ServiceLoader.load(..., SensitiveDataRule.class.getClassLoader())`

Baseline: текущий `URLClassLoader` workaround в `appendSpiRulesFromExtensions`.

## Findings

### F1. Нативный ServiceLoader **не видит** sibling custom-rules JAR

| Вариант | ClassLoader | `custom-e2e-rule` найден |
|---------|-------------|--------------------------|
| default (TCCL) | `AppClassLoader` | **нет** |
| tccl | `AppClassLoader` | **нет** |
| factory | `ExtensionClassLoader` | **нет** |
| api | `ExtensionClassLoader` | **нет** |

`PlatformSpanProcessorFactory` и `SensitiveDataRule` API живут в **одном** `io.opentelemetry.javaagent.tooling.ExtensionClassLoader` (platform extension JAR). Custom-rules JAR в той же директории `otel.javaagent.extensions` через нативный `ServiceLoader` **не обнаруживается**.

**Вывод:** утверждение «достаточно TCCL / factory ClassLoader» — **опровергнуто** spike'ом.

### F2. URLClassLoader workaround **работает**, но грузит все JAR из директории

При `otel.javaagent.extensions=<dir>` workaround сканирует директорию и создаёт `URLClassLoader` над **всеми** `.jar`:

```
loading from URLs = [custom-rule.jar, extension.jar]
found rule = custom-e2e-rule
```

Это подтверждает:
- custom rule находится только через явный `URLClassLoader`;
- в URLClassLoader попадает и **platform extension JAR** — риск двойной загрузки platform classes, не только custom rules.

### F3. Рекомендуемая архитектура: **Вариант B**

```
otel.javaagent.extensions = platform-tracing-otel-extension-agent.jar   # только platform extension
platform.tracing.scrubbing.rules.extensions = /path/to/custom-rules.jar   # только custom rules
```

Контракт для команд:

- `custom-rules.jar` — **не** OTel Java Agent extension;
- `custom-rules.jar` — platform scrubbing plugin;
- **запрещено** указывать один и тот же JAR в обоих свойствах одновременно.

`URLClassLoader` допустим **только** для путей из `platform.tracing.scrubbing.rules.extensions`, не для повторного сканирования `otel.javaagent.extensions`.

### F4. Отклонённые варианты

| Вариант | Причина отклонения |
|---------|-------------------|
| Удалить URLClassLoader, полагаться на TCCL/factory CL | Spike: `nativeVisible=false` |
| Оставить сканирование `otel.javaagent.extensions` | Двойная загрузка platform + custom JAR |
| Заставить команды писать `AutoConfigurationCustomizerProvider` | Ухудшение DX |

## Решение для PR-2 (ClassLoader Fix)

1. Добавить свойство `platform.tracing.scrubbing.rules.extensions` (comma-separated paths, JAR или directory).
2. Загружать custom rules **только** из этого свойства через `URLClassLoader` (parent = API ClassLoader).
3. Убрать парсинг `otel.javaagent.extensions` из `appendSpiRulesFromExtensions`.
4. Обновить E2E (`CustomRuleSmokeE2ETest`): custom-rules JAR — через новое свойство, platform extension — через `otel.javaagent.extensions`.
5. Startup diagnostics: `loadingMode=PLATFORM_RULES_EXTENSIONS`, `customRules=N`.

## Кросс-платформенное подтверждение (Gentoo Linux)

Повторный прогон в Linux-контейнере на Gentoo Docker host (`DOCKER_HOST=tcp://192.168.100.70:2375`):

```bash
# scripts/run-classloader-spike-gentoo.ps1
docker run eclipse-temurin:21-jdk
java -javaagent:opentelemetry-javaagent-2.27.0.jar -Dotel.javaagent.extensions=/spike/ext ...
```

| Платформа | nativeVisible | workaroundVisible | recommendation |
|-----------|---------------|-------------------|----------------|
| Windows (local JVM) | false | true | USE_PLATFORM_RULES_EXTENSIONS |
| Gentoo Linux (Docker container) | false | true | USE_PLATFORM_RULES_EXTENSIONS |

На Gentoo Linux ClassLoader-типы идентичны: `ExtensionClassLoader` для factory/api, `AppClassLoader` для TCCL; нативный `ServiceLoader` не видит `custom-e2e-rule`.

## Audit

```bash
# Windows/local E2E
./gradlew :platform-tracing-e2e-tests:test -PrunE2e \
  --tests "space.br1440.platform.tracing.e2e.spike.ClassLoaderVisibilitySpikeE2ETest"

# Gentoo Linux container
DOCKER_HOST=tcp://192.168.100.70:2375 pwsh scripts/run-classloader-spike-gentoo.ps1
```

Ожидаемый вывод:

```
SPIKE_RESULT nativeVisible=false
SPIKE_RESULT workaroundVisible=true
SPIKE_RESULT recommendation=USE_PLATFORM_RULES_EXTENSIONS
```

## Связанные артефакты

- `ClassLoaderVisibilitySpikeProbe.java` — **удалён** (2026-06-17); заменён test-only probe JAR
- `ClassLoaderVisibilitySpikeE2ETest.java` — **удалён**; заменён `ClassLoaderVisibilityE2ETest`

## Migration (2026-06-17)

`ClassLoaderVisibilitySpikeProbe` удалён из `platform-tracing-otel-extension/src/main`.
Пакет `factory.spike` удалён. Свойство `platform.tracing.spike.classloader.visibility` и
маркер-prefix `SPIKE_CLASSLOADER:` удалены из активного кода.

- **F1** верифицируется через `ClassLoaderVisibilityTestProbe` —
  test-only extension JAR (`platform-tracing-e2e-tests/src/testExtension`),
  загружаемый через `otel.javaagent.extensions` в дочерней JVM E2E-теста.
  Маркер-prefix: `CL_VISIBILITY:`. Тест: `ClassLoaderVisibilityE2ETest`.
  Не доказывает production `ExtensionRuleLoader` semantics; optional mechanism smoke
  (`mechanismCustomRules`, `mechanismLoadingMode`) — probe-side URLClassLoader only.
- **F3 (production)** верифицируется через `CustomRuleSmokeE2ETest`:
  custom rule загружается production `ExtensionRuleLoader` и применяется в scrubbing pipeline
  (маскирование атрибута span в Jaeger). `StartupDiagnostics` не используется как E2E assertion
  (SLF4J в ExtensionClassLoader агента 2.28.x без binding).
- Gentoo script `scripts/run-classloader-spike-gentoo.ps1` удалён.
