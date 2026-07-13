# E2E-тесты: падающие сценарии и runbook запуска

Date: 2026-07-12  
Repository: `E:\Platform_Traces`  
Module: `platform-tracing-e2e-tests`

## 2026-07-12 E2E hardening update

This section supersedes the older failure counts below.

| Area | Result |
| --- | --- |
| Remote Docker | **PASS**. `DOCKER_HOST=tcp://192.168.100.70:2375`; Docker Server `28.0.4`, API `1.48`, OS `Gentoo Linux`; `docker ps` and `docker run --rm hello-world` succeed. |
| Collector DNS -> IP audit | **PASS**. Active collector paths inject Jaeger's container network IP via `JAEGER_OTLP_GRPC_ENDPOINT` / `OTEL_EXPORTER_OTLP_ENDPOINT`; `jaeger:4317` remains only as a YAML fallback. |
| Collector-based tests | **PASS** isolated: `TracingE2ETest`, `ExceptionEventScrubbingE2ETest`, `CollectorProductionPolicyE2ETest`. |
| Agent direct Jaeger endpoints | **PASS audit**. Child JVM agent runners use host-mapped Jaeger OTLP HTTP endpoints (`http://192.168.100.70:<mapped-4318>/v1/traces`), which is correct for Windows host -> remote Gentoo Docker. |
| Baseline vs extension | **PASS after deterministic sampler fix**. `DbSemconvAgentSmokeTest` passes without extension; `PlatformExtensionAgentSmokeTest`, `ResourceIdentityAgentSmokeE2ETest`, and `ReactorContextPropagationAgentE2ETest` pass when tests set `platform.tracing.sampling.ratio=1.0`. |
| Force-header/platform sampler | **PASS after config-default fix**. `X-Trace-On=on` at `platform.tracing.sampling.ratio=0` now records the server span through the named `platform` sampler. |
| Full E2E latest full run | **PASS**. `.\gradlew.bat :platform-tracing-e2e-tests:test -PrunE2e --no-daemon --info --rerun-tasks` completed successfully on 2026-07-12. |
| Remaining blocker | **None confirmed** for Docker-backed E2E. The previous force-header blocker was traced to configuration default binding, not Docker/Testcontainers/Jaeger/exporter behavior. |

Minimal test hardening applied:

- `PlatformExtensionAgentSmokeTest` now sets `platform.tracing.sampling.ratio=1.0` and includes endpoint/span/agent-output diagnostics when the JDBC span is absent.
- `ResourceIdentityAgentSmokeProcessRunner` now sets `platform.tracing.sampling.ratio=1.0`.
- `ReactorContextPropagationAgentE2ETest` now sets `platform.tracing.sampling.ratio=1.0`.

## 2026-07-12 Force-header closure update

The final remaining Docker-backed E2E blocker was `ForceSamplingAgentSmokeTest`: the child Spring JVM received `X-Trace-On=on`, ran with `otel.traces.sampler=platform`, `otel.propagators=tracecontext,baggage,platform-trace-control`, and `platform.tracing.sampling.ratio=0`, but Jaeger still returned `spanNames=[]`.

Diagnostic classification:

- Request/header absent: **No**. The smoke runner logged `SMOKE_REQUEST_HEADERS={X-Trace-On=on}`.
- Wrong wire header/value: **No**. The propagator extracted force value `on`.
- Propagator missing: **No**. The child JVM used `tracecontext,baggage,platform-trace-control`.
- Context lost before sampler: **No**. The sampler observed the extracted force value.
- Sampler missing: **No**. The named `platform` sampler was invoked for the server span.
- Rule priority bug: **No**. The force-header rule runs before the default-ratio rule.
- Exporter/Jaeger/Docker issue: **No**. Baseline, collector, DB, resource, reactor, and platform extension smokes passed through the same remote Docker/Jaeger path.
- Root cause: **configuration default binding bug** in `ExtensionConfigReader.listValue(...)`.

Actual root cause:

`DefaultConfigProperties.getList(name)` can return an empty list for an absent list property. `ExtensionConfigReader.listValue(...)` treated that empty list as an explicit configured value, so `SamplingExtensionConfig.forceRecordValues` became empty instead of the intended default `["on"]`. The force-header rule then abstained, and the default ratio `0` dropped the span.

Fix:

