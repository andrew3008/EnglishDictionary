# Config Server Property Provenance Spike

| Поле | Значение |
|------|----------|
| **Step 0 overall** | **PASS**, **COMPLETED** |
| **Step 0A — source/API design analysis** | **PASS**, **COMPLETED** |
| **Step 0B — executable provenance characterization** | **PASS**, **COMPLETED** |
| Дата (0A) | 2026-07-26 |
| Артефакт downstream (implementation GO) | `TracingConfigurationPropertySourceClassifier` |
| Runtime verification artifact (0B) | characterization tests в `platform-tracing-e2e-tests` (§9) |

### Статус GO (authoritative)

| Scope | Status |
|-------|--------|
| Plan revision | **PASS** |
| Step 0A — source/API design analysis | **PASS**, **COMPLETED** |
| Step 0B — executable provenance characterization | **PASS**, **COMPLETED** |
| **Step 0 overall** | **PASS**, **COMPLETED** |
| General implementation | **NOT GO** |
| Legacy mutation deletion | **BLOCKED** |

**Основание PASS:** T1–T10 выполнены на изолированном целевом стеке; отдельный дочерний
процесс настоящего Config Server успешно прочитал временный Git repository и отдал новую
revision после refresh. Результаты и runtime-уточнения приведены в §10.

**Path to implementation GO:** Step 0B PASS → state review ∥ diagnostics inventory ∥ `scrubbing.rules-config` classification → explicit implementation GO.

---

## 1. Цель и scope

### Цель Step 0

Доказать, что для режима `platform.tracing.configuration.authority=CONFIG_SERVER` можно **надёжно** определить provenance ключей `platform.tracing.policy.*` в Spring `Environment` без опоры **только** на строковое имя `PropertySource`, и зафиксировать извлекаемый **`sourceRevision`**.

### Step 0A — source/API design analysis (COMPLETED)

| В scope | Вне scope |
|---------|-----------|
| Анализ public API Spring Boot 3.5.x + Spring Cloud Config Client 4.3.x (reference) | Реализация bridge, guard, applier |
| Проектирование multi-signal classifier | Legacy deletion |
| PASS/FAIL критерии и рекомендации | Executable tests (→ Step 0B) |
| Проектирование T1–T10 | Bump BOM / изменение `gradle.properties` |

**Метод:** read-only анализ public API и reference implementation (GitHub tags v4.3.0).

### Step 0B — executable provenance characterization (COMPLETED)

| В scope | DoD |
|---------|-----|
| Characterization tests T1–T10 в существующем `platform-tracing-e2e-tests` | PASS/FAIL/PARTIAL по каждому сценарию |
| WireMock Config Server для deterministic provenance/refresh | Actual PropertySource tree |
| ≥1 process-level test с настоящим Config Server или official test fixture | Per-key origin/type/name |
| Pin exact test stack (§8) | Effective + remote desired resolution |
| | Revision extraction, refresh event ordering |
| | Unresolved implementation-detail dependencies |

**Без нового Gradle-модуля.**

---

## 2. Процесс выполнения

### Step 0A (COMPLETED)

| Шаг | Действие | Результат |
|-----|----------|-----------|
| 1 | Сверка версий в `gradle.properties` и `platform-tracing-bom/build.gradle` | Gap: нет config-client, Cloud Context 4.1.5 |
| 2 | Анализ Config Client 4.3.0 (`ConfigServerConfigDataLoader`) | Config Data path PropertySource tree |
| 3 | Анализ Spring Cloud Context 4.3.0 (`ContextRefresher`, `EnvironmentChangeEvent`) | Refresh/deletion semantics |
| 4 | Анализ Boot `OriginTrackedMapPropertySource` / `OriginLookup` | Per-key origin (public API only) |
| 5 | Проектирование multi-signal classifier | §4–5 |
| 6 | Проектирование per-key provenance model | §4.5 |
| 7 | Проектирование T1–T10 | §7 |
| 8 | Pin exact test stack | §8 |

### Step 0B (COMPLETED)