- `ExtensionConfigReader.listValue(...)` now falls back to the supplied default when `getList(name)` is empty and `getString(name)` is absent.
- Explicit empty list configuration remains distinguishable because a present raw string is not treated as absent.
- `PlatformSamplerProviderTest` now has a regression test proving that absent `platform.tracing.sampling.force-record-values` still force-records `X-Trace-On=on` at ratio `0`.
- The force smoke explicitly sets the named platform sampler and platform trace-control propagator.
- E2E smoke diagnostics now print JVM properties, request route, request headers, and sampler properties when failures need triage.

Verification after the fix:

```powershell
$env:DOCKER_HOST = "tcp://192.168.100.70:2375"
.\gradlew.bat :platform-tracing-otel-extension:test --tests "space.br1440.platform.tracing.otel.extension.sampler.PlatformSamplerProviderTest" --tests "space.br1440.platform.tracing.otel.extension.sampler.SamplingPolicyDecisionOtelAdapterTest" --tests "space.br1440.platform.tracing.otel.extension.PlatformAutoConfigurationCustomizerTest" --no-daemon --info
.\gradlew.bat :platform-tracing-e2e-tests:test -PrunE2e --tests "space.br1440.platform.tracing.e2e.smoke.ForceSamplingAgentSmokeTest" --no-daemon --info --rerun-tasks
.\gradlew.bat :platform-tracing-e2e-tests:test -PrunE2e --tests "space.br1440.platform.tracing.e2e.smoke.ForceSamplingAgentSmokeTest" --tests "space.br1440.platform.tracing.e2e.smoke.PlatformSpiAgentSmokeTest" --tests "space.br1440.platform.tracing.e2e.smoke.RuntimeSamplingControlSmokeTest" --no-daemon --info --rerun-tasks
.\gradlew.bat :platform-tracing-e2e-tests:test -PrunE2e --tests "space.br1440.platform.tracing.e2e.smoke.DbSemconvAgentSmokeTest" --tests "space.br1440.platform.tracing.e2e.smoke.PlatformExtensionAgentSmokeTest" --tests "space.br1440.platform.tracing.e2e.smoke.ResourceIdentityAgentSmokeE2ETest" --tests "space.br1440.platform.tracing.e2e.smoke.ReactorContextPropagationAgentE2ETest" --no-daemon --info --rerun-tasks
.\gradlew.bat :platform-tracing-e2e-tests:test -PrunE2e --tests "space.br1440.platform.tracing.e2e.smoke.CustomRuleSmokeE2ETest" --tests "space.br1440.platform.tracing.e2e.probe.ClassLoaderVisibilityE2ETest" --tests "space.br1440.platform.tracing.e2e.smoke.MicrometerStatusMappingE2ETest" --no-daemon --info --rerun-tasks
.\gradlew.bat :platform-tracing-e2e-tests:test -PrunE2e --no-daemon --info --rerun-tasks
```

All commands above passed in the final verification pass. Remaining warnings are Gradle/JDK warnings only: deprecated Gradle features, `JsonInclude.Include.NON_EMPTY` annotation classpath warnings during compile, Spring WebFlux deprecated API note, SLF4J multiple providers in test classpath, and Mockito dynamic-agent warnings.

## Что было сделано для фикса и стабилизации E2E

Цель работы была не в том, чтобы отключить нестабильные проверки или ослабить assertions, а в том, чтобы отделить реальные проблемы в tracing runtime от инфраструктурных проблем remote Docker/Testcontainers на Gentoo. Итог: Docker/Jaeger/Collector-сеть проверена и исключена как причина основных падений; несколько E2E-сценариев сделаны детерминированными; оставшийся блокер локализован в поведении force-header/platform sampler.

### 1. Проверена доступность remote Docker

Сначала была проверена базовая доступность Docker daemon:

```powershell
$env:DOCKER_HOST = "tcp://192.168.100.70:2375"
docker version
docker ps
docker run --rm hello-world
```

Подтверждено:

- Docker daemon доступен по `tcp://192.168.100.70:2375`;
- сервер Docker: `28.0.4`;
- API: `1.48`;
- ОС daemon: `Gentoo Linux`;
- Testcontainers видит Docker host как `192.168.100.70`;
- `hello-world` успешно стартует и завершается.

Это исключило базовую проблему вида "E2E падают, потому что Docker недоступен".

### 2. Проверена схема Collector -> Jaeger

Была отдельно проверена гипотеза, что Collector внутри Docker-сети может не резолвить Docker DNS alias `jaeger` на Gentoo. Для этого были просмотрены:

- `platform-tracing-e2e-tests/src/test/resources/e2e/otel-collector-e2e.yaml`;
- `OtelCollectorTestContainerSupport`;
- `JaegerTestContainerSupport`;
- `CollectorProductionPolicyE2ETest`.

Результат аудита:

- активный путь Collector -> Jaeger использует IP контейнера Jaeger в Docker network;
- значение передается через `JAEGER_OTLP_GRPC_ENDPOINT` или `OTEL_EXPORTER_OTLP_ENDPOINT`;
- fallback `jaeger:4317` остался только в YAML и не используется в проверенных Gentoo E2E-прогонах.

После этого были изолированно прогнаны Collector-based тесты:

```powershell
.\gradlew.bat :platform-tracing-e2e-tests:test -PrunE2e --tests "space.br1440.platform.tracing.e2e.TracingE2ETest" --no-daemon --info
.\gradlew.bat :platform-tracing-e2e-tests:test -PrunE2e --tests "space.br1440.platform.tracing.e2e.ExceptionEventScrubbingE2ETest" --no-daemon --info
.\gradlew.bat :platform-tracing-e2e-tests:test -PrunE2e --tests "space.br1440.platform.tracing.e2e.CollectorProductionPolicyE2ETest" --no-daemon --info
```

Все три класса прошли. Это подтвердило, что путь SDK/JVM -> Collector -> Jaeger работает.

### 3. Проверен прямой export из agent smoke JVM в Jaeger

Agent smoke-тесты отличаются от Collector-тестов: дочерняя JVM с `-javaagent` запускается на Windows host, а Jaeger находится в remote Docker на Gentoo. Поэтому корректный endpoint для agent smoke — не Docker DNS и не container IP, а host-mapped порт Jaeger.

Были просмотрены runner'ы:

- `AgentJdbcSmokeProcessRunner`;
- `AgentHttpSpringSmokeProcessRunner`;
- `AgentWebFluxProcessRunner`;
- `ResourceIdentityAgentSmokeProcessRunner`;
- `AgentMdcLoggingProcessRunner`.

Подтверждено, что agent smoke JVM получает endpoint вида:

```text
http://192.168.100.70:<mapped-4318>/v1/traces
```

То есть дочерняя JVM на Windows ходит в Jaeger через mapped port remote Docker host. Это правильная схема для текущей инфраструктуры.

### 4. Сравнен baseline без extension и сценарий с extension

Для отделения проблем Jaeger/export от проблем extension был выполнен baseline-прогон:

```powershell
.\gradlew.bat :platform-tracing-e2e-tests:test -PrunE2e --tests "space.br1440.platform.tracing.e2e.smoke.DbSemconvAgentSmokeTest" --no-daemon --info --rerun-tasks
```

`DbSemconvAgentSmokeTest` прошел. Это доказало, что:

- Java agent стартует;
- JDBC instrumentation работает;
- OTLP/HTTP export до Jaeger работает;
- Jaeger Query API возвращает span'ы;
- remote Docker networking не является причиной отсутствия span'ов.

Затем был прогнан `PlatformExtensionAgentSmokeTest`. Он падал на отсутствии JDBC span в Jaeger. После добавления диагностик стало видно, что endpoint корректный, extension jar передан, agent стартует, но `spanNames=[]`.

Ключевая причина оказалась не в Docker, а в sampler path: extension подменяет sampler на platform sampler, а default `platform.tracing.sampling.ratio` равен `0.1`. Поэтому `OTEL_TRACES_SAMPLER=always_on` не делал тест детерминированным в присутствии platform extension.

### 5. Внесены минимальные тестовые фиксы

Чтобы не ослаблять проверки и не увеличивать timeout как основной фикс, были внесены точечные изменения в тесты, где сам сценарий ожидает обязательный export span'а.

#### `PlatformExtensionAgentSmokeTest`

Добавлено:

- сохранение `jaegerQueryBaseUrl` для диагностического сообщения;
- захват stdout/stderr дочерней agent JVM в `agentOutput`;
- вывод `otlpEndpoint`, `jaegerQuery`, `extensionJar`, `spanNames` и `agentOutput` при отсутствии JDBC span;
- явное свойство:

```text
platform.tracing.sampling.ratio=1.0
```

Это делает сценарий детерминированным: тест проверяет enrich/export behavior extension, а не случайное попадание в sampling ratio `0.1`.

#### `ResourceIdentityAgentSmokeProcessRunner`

Добавлено:

```text
platform.tracing.sampling.ratio=1.0
```

Также в runner присутствует явный traces endpoint:

```text
otel.exporter.otlp.traces.endpoint=<otlpEndpoint>/v1/traces
```

После этого `ResourceIdentityAgentSmokeE2ETest` прошел изолированно.

#### `ReactorContextPropagationAgentE2ETest`

Добавлено:

```text
platform.tracing.sampling.ratio=1.0
```

До фикса приложение корректно сохраняло propagation внутри Reactor chain, но HTTP span мог не попасть в Jaeger из-за platform sampler ratio. После фикса targeted rerun прошел.

### 6. Что было проверено после фиксов

Успешно прошли targeted reruns:

```powershell
.\gradlew.bat :platform-tracing-e2e-tests:test -PrunE2e --tests "space.br1440.platform.tracing.e2e.smoke.PlatformExtensionAgentSmokeTest" --no-daemon --info --rerun-tasks
.\gradlew.bat :platform-tracing-e2e-tests:test -PrunE2e --tests "space.br1440.platform.tracing.e2e.smoke.ResourceIdentityAgentSmokeE2ETest" --no-daemon --info --rerun-tasks
.\gradlew.bat :platform-tracing-e2e-tests:test -PrunE2e --tests "space.br1440.platform.tracing.e2e.smoke.ReactorContextPropagationAgentE2ETest" --no-daemon --info --rerun-tasks
```

Также ранее были подтверждены:

- baseline `DbSemconvAgentSmokeTest` — PASS;
- Collector path tests — PASS;
- Docker/Testcontainers connectivity — PASS.

### 7. Что осталось настоящим блокером

Свежий isolated rerun показал, что `ForceSamplingAgentSmokeTest` все еще падает:

```powershell
.\gradlew.bat :platform-tracing-e2e-tests:test -PrunE2e --tests "space.br1440.platform.tracing.e2e.smoke.ForceSamplingAgentSmokeTest" --no-daemon --info --rerun-tasks
```

Симптом:

```text
platform.tracing.sampling.ratio=0
X-Trace-On: on
spanNames=[]
```

Это уже не похоже на проблему Docker, Collector, Jaeger endpoint или timeout. Оставшийся блокер находится в поведении force-header/platform sampler: при ratio `0` принудительный header должен привести к записи span'а, но span в Jaeger не появляется.

Связанные сценарии из полного suite:

- `PlatformSpiAgentSmokeTest`;
- `RuntimeSamplingControlSmokeTest`.

### 8. Итог

Сделанные изменения не отключают E2E, не ослабляют assertions и не переводят проблему в более длинные ожидания. Они фиксируют недетерминированность тестов, где ожидается обязательный export, и документируют фактическую границу проблемы:

- remote Docker на Gentoo работает;
- Collector -> Jaeger работает через container IP;
- agent -> Jaeger работает через host-mapped OTLP HTTP endpoint;
- extension/resource/reactor smoke стали детерминированными и проходят;
- full E2E gate пока не зеленый из-за force-header/platform sampler behavior.

Документ описывает, как запускать E2E **по одному** и **полным suite**, какие тесты сейчас падают на remote Docker (Gentoo), и как их отлаживать без прогона всего модуля.

---

## 1. Предусловия

| Требование | Значение |
| --- | --- |
| Docker | Remote Gentoo: `192.168.100.70:2375` |
| Gradle property | **Обязательно** `-PrunE2e` (без него модуль пропускается) |
| JVM | JDK 21 (как в `gradle.properties`) |
| Параллельность | `maxParallelForks = 1` — тесты идут последовательно |

```powershell
$env:DOCKER_HOST = "tcp://192.168.100.70:2375"
cd E:\Platform_Traces
```

Проверка Docker:

```powershell
docker ps
docker run --rm hello-world
```

Gradle передаёт в тестовую JVM (см. `platform-tracing-e2e-tests/build.gradle`):

- `-Dotel.javaagent.jar=...` — OTel Java Agent 2.28.x
- `-Dsmoke.otel.extension.jar=...` — `platform-tracing-otel-extension-*-agent.jar`
- `-Dsmoke.test.runtime.classpath=...` — classpath дочерних JVM smoke-тестов
- `-Dsmoke.custom.rule.jar=...`, `-Dsmoke.test.classloader.probe.extension.jar=...`, `-Dsmoke.jmx.wire.extension.jar=...`

---

## 2. Полный suite

```powershell
$env:DOCKER_HOST = "tcp://192.168.100.70:2375"
.\gradlew.bat :platform-tracing-e2e-tests:test -PrunE2e --no-daemon
```