| Шаг | Действие | Статус |
|-----|----------|--------|
| 1 | Изолировать pinned test stack (§8) без production BOM bump | completed |
| 2 | JDK HTTP Config Server stub + characterization harness | completed |
| 3 | Execute T1–T10, record evidence | completed |
| 4 | Real Config Server child process + temporary Git | completed |
| 5 | Append §10 runtime verification results | completed |

---

## 3. Поддерживаемый CONFIG_SERVER path (Config Data only)

### 3.1. Единственный supported import

Production и tests для `authority=CONFIG_SERVER`:

```properties
spring.config.import=configserver:
spring.cloud.config.fail-fast=true
```

Загрузчик: `ConfigServerConfigDataLoader` (Spring Cloud Config Client, Config Data path).

`optional:configserver:` — только bootstrap/local; **не** authoritative CONFIG_SERVER mode.

### 3.2. Legacy bootstrap — NOT supported

Legacy `ConfigServicePropertySourceLocator` создаёт composite PropertySource **`configService`** (без префикса `configserver:`). Этот path **не** реализуется в classifier без отдельного подтверждённого требования.

**Fail-fast rule:** при `authority=CONFIG_SERVER` обнаружение PropertySource с именем **`configService`** (legacy bootstrap) → startup error с migration message:

```text
Legacy Spring Cloud Config bootstrap (configService PropertySource) is not supported
in platform.tracing.configuration.authority=CONFIG_SERVER.
Migrate to: spring.config.import=configserver:
```

### 3.3. Структура PropertySource после Config Data import

При успешном ответе Config Server клиент добавляет:

1. **`configClient`** (`MapPropertySource`) — маркер **успешного HTTP-ответа** Config Server (не per-key provenance):
   - может содержать `config.client.version` ← version label / commit id от Config Server
   - может содержать `config.client.state` ← state header protocol
   - **version может отсутствовать** даже при успешном fetch

2. **Один или несколько `configserver:{remoteSourceName}`** — `OriginTrackedMapPropertySource` (immutable):
   - префикс: `"configserver:" + source.getName()`
   - значения обёрнуты в `OriginTrackedValue` с origin (implementation type, см. §4.3)

3. Опционально **`configserver:overrides`** — server-side overrides.

---

## 4. Public API для provenance

### 4.1. Разделение: successful fetch vs per-key provenance

| Концепт | Сигнал | Что доказывает |
|---------|--------|----------------|
| **Successful Config Server fetch** | PropertySource **`configClient`** присутствует | HTTP-ответ Config Server получен |
| **Per-key remote provenance** | Winning / remote desired source per key (§4.5) | Конкретный ключ пришёл из remote config |

**`configClient` ≠ remote provenance конкретного ключа.** Guard и bridge обязаны различать эти уровни.

### 4.2. Сигналы доверия (ordered)

| # | Сигнал | Public API | Роль |
|---|--------|------------|------|
| S1 | PropertySource **`configClient`** present | `environment.getPropertySources().get("configClient")` | Successful fetch marker |
| S2 | PropertySource name prefix **`configserver:`** | `propertySource.getName().startsWith("configserver:")` | Primary per-key remote source discriminator |
| S3 | PropertySource type **`OriginTrackedMapPropertySource`** | `instanceof OriginTrackedMapPropertySource` | Remote entries container |
| S4 | Per-key origin via **`OriginLookup`** | `((OriginLookup) ps).getOrigin(key)` | **Corroborating** signal (§4.3) |
| S5 | Только `propertySource.getName()` string match | — | **FAIL** как единственный критерий |

**Удалено из supported signals:** composite **`configService`** (legacy bootstrap) — fail-fast, не classifier category.

### 4.3. `ConfigServiceOrigin` — NOT public API

`ConfigServiceOrigin` — **package-private implementation type** Spring Cloud Config Client. Classifier **не должен**:

- импортировать `ConfigServiceOrigin`;
- применять reflection к нему;
- полагаться только на `origin.getClass().getName()` / class name match;
- считать `origin.toString()` **самостоятельным** trust signal.

**Допустимо:** public `org.springframework.boot.origin.OriginLookup.getOrigin(String key)` → `Origin` (interface).