| Метрика | Значение |
| --- | --- |
| Классов | 22 |
| Методов `@Test` | 62 (актуальный подсчёт по исходникам) |
| Ожидаемое время | **~9–10 минут** на remote Docker |
| Последний полный прогон (PR-B2 audit) | 47 executed, **8 failed** (~9m25s) |

**Почему долго:**

1. Каждый agent smoke поднимает **дочернюю JVM** с `-javaagent` (premain нельзя в JUnit-процессе).
2. Testcontainers стартуют Jaeger / Postgres / Collector на remote daemon — сетевой round-trip.
3. `Awaitility` ждёт до **30 с** на появление span'а в Jaeger Query API.
4. Collector-тесты ждут `decision_wait` tail_sampling (**5 с**) + batch export.
5. `maxParallelForks = 1` — нет параллелизма.

**Отчёты после прогона:**

- XML: `platform-tracing-e2e-tests/build/test-results/test/`
- HTML: `platform-tracing-e2e-tests/build/reports/tests/test/index.html`

**Пропуск E2E без удаления property:**

```powershell
.\gradlew.bat :platform-tracing-e2e-tests:test -PrunE2e -DskipE2e=true
```

---

## 3. Запуск по одному тесту

### 3.1. Один класс целиком

```powershell
.\gradlew.bat :platform-tracing-e2e-tests:test -PrunE2e `
  --tests "space.br1440.platform.tracing.e2e.smoke.PlatformExtensionAgentSmokeTest" `
  --no-daemon
```

### 3.2. Один метод

```powershell
.\gradlew.bat :platform-tracing-e2e-tests:test -PrunE2e `
  --tests "space.br1440.platform.tracing.e2e.smoke.PlatformExtensionAgentSmokeTest.agent_with_extension_выставляет_platform_type_database" `
  --no-daemon
```

### 3.3. Wildcard по пакету

```powershell
# Все smoke-тесты
.\gradlew.bat :platform-tracing-e2e-tests:test -PrunE2e --tests "space.br1440.platform.tracing.e2e.smoke.*"

# SDK → Collector → Jaeger (без smoke)
.\gradlew.bat :platform-tracing-e2e-tests:test -PrunE2e --tests "space.br1440.platform.tracing.e2e.TracingE2ETest"
```

### 3.4. Рекомендуемые батчи для отладки

| Батч | Команда `--tests` | ~Время | Назначение |
| --- | --- | --- | --- |
| PR-B2 critical | `CustomRuleSmokeE2ETest`, `ClassLoaderVisibilityE2ETest`, `MicrometerStatusMappingE2ETest` | ~35 с | SPI rename wiring |
| Collector pipeline | `TracingE2ETest`, `ExceptionEventScrubbingE2ETest`, `CollectorProductionPolicyE2ETest` | ~2–3 мин | SDK/Collector/Jaeger + production YAML |
| Agent export baseline | `DbSemconvAgentSmokeTest` | ~30 с | Agent → Jaeger **без** extension |
| Agent export + extension | `PlatformExtensionAgentSmokeTest`, `ResourceIdentityAgentSmokeE2ETest` | ~1–2 мин | Agent + extension → Jaeger |
| Force sampling | `ForceSamplingAgentSmokeTest`, `PlatformSpiAgentSmokeTest`, `RuntimeSamplingControlSmokeTest` | ~3–5 мин | `X-Trace-On` + platform sampler |
| WebFlux / HTTP | `ReactorContextPropagationAgentE2ETest`, `DuplicateHttpSpanAgentSmokeTest` | ~2–3 мин | WebFlux propagation + dup spans |

Пример батча PR-B2 (три класса одной строкой — Gradle OR по классам):

```powershell
.\gradlew.bat :platform-tracing-e2e-tests:test -PrunE2e --tests "space.br1440.platform.tracing.e2e.smoke.CustomRuleSmokeE2ETest" --tests "space.br1440.platform.tracing.e2e.probe.ClassLoaderVisibilityE2ETest" --tests "space.br1440.platform.tracing.e2e.smoke.MicrometerStatusMappingE2ETest" --no-daemon
```

---

## 4. Каталог всех E2E-классов (62 теста)

### 4.1. SDK → Collector → Jaeger

| Класс | Тестов | Путь экспорта | Статус (2026-07-12) |
| --- | ---: | --- | --- |
| `TracingE2ETest` | 9 | JVM test → Collector OTLP/HTTP → Jaeger gRPC | **PASS** (full run) |
| `ExceptionEventScrubbingE2ETest` | 4 | JVM test → Collector → Jaeger | не верифицирован изолированно |
| `CollectorProductionPolicyE2ETest` | 7 | JVM test → production gateway YAML → Jaeger | не верифицирован после IP-fix |