Origin — **подтверждающий** (corroborating) сигнал в сочетании с S2+S3, **не** стабильный типизированный discriminator. Классификация per-key опирается на PropertySource **name + type + precedence**, origin усиливает confidence при совпадении.

### 4.4. `sourceRevision` — extraction path

**Запрещено:**

```java
environment.getProperty("config.client.version")  // resolves through full PropertySource chain
```

**Обязательно:** прямое чтение из marker source после проверки типа и присутствия:

```java
PropertySource<?> marker = environment.getPropertySources().get("configClient");
if (marker instanceof MapPropertySource mapSource) {
    Object version = mapSource.getProperty("config.client.version");  // may be null
    Object state = mapSource.getProperty("config.client.state");      // optional correlation
}
```

| Поле | Источник | Обязательность |
|------|----------|----------------|
| `sourceRevision` | `configClient` → `config.client.version` | **Optional** — marker source может существовать без version |
| `sourceState` (correlation) | `configClient` → `config.client.state` | Optional |
| Synthetic из PropertySource name | — | **Запрещено** |

Локальная попытка подменить `config.client.version` через более приоритетный PropertySource **не должна** влиять на revision, прочитанную из marker source (T8).

### 4.5. Per-key provenance model (mandatory)

Для каждого allowlisted key classifier/bridge определяет **отдельно**:

| Поле | Описание |
|------|----------|
| **effectiveValue** | Значение по Spring resolution order (`environment.getProperty(key)`) |
| **effectiveWinningSource** | Первый PropertySource в chain, содержащий key |
| **effectiveCategory** | Классификация winning source |
| **remoteDesiredValue** | Значение из highest-precedence `configserver:*` source, содержащего key (null if absent) |
| **remoteDesiredSource** | Соответствующий `configserver:*` PropertySource |
| **localOverride** | `effectiveWinningSource != remoteDesiredSource` при наличии remote desired |
| **conflict** | `localOverride && effectiveValue != remoteDesiredValue` |

```text
for each allowlisted key:
  effectiveWinningSource = findFirstPropertySourceContaining(key)
  effectiveValue         = resolveFrom(effectiveWinningSource, key)
  effectiveCategory      = classify(effectiveWinningSource, key)

  remoteDesiredSource    = findFirstConfigServerSourceContaining(key)  // configserver:* only
  remoteDesiredValue     = remoteDesiredSource ? resolveFrom(remoteDesiredSource, key) : null

  localOverride = remoteDesiredSource != null
                  && effectiveWinningSource != remoteDesiredSource
  conflict      = localOverride
                  && !Objects.equals(effectiveValue, remoteDesiredValue)
```

**CONFIG_SERVER mode fail-closed (policy.*):** `localOverride` или `conflict` для category A keys → startup/readiness error (T6).

**Structural keys (category B):** remote desired vs effective pre-JVM comparison → `restartRequired` без runtime apply (T4, T5).

### 4.6. PropertySource precedence

Spring `Environment` — упорядоченный список. Config Data loader добавляет `configserver:*` sources **рано** (высокий приоритет).

Bridge строит full snapshot через **iterating allowlisted keys** с per-key model §4.5 — не предполагать единый source для всех `policy.*`.

---

## 5. Проектируемый `PropertySourceCategory` enum

```text
CONFIG_SERVER_REMOTE   — configserver:* PropertySource (Config Data path only)
CONFIG_CLIENT_MARKER   — configClient (fetch marker; version/state meta only)
HELM_ENV               — systemEnvironment / systemProperties
LOCAL_DEV              — application-dev.yml / dev profile (DEV_ACTUATOR only)
LOCAL_YAML             — application.yml / classpath local
UNKNOWN_AMBIGUOUS      — fail-closed in CONFIG_SERVER authoritative mode
```

**Не включать:** `configService` / legacy bootstrap — detection → fail-fast (§3.2).

### Fail-closed rules (CONFIG_SERVER)

| Condition | Action |
|-----------|--------|
| `configClient` marker **отсутствует** | startup/readiness error |
| Legacy `configService` PropertySource detected | fail-fast + migration message |
| `policy.*` effectiveCategory ≠ `CONFIG_SERVER_REMOTE` | fail-closed (T6) |
| effectiveCategory = `UNKNOWN_AMBIGUOUS` | fail-closed |
| `policy.*` localOverride / conflict | fail-closed |

---

## 6. Refresh, key deletion, EnvironmentChangeEvent

### 6.1. Механизм refresh

`POST /actuator/refresh` → `ContextRefresher.refreshEnvironment()` → `ConfigDataContextRefresher.updateEnvironment()`:

1. Копирует environment (commandLine + defaultProperties preserved).
2. Re-run EnvironmentPostProcessors (Config Data reload).
3. Replace/add non-standard PropertySources.
4. Diff `before` vs `after` map всех non-standard keys.
5. Публикует `EnvironmentChangeEvent`.

### 6.2. Deletion semantics (исправлено)

`ContextRefresher.changes()` помещает удалённые keys в diff map со значением **`null`**. `EnvironmentChangeEvent.getKeys()` возвращает **keySet** diff map — удалённые keys **включены** в event.

**Архитектурное правило (не меняется):**

| Rule | Rationale |
|------|-----------|
| Bridge **не** использует `changedKeys` как patch | diff неполон для nested structures |
| Bridge rebuild **full snapshot** из текущего Environment | authoritative state |
| Missing allowlisted keys → **explicit documented defaults** | deletion ≠ silent unchanged |
| Listener видит **уже обновлённый** Environment (T10) | read after event, not before-state |

### 6.3. Key removal after refresh (T9)

1. Config Server удаляет key.
2. Refresh reloads PropertySources; key absent from `configserver:*` sources.
3. `EnvironmentChangeEvent.getKeys()` **содержит** removed key (value was null in diff).
4. Bridge rebuilds full snapshot → missing key → explicit default.

---

## 7. Тестовые сценарии (T1–T10)

Executable verification **обязательна** до explicit implementation GO. Step 0A: **defined**; Step 0B: **executed**.

| ID | Сценарий | Ожидание | Step 0B |
|----|----------|----------|---------|
| **T1** | Pure remote: все `policy.*` только в `configserver:*` | `CONFIG_SERVER_REMOTE`; `configClient` present; per-key remote desired = effective | **PASS** |
| **T2** | Remote + local/command-line override | command line wins and fails closed; обычный local YAML фактически ниже remote import | **PASS** |
| **T3** | Refresh after policy key removal | full snapshot выбирает explicit default; revision меняется | **PASS** |
| **T4** | Structural key в env/`-D` и Config Server **одинаковое** значение | no drift; `restartRequired=false` | **PASS** |
| **T5** | Structural key в env/`-D` и Config Server **разное** значение | remote desired found; `restartRequired=true` | **PASS** |
| **T6** | Remote policy key перекрыт system property | CONFIG_SERVER fail-closed | **PASS** |
| **T7** | `configClient` есть, `config.client.version` **отсутствует** | startup OK; `sourceRevision` absent in diagnostics | **PASS** |
| **T8** | Локальная подмена `config.client.version` в high-precedence source | revision из marker source unchanged | **PASS** |
| **T9** | Key removal после refresh | removed key in event keys; full snapshot default | **PASS** |
| **T10** | Refresh event listener | reads **post-refresh** Environment | **PASS** |

---

## 8. Exact test stack (pinned before Step 0B execution)

Не тестировать абстрактный `2025.0.x`. Зафиксировать **до** executable verification:

| Component | Pinned version | Notes |
|-----------|----------------|-------|
| Spring Boot | **3.5.6** | isolated `enforcedPlatform`, production `gradle.properties` unchanged |
| Spring Cloud BOM | **2025.0.0** | isolated `enforcedPlatform`, production BOM unchanged |
| Config Client (resolved) | **4.3.0** (verify from BOM) | record exact resolved version in §10 |
| Cloud Context (resolved) | **4.3.0** (verify from BOM) | record exact resolved version in §10 |
| Java | **21** | repository toolchain |
| Config Server backend | **Git** (default) | `spring-cloud-config-server` test dependency, exact artifact version = resolved Config Client line |
| HTTP stub | JDK `HttpServer` | deterministic Config Server `Environment` responses |