### 4.2. Agent smokes (дочерняя JVM + `-javaagent`)

| Класс | Тестов | Extension | Статус |
| --- | ---: | --- | --- |
| `DbSemconvAgentSmokeTest` | 4 | нет | **PASS** (изолированный прогон 2026-07-12) |
| `PlatformExtensionAgentSmokeTest` | 2 | да | **FAIL** |
| `ResourceIdentityAgentSmokeE2ETest` | 3 | да | **FAIL** (2 метода в full run) |
| `ForceSamplingAgentSmokeTest` | 2 | да | **FAIL** |
| `PlatformSpiAgentSmokeTest` | 2 | да | **FAIL** |
| `RuntimeSamplingControlSmokeTest` | 2 | да | **FAIL** |
| `ReactorContextPropagationAgentE2ETest` | 2 | да | **FAIL** |
| `CustomRuleSmokeE2ETest` | 2 | custom rule JAR | **PASS** |
| `AgentMdcPlatformLoggingAgentE2ETest` | 3 | да | не в списке 8 failures |
| `AgentStatusMappingSmokeTest` | 2 | да | не в списке 8 failures |
| `DuplicateHttpSpanAgentSmokeTest` | 3 | да | не в списке 8 failures |
| `BspDropOldestSafetyAgentSmokeTest` | 1 | да | не в списке 8 failures |
| `BspOverflowSafetyAgentSmokeTest` | 1 | да | не в списке 8 failures |
| `CollectorUnavailableResilienceTest` | 1 | да | не в списке 8 failures |
| `OtelCollectorFileExporterSmokeTest` | 2 | нет | не в списке 8 failures |
| `MicrometerStatusMappingE2ETest` | 2 | Spring in-process | **PASS** |

### 4.3. Probe / wire (без Jaeger export assert)

| Класс | Тестов | Статус |
| --- | ---: | --- |
| `ClassLoaderVisibilityE2ETest` | 1 | **PASS** |
| `MapWireRoundTripE2ETest` | 1 | не в списке 8 failures |
| `WireRoundTripInProcessTest` | 6 | in-process, без Docker |

---

## 5. Падающие тесты — подробно

Ниже — **8 падений** из последнего полного `-PrunE2e` (PR-B2 post-audit).  
Эти тесты **не связаны** с rename `SensitiveDataRule` → `SpanAttributeScrubbingRule`.

### 5.1. `PlatformExtensionAgentSmokeTest`

**Команда:**

```powershell
.\gradlew.bat :platform-tracing-e2e-tests:test -PrunE2e `
  --tests "space.br1440.platform.tracing.e2e.smoke.PlatformExtensionAgentSmokeTest" --no-daemon
```

| Поле | Значение |
| --- | --- |
| Метод | `agent_with_extension_выставляет_platform_type_database()` |
| Цепочка | Agent + `platform-tracing-otel-extension` JAR → PostgreSQL JDBC → OTLP/HTTP → Jaeger |
| Sampler | `OTEL_TRACES_SAMPLER=always_on` (env) |
| Assert | JDBC span с `db.system`/`db.system.name` + `platform.trace.type=database` |
| Ошибка | `Optional empty` — span не найден в Jaeger за 30 с |
| Время | ~41–53 с |

**Контраст с baseline:** `DbSemconvAgentSmokeTest` (**PASS**) делает то же JDBC без extension (`extensionJar=null`).  
Гипотеза: при загрузке extension меняется sampler/export pipeline, и span не доходит до Jaeger.

**Ключевые файлы:**

- `platform-tracing-e2e-tests/.../smoke/PlatformExtensionAgentSmokeTest.java`
- `platform-tracing-e2e-tests/.../support/AgentJdbcSmokeProcessRunner.java`
- `platform-tracing-otel-extension/` — `EnrichingSpanProcessor`, SPI sampler

---

### 5.2. `ResourceIdentityAgentSmokeE2ETest` (2 падения)

**Команда:**

```powershell
.\gradlew.bat :platform-tracing-e2e-tests:test -PrunE2e `
  --tests "space.br1440.platform.tracing.e2e.smoke.ResourceIdentityAgentSmokeE2ETest" --no-daemon
```