Resolved versions captured from
`./gradlew :platform-tracing-e2e-tests:dependencies --configuration configProvenanceRuntimeClasspath`.

---

## 9. Runtime verification artifact (Step 0B)

### Размещение

**Модуль:** [`platform-tracing-e2e-tests`](../../platform-tracing-e2e-tests) — **без нового Gradle-модуля**.

**Предлагаемая структура:**

```text
platform-tracing-e2e-tests/src/configProvenance/java/.../configserver/
  ConfigServerProvenanceCharacterizationTest.java   // JDK HTTP stub, T1–T10
  ConfigServerGitProcessCharacterizationTest.java   // child Config Server + temporary Git
  support/
    ConfigServerResponseStub.java
    ProvenanceInspector.java
    EvidenceRecorder.java
```

### Стратегия

1. JDK HTTP stub отдаёт `/{application}/{profile}` Environment JSON — deterministic
   provenance/refresh для T1–T10.
2. Process-level test запускает настоящий `spring-cloud-config-server` в child JVM с
   temporary local Git — validate real PropertySource tree and revision refresh.

### Dependencies (Step 0B scope)

```gradle
// platform-tracing-e2e-tests/build.gradle — isolated configProvenance scope only
configProvenanceImplementation 'org.springframework.cloud:spring-cloud-starter-config'
configProvenanceImplementation 'org.springframework.cloud:spring-cloud-config-server'
// BOM: org.springframework.cloud:spring-cloud-dependencies:2025.0.0
```

---

## 10. Runtime verification results (Step 0B)

> **Статус:** **PASS, COMPLETED**. Выполнено 2026-07-26.

### Методика и фактический стек

В `platform-tracing-e2e-tests` добавлен отдельный source set `configProvenance` и задача
`configProvenanceTest`. Конфигурация использует два `enforcedPlatform` только в test scope;
production BOM, публикационные metadata и consumer classpath не менялись.

| Component | Resolved |
|-----------|----------|
| Java | 21.0.6 |
| Spring Boot | **3.5.6** |
| Spring Cloud BOM | **2025.0.0** |
| Spring Cloud Config Client | **4.3.0** |
| Spring Cloud Context | **4.3.0** |
| Spring Cloud Config Server | **4.3.0** |
| JGit | 6.10.1.202505221210-r |

Layer A использует локальный JDK HTTP server, отдающий настоящий Config Server
`Environment` JSON. Ответ проходит через реальный Config Client и канонический Config Data
path: `spring.config.import=configserver:` + `spring.cloud.config.uri`.

Layer B запускает `ConfigServerProcessApplication` в дочерней Java 21 JVM на случайном
порту. Backend — временный локальный Git repository с веткой `main`; после второго commit
client выполняет стандартный `ContextRefresher.refresh()`. Docker не требуется.

### Per-scenario verdict

| ID | Verdict | Notes |
|----|---------|-------|
| T1 | **PASS** | `configClient` present; effective=remote desired=`0.25`; source=`configserver:stub:application.yml` / `OriginTrackedMapPropertySource`; version/state direct=`remote-v1/state-v1` |
| T2 | **PASS** | command line выигрывает и даёт conflict; remote desired остаётся доступным. Обычный additional `application.yml` расположен **ниже** remote Config Data и не выигрывает |
| T3 | **PASS** | removed ratio входит в event keys; обе per-key views становятся absent; full snapshot выбирает explicit default `1.0`; marker уже `remote-v3-after` |
| T4 | **PASS** | system=`2048`, remote desired=`2048`; shadowing=true, conflict=false, `restartRequired=false` |
| T5 | **PASS** | system=`2048`, remote desired=`4096`; shadowing/conflict=true, `restartRequired=true` |
| T6 | **PASS** | effective source=`systemProperties`, remote desired=`0.20`; authoritative decision=`FAIL_CLOSED` |
| T7 | **PASS** | marker и remote classification присутствуют, direct version=`null`, state=`state-v7`; synthetic revision не создаётся |
| T8 | **PASS** | effective `config.client.version=fake-local-version`, direct marker=`actual-remote-version` |
| T9 | **PASS** | removal независимо подтверждён event keys, Environment, remote search и full-snapshot default |
| T10 | **PASS** | listener внутри `EnvironmentChangeEvent` видит value=`0.22`, новый remote source и marker=`remote-v10-after` |