| Метод | Assert | Ошибка |
| --- | --- | --- |
| `resource_identity_собирается_и_environment_нормализуется()` | Resource attrs: `service.name`, `deployment.environment.name=production`, `platform.c_group` | Resource не найден в Jaeger |
| `resource_ключи_не_дублируются_в_span_атрибутах()` | Span `resource-smoke-op` без resource-ключей на span | Span не найден |

**Цепочка:** Agent + extension → `PlatformResourceProvider` → OTLP/HTTP → Jaeger v3 Query (`findResourceAttributes` / `findSpanAttributesByName`).

**Гипотеза:** та же корневая причина, что и 5.1 — export с extension не виден в Jaeger (не query API bug: `DbSemconv` без extension работает).

---

### 5.3. `ForceSamplingAgentSmokeTest`

**Команда:**

```powershell
.\gradlew.bat :platform-tracing-e2e-tests:test -PrunE2e `
  --tests "space.br1440.platform.tracing.e2e.smoke.ForceSamplingAgentSmokeTest.agent_force_header_при_ratio_0_записывает_http_span" --no-daemon
```

| Поле | Значение |
| --- | --- |
| Сценарий | Spring Boot Web в дочерней JVM, HTTP GET `/probe` с `X-Trace-On: on` |
| Sampler | `otel.traces.sampler=platform`, `platform.tracing.sampling.ratio=0` |
| Propagators | `tracecontext,baggage,platform-trace-control` |
| Assert | HTTP span в Jaeger с `platform.sampling.reason=force_header` |
| Ошибка | `spanNames=[]` — ни одного span'а в Jaeger |
| Время | ~30–45 с ожидания + subprocess |

**Гипотеза:** `ForceHeaderRule` / `CompositeSampler` не записывает span при ratio=0, **или** span создаётся, но не экспортируется.

---

### 5.4. `PlatformSpiAgentSmokeTest`

**Команда:**

```powershell
.\gradlew.bat :platform-tracing-e2e-tests:test -PrunE2e `
  --tests "space.br1440.platform.tracing.e2e.smoke.PlatformSpiAgentSmokeTest.named_sampler_и_propagator_резолвятся_через_agent_spi" --no-daemon
```

Идентичен `ForceSamplingAgentSmokeTest` по конфигурации sampler/propagator; дополнительно проверяет **named SPI** резолв (`otel.traces.sampler=platform`).  
Та же ошибка: `spanNames=[]`.

---

### 5.5. `RuntimeSamplingControlSmokeTest`

**Команда:**

```powershell
.\gradlew.bat :platform-tracing-e2e-tests:test -PrunE2e `
  --tests "space.br1440.platform.tracing.e2e.smoke.RuntimeSamplingControlSmokeTest" --no-daemon
```

| Фаза | Действие | Ожидание |
| --- | --- | --- |
| 1 | startup `ratio=1.0`, 5× GET `/phase1` | 5 span'ов `/phase1` в Jaeger |
| 2 | JMX `ratio=0.0`, 5× GET `/phase2` + 1× с `X-Trace-On` | ровно **1** span `/phase2` с `force_header` |
| Ошибка (full run) | Фаза 2: expected 1 forced span, got **0** | |

Long-lived subprocess (`LongLivedAgentSmokeProcess`) — до **3 мин** startup timeout.

---

### 5.6. `ReactorContextPropagationAgentE2ETest`

**Команда:**

```powershell
.\gradlew.bat :platform-tracing-e2e-tests:test -PrunE2e `
  --tests "space.br1440.platform.tracing.e2e.smoke.ReactorContextPropagationAgentE2ETest.agent_webflux_publishOn_сохраняет_traceId_и_remote_service" --no-daemon
```

| Поле | Значение |
| --- | --- |
| In-app | **PASS** — `traceId` совпадает caller/worker, `remote.service=upstream-e2e-g205`, thread `parallel-*` |
| Jaeger | **FAIL** — HTTP span с `url.path` / `http.route` не найден |
| Ошибка | `Optional empty` после 30 с |

**Важно:** propagation в приложении работает; падает только **export visibility** в Jaeger.

---

## 6. Инфраструктурные особенности Gentoo

### 6.1. Remote Docker

- Testcontainers `getHost()` → IP daemon: `192.168.100.70` (не hostname).
- OTLP/HTTP с Windows-хоста: `http://192.168.100.70:<mapped-4318>/v1/traces`.

### 6.2. Collector → Jaeger (внутри docker-сети)

На Gentoo **внутренний Docker DNS между контейнерами ненадёжен** (дефект kernel).  
Исправление (2026-07-12):