Дополнительный Layer B: **PASS**. Initial и refreshed marker version точно совпали с
соответствующими Git commit SHA; refreshed value=`0.62`; event keys содержали policy key и
`config.client.version`; origin был доступен через public `OriginLookup`.

### Evidence checklist (mandatory output)

- [x] Actual PropertySource tree (ordered names + types)
- [x] Per-key: origin (via `OriginLookup`), type, source name
- [x] Effective vs remote desired resolution table
- [x] Revision extraction from `configClient` marker (direct read)
- [x] Refresh event key set (including deletions)
- [x] Refresh listener Environment ordering (pre vs post)
- [x] Resolved dependency versions (Boot, Cloud BOM, Client, Context, Config Server)
- [x] Unresolved implementation-detail dependencies

### Sanitized PropertySource trees

Pure remote:

```text
0 configurationProperties
1 commandLineArgs
2 systemProperties
3 systemEnvironment
4 random
5 cachedrandom
6 configserver:stub:application.yml | OriginTrackedMapPropertySource | policy keys present
7 configClient | MapPropertySource
8 springCloudClientHostInfo
9 applicationInfo
```

Command-line + local YAML + remote:

```text
1 commandLineArgs | ratio present
6 configserver:stub:application.yml | ratio + route-ratio present
7 configClient
8 Config resource '.../application.yml' | ratio + route-ratio present
```

Следствие: command line перекрывает remote; импортированный remote источник перекрывает
обычный local `application.yml`. Step 0A assumption о higher-precedence local YAML
скорректирован по runtime evidence.

После удаления:

```text
6 configserver:stub:application.yml | OriginTrackedMapPropertySource | removed key absent
7 configClient | version/state refreshed
```

Structural shadowing:

```text
2 systemProperties | otel.bsp.max.queue.size present
6 configserver:stub:application.yml | otel.bsp.max.queue.size present
7 configClient
```

### Runtime уточнения и implementation-detail dependencies

1. `configurationProperties` — агрегирующий wrapper и не может считаться concrete winning
   source; поиск effective source обязан пропускать его.
2. Layer A подтвердил, что `OriginLookup` может вернуть `null` даже при корректных
   `configClient` + `configserver:*` + `OriginTrackedMapPropertySource`. На реальном Git
   Config Server origin присутствовал. Поэтому origin остаётся corroborating, но не required
   trust signal.
3. Прямое чтение marker обязательно: effective Environment допускает spoofing version,
   concrete `configClient` — нет.
4. Refresh заменяет remote contents до публикации события; listener безопасно строит full
   snapshot из уже обновлённого Environment.
5. При изменении marker event keys включают `config.client.version/state`; consumer обязан
   фильтровать allowlist и не применять event keys как patch.
6. Совмещение Config Client и Config Server в одном test source set требует явно включить
   `spring.cloud.config.enabled=true`; это ограничение test fixture, а не production client.

### Step 0B overall verdict

**PASS** — T1–T10 и process-level Git Config Server verification зелёные.

---

## 11. Gap analysis: текущий репозиторий

| Компонент | Текущее состояние | Action |
|-----------|-------------------|--------|
| Spring Boot | 3.5.5 | → 3.5.6 (Step 0B test stack) |
| Spring Cloud | `spring-cloud-context:4.1.5` only | → BOM 2025.0.0 + config-client (Step 0B) |
| Config import tests | None | Step 0B |
| `EnvironmentChangeEvent` listeners | None | implementation GO (after 0B PASS) |
| `@RefreshScope` autoconfig | `TracingRefreshScopeAutoConfiguration` (legacy) | legacy deletion GO |

---