| Компонент | Было | Стало |
| --- | --- | --- |
| `otel-collector-e2e.yaml` | `endpoint: jaeger:4317` | `${env:JAEGER_OTLP_GRPC_ENDPOINT:-jaeger:4317}` |
| `OtelCollectorTestContainerSupport` | — | env `JAEGER_OTLP_GRPC_ENDPOINT=<ip>:4317` |
| `CollectorProductionPolicyE2ETest` | `OTEL_EXPORTER_OTLP_ENDPOINT=jaeger:4317` | IP Jaeger из `JaegerTestContainerSupport.jaegerOtlpGrpcEndpointOnNetwork()` |
| `JaegerTestContainerSupport` | — | `containerNetworkIp()`, `jaegerOtlpGrpcEndpointOnNetwork()` |

Agent smokes (раздел 5) идут **напрямую в Jaeger**, минуя Collector — IP-fix их не затрагивает.

### 6.3. OTLP endpoint в agent runners

Во всех `*ProcessRunner` заданы оба свойства:

```
otel.exporter.otlp.endpoint=http://<host>:<port>
otel.exporter.otlp.traces.endpoint=http://<host>:<port>/v1/traces
otel.exporter.otlp.protocol=http/protobuf
```

Файлы: `AgentJdbcSmokeProcessRunner`, `AgentHttpSpringSmokeProcessRunner`, `AgentWebFluxProcessRunner`, `ResourceIdentityAgentSmokeProcessRunner`, `AgentMdcLoggingProcessRunner`.

---

## 7. Алгоритм отладки одного падающего теста

1. **Запустить один класс** (раздел 3.1) — ожидать 30–120 с, не полный suite.
2. **Прочитать HTML-отчёт** класса: `build/reports/tests/test/classes/<FQCN>.html`.
3. **Проверить `system-out`:** старт контейнеров, mapped ports, agent subprocess output.
4. **Сравнить с baseline:**

   ```powershell
   .\gradlew.bat :platform-tracing-e2e-tests:test -PrunE2e --tests "space.br1440.platform.tracing.e2e.smoke.DbSemconvAgentSmokeTest" --no-daemon
   ```

   Если baseline PASS, а extension-тест FAIL — проблема в extension/sampler, не в Docker/Jaeger connectivity.

5. **Для force-sampling** — смотреть `spanNames` в assert message и stdout дочерней JVM (`AgentHttpSpringSmokeProcessRunner` возвращает `agentOutput`).

6. **Не гонять полный suite** при итерации — только целевой класс/метод.

---

## 8. Рекомендуемый порядок triage

| Шаг | Тест | Почему первым |
| ---: | --- | --- |
| 1 | `DbSemconvAgentSmokeTest` | Baseline: Agent → Jaeger без extension |
| 2 | `PlatformExtensionAgentSmokeTest` | Минимальная дельта: +extension JAR |
| 3 | `ResourceIdentityAgentSmokeE2ETest` | Тот же export path + resource asserts |
| 4 | `ForceSamplingAgentSmokeTest` | Platform sampler + force header |
| 5 | `PlatformSpiAgentSmokeTest` | Дублирует шаг 4 (named SPI) |
| 6 | `RuntimeSamplingControlSmokeTest` | Runtime JMX + force header |
| 7 | `ReactorContextPropagationAgentE2ETest` | WebFlux + export (propagation уже OK) |
| 8 | `TracingE2ETest` + `CollectorProductionPolicyE2ETest` | Проверить IP-fix Collector→Jaeger |

---

## 9. Связанные документы

- `platform-tracing-e2e-tests/README.md` — краткий обзор модуля
- `docs/analysis/platform-tracing-api-pr-b2-post-audit.md` — вердикт architect gate (FAIL на full e2e)
- `docs/analysis/platform-tracing-api-pr-b2-warning-closure.md` — таблица 8 failures + PR-B2 PASS subset
- `platform-tracing-e2e-tests/build.gradle` — `-PrunE2e`, system properties, `maxParallelForks=1`

---

## 10. Краткий статус (2026-07-12)

| Группа | Статус |
| --- | --- |
| PR-B2 SPI rename + critical E2E | **PASS** |
| Full `-PrunE2e` (architect zero-warning gate) | **FAIL** — 8 smoke failures |
| Collector DNS → IP fix (Gentoo) | **Implemented**, требует верификации collector-тестов |
| Agent export с extension / platform sampler | **Открыто** — основной блокер full suite |