## 12. Рекомендации для guard и bridge (post-0B)

### Guard (`TracingConfigurationGuard`)

| Key type | CONFIG_SERVER | HELM | DEV_ACTUATOR |
|----------|---------------|------|--------------|
| `platform.tracing.policy.*` | per-key remote desired; no localOverride; `configClient` required | `HELM_ENV` only | `LOCAL_DEV` allowed |
| `platform.tracing.javaagent.*` structural | remote desired vs effective → drift | `HELM_ENV` | pre-JVM |
| Legacy bootstrap `configService` | fail-fast | n/a | n/a |
| Legacy key names | fail all modes | | |

Classifier: multi-signal §4.2; **no** `ConfigServiceOrigin` import; **no** string-only.

### Bridge (`TracingConfigServerBridge`)

- Read revision from `configClient` marker source directly (§4.4).
- Per-key model §4.5 on every snapshot build.
- `spring.config.import=configserver:` + fail-fast; no custom HTTP client.
- On `EnvironmentChangeEvent`: single-flight → full snapshot → applier.
- Ignore `changedKeys` as patch; use post-event Environment (T10).

---

## 13. Verdict summary

### Step 0A (source/API design analysis)

| Criterion | Result |
|-----------|--------|
| Multi-signal classifier (no string-only) | **PASS** |
| Per-key provenance model | **PASS** (designed) |
| `ConfigServiceOrigin` excluded from public contract | **PASS** |
| Revision direct read from marker source | **PASS** (designed) |
| Legacy bootstrap excluded + fail-fast | **PASS** |
| T1–T10 defined | **PASS** |
| Deletion/event semantics corrected | **PASS** |

**Step 0A: PASS, COMPLETED.**

### Step 0B (executable provenance characterization)

| Criterion | Result |
|-----------|--------|
| T1–T10 executed | **PASS** |
| Evidence checklist §10 complete | **PASS** |
| Real Config Server + temporary Git | **PASS** |
| Exact test stack pinned and recorded | **PASS** |

**Step 0B: PASS, COMPLETED.**

### Step 0 overall: **PASS, COMPLETED**

Step 0B больше не блокирует path to implementation GO. Однако explicit
**implementation GO всё ещё не выдан**: остаются state review, diagnostics inventory и
`scrubbing.rules-config` classification. Legacy mutation deletion остаётся **BLOCKED**.

Параллельно допустимо (не блокирует 0B): state review (`VersionedStateHolder`), diagnostics inventory, `scrubbing.rules-config` classification.

---

## Приложение A — reference source

| Component | Version | URL |
|-----------|---------|-----|
| ConfigServerConfigDataLoader | 4.3.0 | `spring-cloud/spring-cloud-config` tag v4.3.0 |
| ContextRefresher | 4.3.0 | `spring-cloud/spring-cloud-commons` tag v4.3.0 |
| ConfigDataContextRefresher | 4.3.0 | same |
| OriginTrackedMapPropertySource | Boot 3.5.x | Spring Boot API |
| OriginLookup | Boot 3.5.x | `org.springframework.boot.origin.OriginLookup` |

**Not public API:** `org.springframework.cloud.config.client.ConfigServiceOrigin` (package-private).

## Приложение B — per-key resolution (implementation GO pseudocode)

```java
PropertySource<?> findWinningSource(MutablePropertySources sources, String key) {
    for (PropertySource<?> ps : sources) {
        if (ps.containsProperty(key)) {
            return ps;
        }
    }
    return null;
}

PropertySource<?> findRemoteDesiredSource(MutablePropertySources sources, String key) {
    for (PropertySource<?> ps : sources) {
        if (ps.getName().startsWith("configserver:") && ps.containsProperty(key)) {
            return ps;
        }
    }
    return null;
}

Optional<String> readSourceRevisionFromMarker(MutablePropertySources sources) {
    PropertySource<?> marker = sources.get("configClient");
    if (!(marker instanceof MapPropertySource map)) {
        return Optional.empty();
    }
    Object version = map.getProperty("config.client.version");
    return version != null ? Optional.of(version.toString()) : Optional.empty();
}
```
