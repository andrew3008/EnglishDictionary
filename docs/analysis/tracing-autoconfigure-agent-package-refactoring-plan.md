# План рефакторинга agent-пакета Spring Boot autoconfigure

| Поле | Значение |
| --- | --- |
| Дата | 2026-08-06 |
| Репозиторий | `E:\Platform_Traces` |
| Проверенный baseline | `c1565430174d33443cb2b71b77e21876170a9955` (`Tracing Bootstrap refactoring`) |
| Целевой пакет | `space.br1440.platform.tracing.autoconfigure.agent` |
| Владеющий модуль | `platform-tracing-spring-boot-autoconfigure` |
| Основание | `docs/analysis/tracing-autoconfigure-agent-package-inventory.md` и повторная проверка текущего кода |
| Статус | `PROPOSED / IMPLEMENTATION PLAN` |
| Production-код в рамках подготовки плана | Не изменялся |

## 1. Решение

Пакет необходимо сохранить как единый application-side boundary взаимодействия Spring
Boot autoconfigure с Controlled Platform Agent, но изменить его внутреннюю архитектуру.
Разделение на подпакеты сейчас нецелесообразно: оно разрушит package-private границу и
вынудит сделать transport/classification-типы публичными. Целевая модель — небольшой
публичный фасад и package-private реализации в одном пакете.

Рефакторинг должен:

1. отделить JMX transport от классификации и startup polling;
2. сократить случайную публичную поверхность;
3. сделать marker detection семантически точным: marker не равен готовому Agent runtime;
4. сохранить classloader-neutral контракт между application и agent planes;
5. устранить скрытые и неоднозначные контракты текущего `AgentExtensionObserver`;
6. сохранить fail-closed startup и Controlled Agent как единственного владельца SDK;
7. усилить доказательства wire-контракта и конкурентных startup-сценариев.

Это не план создания второго SDK, публичного SPI или live control-plane.

## 2. Проверенный baseline

- [x] Прочитаны все семь production-типов пакета и их прямые потребители.
- [x] Проверены Spring composition root, startup health, status assembler, webmvc/webflux
  consumers, Agent readiness producer и architecture allowlist/type ledger.
- [x] Подтверждено отсутствие production-зависимости autoconfigure на
  `platform-tracing-otel-javaagent-extension`.
- [x] Подтверждено, что SDK создаёт и обслуживает Controlled Agent, а не Spring starter.
- [x] Выполнен focused baseline:

```powershell
.\gradlew.bat :platform-tracing-spring-boot-autoconfigure:test `
  --tests "space.br1440.platform.tracing.autoconfigure.agent.*" --no-daemon
```

Результат: `BUILD SUCCESSFUL`.

### 2.1 Обязательный pre-flight перед реализацией

Во время подготовки документа прежний WIP был зафиксирован владельцем в commit
`c1565430174d33443cb2b71b77e21876170a9955`. Commit включает inventory и изменения всех
семи production-типов пакета. Повторное чтение `AgentExtensionObserver` и call-site search
подтвердили, что описанная inventory архитектура соответствует новому HEAD.

Перед первым implementation commit исполнитель обязан:

1. подтвердить, что baseline является потомком `c1565430`;
2. зафиксировать точный `HEAD`, `git status --short` и `git diff --stat`;
3. повторно выполнить focused package tests на фактическом HEAD;
4. не смешивать новый refactoring diff с несвязанными изменениями рабочей копии;
5. не изменять кодировку и окончания строк несвязанных файлов.

## 3. Нормативные инварианты

Следующие правила обязательны во всех slices.

| ID | Инвариант |
| --- | --- |
| A-01 | `platform.tracing.enabled=true` допускает запуск только при `AGENT_READY`. |
| A-02 | `platform.tracing.enabled=false` допускает платформенный NoOp только после подтверждённого `DISABLED`. |
| A-03 | Marker-класс Java Agent не является доказательством готовности platform extension. |
| A-04 | Application-owned `OpenTelemetry` bean/global runtime не может конкурировать с platform Agent SDK. |
| A-05 | Spring autoconfigure не создаёт `OpenTelemetrySdk`, exporters, processors или propagators. |
| A-06 | READY требует protocol `1`, profile `platform-agent-secure-v1`, проверяемую версию обоих platform artifacts и полный capability contract. |
| A-07 | Между Agent и application classloaders передаются только JMX/JDK-safe значения. Общий Java DTO запрещён. |
| A-08 | `FailureMessage` вообще не запрашивается application plane и не пересекает JMX boundary. Допустим только bounded `failureCode`. |
| A-09 | Состояния `AgentRuntimeState` являются operational wire values; их rename требует отдельного schema decision. |
| A-10 | При запрещённом tracing отсутствие startup Agent проверяется один раз после начала Spring bootstrap. `-javaagent` выполняет `premain` до `main`; dynamic attach после startup не поддерживается и не маскируется произвольным двухсекундным окном. |
| A-11 | Startup diagnostics остаётся immutable startup snapshot. Status/health не выполняют скрытый JMX poll на каждом чтении. |
| A-12 | Application plane не зависит от implementation-типов `platform-tracing-otel-javaagent-extension`. |
| A-13 | Ни один failure path не переходит к permissive/no-op runtime при `enabled=true`. |
| A-14 | Один startup verification invocation выдаёт одно окончательное решение без retry/rebase после возврата результата. |
| A-15 | Физический Agent readiness MBean сохраняет 13-attribute ABI. Application probe запрашивает ровно 12 атрибутов и намеренно исключает только `FailureMessage`. |
| A-16 | Marker visibility, application bean presence и `GlobalOpenTelemetry.isSet()` фиксируются один раз на startup. Повторно читается только readiness endpoint при валидном `INITIALIZING`. |

## 4. Проблемы текущей реализации

### 4.1 Смешение ответственностей

`AgentExtensionObserver` одновременно:

- опрашивает MBeanServer;
- декодирует transport values;
- знает protocol/profile/version policy;
- классифицирует runtime state;
- выполняет polling/deadline logic;
- формирует операторские failure codes.

Из-за этого transport defect, state-machine defect и startup timing defect невозможно
проверять независимо.

### 4.2 Случайная public API surface

Все семь типов публичны и находятся в allowlist, хотя
`AgentDeploymentExpectation`, `AgentExtensionObserver` и
`AgentRuntimeContractValidator` нужны только внутренней композиции. Это не application API
и не extension SPI.

### 4.3 Неточный язык модели

`OtelAgentDetector` определяет только видимость marker-класса. Название допускает неверное
прочтение «Agent обнаружен и готов». Поле `agentDetected` в diagnostics имеет ту же
неоднозначность.

### 4.4 Неполный wire-read contract

Observer выполняет несколько scalar JMX reads, сам содержит копию required capabilities и
не использует опубликованный Agent-side атрибут `RequiredCapabilities`. Кроме того, он
запрашивает `FailureMessage` и только после переноса в application classloader отбрасывает
значение. Это расходится с ADR, по которому полный Agent failure message вообще не должен
пересекать application boundary.

Переход на `getAttributes` уменьшит количество round trips, но не должен называться
атомарным: JMX не гарантирует единую версию нескольких атрибутов. JDK также разрешает
`getAttributes` вернуть неполный `AttributeList`, молча исключив непрочитанные атрибуты.
Следовательно, потребитель обязан проверить точный набор имён и размер результата.

### 4.5 Скрытые поведенческие неоднозначности

- `observe(expectation, ...)` принимает `expectation`, но не использует её после null-check;
- при disappearance MBean между `isRegistered` и чтением поведение не закреплено отдельным
  тестом;
- `development` сейчас действует как односторонний wildcard и может принять пару
  `development`/release;
- обычный JAR `platform-tracing-spring-boot-autoconfigure` не содержит
  `Implementation-Version`, поэтому application-side version в packaged E1 фактически всегда
  равна `development`; Agent extension JAR при этом публикует реальную версию. До исправления
  manifest проверка совместимости версий в production не является аттестацией;
- production status фактически показывает sticky startup snapshot, хотя тестовый supplier
  создаёт впечатление live observation;
- deadline/polling не имеют отдельного доказательства около переполнения `nanoTime`;
- FORBIDDEN path всегда задерживает startup на две секунды, хотя startup Agent уже обязан
  завершить `premain` до `main`;
- production-код читает `platform.tracing.test.startup-readiness-timeout-ms`, поэтому
  test-only переключатель может ослабить production validation window;
- тестовый файл `SdkModeResolverTest.java` содержит класс с другой, уже новой семантикой.

### 4.6 Дополнительные проверенные ограничения

| Источник | Подтверждённое ограничение | Следствие для плана |
| --- | --- | --- |
| [JDK 21 `MBeanServer.getAttributes`](https://docs.oracle.com/en/java/javase/21/docs/api/java.management/javax/management/MBeanServer.html#getAttributes(javax.management.ObjectName,java.lang.String%5B%5D)) | Непрочитанные атрибуты исключаются из результата; caller обязан проверить полноту | Probe строит map по именам, запрещает пропуски/дубликаты/неожиданные имена и не использует positional decoding |
| [OpenTelemetry Java Agent extensions](https://opentelemetry.io/docs/zero-code/java/agent/extensions/) | Extension загружается Agent-ом и должен управлять dependency isolation; SPI предоставляет Agent runtime | Spring main source не получает compile/runtime dependency на extension implementation и не делит с ним Java DTO |
| [Java Instrumentation, запуск Agent](https://docs.oracle.com/en/java/javase/21/docs/api/java.instrument/java/lang/instrument/package-summary.html#starting-an-agent-from-the-command-line-interface) | `premain` startup-agent вызывается до application `main`; attach в работающую JVM использует отдельный `agentmain` mechanism | FORBIDDEN не ждёт условные две секунды; dynamic attach запрещается deployment policy и не считается поддержанным режимом |
| [Spring `BeanFactoryPostProcessor`](https://docs.spring.io/spring-framework/reference/core/beans/factory-extension.html#beans-factory-extension-factory-postprocessors) | BFPP работает с metadata до обычной инициализации beans; static factory method предотвращает преждевременное создание configuration class | Существующий static bean-definition guard остаётся в `TracingRuntimeAutoConfiguration`; verifier не заменяет и не откладывает его |
| [JDK 21 `Package.getImplementationVersion`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Package.html#getImplementationVersion()) | Значение берётся из `Implementation-Version` manifest секции JAR; при отсутствии атрибута возвращается `null` | Autoconfigure JAR обязан публиковать manifest version; packaged E1 проверяет реальный JAR, а не classes directory |
| `ADR-sdk-mode-detection.md` | Полный Agent failure message через application boundary не передаётся | `FailureMessage` исключается из runtime read request, а не только из public DTO |
| Agent-side `PlatformExtensionReadiness` | Capability update и lifecycle transition синхронизированы, но отдельные JMX getters читаются последовательно | Batch read не атомарен; согласованность проверяется, а допустимое transition-состояние `INITIALIZING` повторно опрашивается до deadline |

## 5. Целевая архитектура

### 5.1 Граница пакета

Пакет остаётся плоским. Дополнительный `package-info.java` не создаётся: нормативные
границы фиксируются архитектурными тестами, ADR и этим планом, без ещё одного документа в
production source tree.

Целевое дерево:

```text
space.br1440.platform.tracing.autoconfigure.agent
├── AgentStartupVerifier.java                 public facade
├── AgentRuntimeState.java                    public operational enum
├── OtelJavaAgentMarkerDetector.java          public cross-module marker utility
├── TracingDeploymentDiagnostics.java         public immutable startup result
├── AgentReadinessProbe.java                  package-private JMX transport
├── AgentReadinessSnapshot.java               package-private decoded descriptor
└── AgentReadinessClassifier.java             package-private pure state machine
```

### 5.2 Ответственности

#### `AgentStartupVerifier`

Единственный публичный entry point для composition root. Он:

- получает `tracingEnabled` и наличие application `OpenTelemetry` bean;
- один раз получает application artifact version из package manifest; отсутствие/blank version
  в packaged runtime отклоняется до сравнения с Agent extension;
- самостоятельно читает marker visibility и `GlobalOpenTelemetry.isSet()` через
  production suppliers;
- фиксирует JVM/Spring presence-сигналы один раз; dynamic attach не превращает verifier в
  частичный live monitor;
- управляет bounded polling только для `REQUIRED + INITIALIZING`;
- вызывает probe и classifier;
- проверяет соответствие итогового state флагу `tracingEnabled`;
- возвращает `TracingDeploymentDiagnostics` либо бросает детерминированный
  `IllegalStateException`, сохраняя текущий внешний failure contract и не вводя новый
  exception hierarchy.

Package-private constructor принимает clock/sleeper/signal suppliers для unit tests.
Публичного Spring override bean и нового SPI не вводится.

#### `AgentReadinessProbe`

Единственный владелец JMX transport. Он:

- проверяет registration readiness endpoint;
- выполняет один `MBeanServer.getAttributes(...)` на полный набор из 12 разрешённых
  application-side атрибутов;
- преобразует `AttributeList` в map по имени и проверяет точный размер, отсутствие
  пропусков, дубликатов и неожиданных имён;
- проверяет точные типы и bounds до создания sets/domain snapshot;
- декодирует `String[]` в immutable deterministic sets;
- никогда не запрашивает `FailureMessage`;
- различает endpoint absence, access/invocation failure и malformed descriptor;
- не принимает решений о `AGENT_READY`, `DISABLED` и deployment expectation.

Transport outcome моделируется закрытым nested result внутри `AgentReadinessProbe`, без
`null`, публичного transport DTO и новой exception hierarchy. Наружу различимы только
`ABSENT`, validated `PRESENT` и bounded `FAILED`.

Нормативный список атрибутов:

```text
ExtensionVersion
ProtocolVersion
Profile
LifecycleState
FailureCode
Capabilities
RequiredCapabilities
SanitizerInstalled
SamplerInstalled
RequiredSpanProcessorsInstalled
PropagationHooksInstalled
ExportPathProtected
```

Физический `PlatformExtensionReadinessMBean` продолжает публиковать 13 атрибутов. Его ABI
не меняется этим рефакторингом. Разность множеств обязана быть точной:

```text
agent MBean attributes - application requested attributes = { FailureMessage }
```

`getAttributes` считается batch transport operation, но не atomic snapshot. Positional
decoding и fallback scalar reads запрещены: они создают второй, потенциально другой
snapshot. Классификатор обязан проверять внутреннюю согласованность полученного набора.

#### `AgentReadinessSnapshot`

Package-private immutable record для уже проверенных transport values:

- defensive copy всех массивов/sets;
- deterministic ordering;
- отсутствие nullable полей;
- отсутствие `FailureMessage`;
- никакой Spring/JMX логики.

#### `AgentReadinessClassifier`

Чистая функция без JMX, sleep, global state и Spring. Она владеет:

- dual-runtime predicate;
- marker/endpoint state matrix;
- lifecycle transition interpretation;
- protocol/profile/version compatibility;
- required capability и boolean-attestation consistency;
- transient transition state при непротиворечивом `INITIALIZING`;
- детерминированными `AgentRuntimeState` и `failureCode`.

#### `TracingDeploymentDiagnostics`

Публичный immutable startup result. Он больше не раскрывает отдельный публичный raw
descriptor. Нужные status/health поля входят непосредственно в diagnostics и проверяются
compact constructor/invariant validation:

- `tracingEnabled`;
- `agentMarkerVisible`;
- `runtimeState`;
- endpoint presence;
- безопасные protocol/profile/version/lifecycle/failureCode значения;
- immutable capability set.

Это startup snapshot, а не live monitor.

### 5.3 Public surface после рефакторинга

Публичными остаются ровно четыре типа:

1. `AgentStartupVerifier` — composition boundary;
2. `AgentRuntimeState` — operational result vocabulary;
3. `OtelJavaAgentMarkerDetector` — cross-module marker check для webmvc/webflux и
   observation diagnostics;
4. `TracingDeploymentDiagnostics` — Spring bean/status/health input.

Public constructors/methods этих типов должны быть добавлены в exact public-surface test.
Ни один transport/classifier тип не добавляется в allowlist.

### 5.4 Карта преобразования типов

| Текущий тип | Действие | Целевой владелец |
| --- | --- | --- |
| `OtelAgentDetector` | rename без deprecated bridge | `OtelJavaAgentMarkerDetector` |
| `AgentDeploymentExpectation` | удалить top-level; internal expectation хранить внутри verifier | `AgentStartupVerifier` |
| `AgentRuntimeState` | сохранить public и wire names | без изменения ownership |
| `AgentRuntimeContractValidator` | удалить; fail-closed validation перенести в facade | `AgentStartupVerifier` |
| `AgentExtensionDescriptor` | удалить public DTO | internal `AgentReadinessSnapshot` + flattened diagnostics |
| `AgentExtensionObserver` | удалить после миграции | verifier + probe + classifier |
| `TracingDeploymentDiagnostics` | расширить и переименовать `agentDetected` в `agentMarkerVisible` | public startup result |

Решение ещё не выпущено в production, поэтому deprecated aliases и compatibility shims не
создаются. Все потребители мигрируют атомарно в рамках последовательных зелёных commits.

## 6. Нормативная state machine

Приоритет решения:

1. application `OpenTelemetry` bean или конфликтующий external global без platform Agent
   attestation → `DUAL_SDK_DETECTED`;
2. endpoint отсутствует, marker видим → `EXTENSION_MISSING`;
3. endpoint отсутствует, marker не видим → `AGENT_MISSING`;
4. malformed/inaccessible endpoint → `EXTENSION_INCOMPATIBLE` с bounded failure code;
5. lifecycle `INITIALIZING` → продолжить polling только в пределах startup window;
6. lifecycle `FAILED` → `EXTENSION_INCOMPATIBLE`;
7. protocol/profile/version/capability mismatch → `EXTENSION_INCOMPATIBLE`;
8. полный согласованный READY descriptor → `AGENT_READY`;
9. при `tracingEnabled=false` однократное отсутствие marker, endpoint и external runtime
   после начала Spring bootstrap даёт `DISABLED`; любой presence signal немедленно
   отклоняется.

### 6.1 Version contract

Перед включением строгого сравнения `platform-tracing-spring-boot-autoconfigure` получает
те же manifest attributes, которые уже публикует Agent extension:

```text
Implementation-Title: platform-tracing-spring-boot-autoconfigure
Implementation-Version: <project.version>
```

В supported packaged runtime совместимость означает только точное непустое равенство
`Implementation-Version` application artifact и `ExtensionVersion` Agent MBean.

Для exploded unit/context test runtime допускается controlled test seam, в котором обе
стороны явно заданы как `development`. Это не production fallback и не system property.
Package-private constructor verifier получает version supplier для unit tests. Публичный
production path читает manifest и fail-closed отклоняет отсутствующую/blank версию.

Итоговое правило:

```text
packaged runtime: exact nonblank artifact-version equality
OR
isolated unit seam: both sides are explicitly "development"
```

Односторонний wildcard (`development` только с одной стороны) запрещён. Отсутствующая
application manifest version не преобразуется в `development` в production path.

E1 уже использует JAR `platform-tracing-spring-boot-autoconfigure-<version>.jar` в minimal
consumer subprocess; это подтверждено фактической command line. Поэтому E1 обязан проверять:

- manifest autoconfigure JAR содержит ожидаемые title/version;
- штатный Agent extension сообщает ту же version;
- readiness fixture получает текущую project version явно и не полагается на default
  `development`;
- packaged mismatch и missing extension version завершают startup fail-closed; missing
  application version проверяется unit seam, а archive gate не позволяет опубликовать такой
  autoconfigure JAR.

Равенство версий является compatibility check, а не доказательством целостности или
происхождения JAR. Supply-chain attestation остаётся обязанностью deployment gate
`RG-CONTROLLED-AGENT`.

### 6.2 Capability contract protocol v1

Application plane сохраняет собственный нормативный набор семи обязательных capabilities;
Agent-side `RequiredCapabilities` не является доверенным источником сам по себе.

Для protocol `1`:

- `RequiredCapabilities` должен в точности совпадать с application expected set;
- при `READY` `Capabilities` должен в точности совпадать с expected set;
- при `INITIALIZING` и `FAILED` `Capabilities` может быть только подмножеством expected set;
- неизвестные, пустые, повторяющиеся и слишком длинные capability names запрещены;
- пять boolean attestations должны точно соответствовать связанным capability values;
- расширение capability vocabulary требует protocol/profile decision, а не молчаливого
  принятия неизвестного значения.

Transport bounds для protocol v1 фиксируются рядом с probe и тестируются на границах:

```text
capability array length <= 7
capability/failure code length <= 64 UTF-16 code units
version length <= 128 UTF-16 code units
profile/lifecycle обязаны совпадать с закрытым protocol vocabulary
ISO control characters запрещены
```

### 6.3 Неатомарный transition read

Поскольку JMX getters вызываются последовательно, переход
`INITIALIZING → READY` может дать комбинацию `INITIALIZING` с уже полным capability set.
Она не считается несовместимостью и повторно читается в пределах текущего startup window.

Повторный read допустим только для валидного transition-state `INITIALIZING`. Wrong
type/name, protocol/profile/version mismatch, неизвестные capabilities, nonblank failure code
при `READY` и endpoint disappearance являются terminal failures и не ретраятся.

### 6.4 Endpoint disappearance

Если endpoint исчез после того, как был замечен в текущем startup verification, результат
не понижается до `AGENT_MISSING`. Это нарушение readiness lifecycle:

```text
state       = EXTENSION_INCOMPATIBLE
failureCode = READINESS_ENDPOINT_DISAPPEARED
```

Повторная регистрация и скрытый retry после такого события запрещены. Поведение сначала
фиксируется воспроизводимым тестом на текущей реализации, затем меняется отдельным
behavioral commit.

### 6.5 Failure taxonomy

Минимальные bounded codes:

```text
CONTROLLED_AGENT_MISSING
PLATFORM_EXTENSION_MISSING
APPLICATION_RUNTIME_DETECTED
READINESS_ENDPOINT_DISAPPEARED
READINESS_ACCESS_DENIED
READINESS_INVOCATION_FAILED
MALFORMED_READINESS_DESCRIPTOR
EXTENSION_READINESS_TIMEOUT
PROTOCOL_MISMATCH
PROFILE_MISMATCH
VERSION_MISMATCH
CAPABILITY_CONTRACT_MISMATCH
```

Agent-provided `failureCode` принимается только для lifecycle `FAILED`, если соответствует
`[A-Z0-9_]{1,64}`. Raw exception message, stack trace и Agent `FailureMessage` не
запрашиваются и в diagnostics/status не входят.

### 6.6 Startup timing и dynamic attach

Двухсекундное FORBIDDEN window удаляется. Оно не доказывает отсутствие dynamic attach после
истечения окна и создаёт latency при каждом intentional disabled startup. Проверка
выполняется после входа приложения в Spring bootstrap, то есть после startup `premain`.

Polling сохраняется только когда tracing обязателен, readiness endpoint присутствует и
descriptor находится в допустимом `INITIALIZING`. Default timeout `2s` остаётся внутренней
константой этого пути.

`platform.tracing.test.startup-readiness-timeout-ms` удаляется из production-кода,
`build.gradle`, configuration inventory и документации. Unit tests передают timeout/clock/
sleeper через package-private constructor. Контекстным disabled-тестам больше не требуется
сокращать окно.

Presence-сигналы marker/global/user-bean фиксируются до первого probe и не перечитываются в
poll loop. Dynamic attach после Spring startup остаётся неподдерживаемым deployment-сценарием. Его
запрет и pre-JVM supply-chain verification принадлежат RG-CONTROLLED-AGENT, а не Spring
observer.

## 7. Карта затрагиваемых модулей и файлов

### 7.1 Production

| Модуль | Файлы/область | Изменение |
| --- | --- | --- |
| `platform-tracing-spring-boot-autoconfigure` | `.../agent/*.java` | Новые probe/classifier/verifier, удаление transitional типов, rename marker detector |
| `platform-tracing-spring-boot-autoconfigure` | `TracingRuntimeAutoConfiguration.java` | Composition через один verifier; существующий static BFPP guard сохраняется |
| `platform-tracing-spring-boot-autoconfigure` | startup health, status assembler/auto-configuration, observation integration | Миграция на immutable startup diagnostics и новое marker-name |
| `platform-tracing-spring-boot-autoconfigure` | `build.gradle` | Manifest title/version; удаление production-доступного test timeout wiring |
| `platform-tracing-autoconfigure-webmvc` | `WebMvcSuppressMicrometerTracingAutoConfiguration.java` | Механический rename detector |
| `platform-tracing-autoconfigure-webflux` | `WebFluxSuppressMicrometerTracingAutoConfiguration.java` | Механический rename detector |

Agent extension production sources, JMX interface, ObjectName, SDK composition и physical
13-attribute MBean ABI этим планом не меняются.

### 7.2 Tests, fixtures и governance

| Область | Изменение |
| --- | --- |
| autoconfigure agent/context/status/architecture tests | Characterization, exact state/wire/public-surface gates, миграция старых типов |
| `platform-tracing-e2e-tests/build.gradle` и E1 attestation fixture | Передача project version fixture, exact/missing/mismatch packaged cases, manifest assertions |
| Agent extension tests/baseline | Только cross-module proof физического 13-attribute ABI и capability parity; production MBean не правится |
| architecture allowlist/type ledger/config inventory | CREATE/RENAME/DELETE и удаление test timeout property |
| ADR, runbook, traceability, inventory | Синхронизация фактической startup architecture без переписывания historical logs |

### 7.3 Dependencies и publication metadata

- новые production dependencies и новые модули не добавляются;
- BOM, published POM и Gradle Module Metadata dependency graph не меняются;
- единственный intentional publication-metadata delta — manifest
  `platform-tracing-spring-boot-autoconfigure` с `Implementation-Title` и
  `Implementation-Version`;
- Agent extension manifest и physical JMX ABI не меняются;
- удаление лишних public Java типов и rename detector выполняются без compatibility shims,
  поскольку решение ещё не выпущено в production.

## 8. Реализационные slices

Каждый slice должен завершаться зелёным focused build и отдельным логическим commit.

### Slice 0 — baseline и characterization, без production changes

- [x] Зафиксировать чистый/атрибутированный baseline текущего WIP (`c1565430`).
- [ ] Переименовать только тестовый файл `SdkModeResolverTest.java` в имя фактического
  класса либо отложить rename в Slice 3; не смешивать с behavior.
- [ ] Добавить characterization matrix текущего observer:
  marker/endpoint/global/user-bean/lifecycle/expectation.
- [ ] Добавить тесты текущего version wildcard.
- [ ] Зафиксировать, что текущий packaged autoconfigure JAR не содержит
  `Implementation-Version`, а Agent extension JAR содержит project version.
- [ ] Добавить воспроизводимый тест disappearance между registration и read.
- [ ] Добавить characterization, доказывающий текущий read+discard `FailureMessage`.
- [ ] Добавить тест near-wrap `nanoTime` для текущего polling.
- [ ] Зафиксировать текущую двухсекундную FORBIDDEN задержку и test-only system property.
- [ ] Зафиксировать, что production status использует startup snapshot.

Exit: все новые тесты зелёным тестом описывают текущий факт, включая нежелательный. Red,
disabled и expected-failure тесты не используются. Production diff отсутствует.

### Slice 1 — transport extraction, behavior preserving

- [ ] Создать package-private `AgentReadinessSnapshot`.
- [ ] Создать package-private `AgentReadinessProbe`.
- [ ] Перенести JMX attribute names, conversion и bounds из observer в probe.
- [ ] Сохранить scalar-read behavior на первом commit, чтобы extraction был проверяемым.
- [ ] Добавить probe unit tests для каждого типа/отсутствующего поля/null/oversize массива.
- [ ] Доказать defensive copy и unmodifiable deterministic sets.

Exit: observer делегирует transport probe; state outcomes baseline не изменились.

### Slice 2 — exact batch wire contract

- [ ] Перевести probe на один `getAttributes` с полным списком из 12 разрешённых имён.
- [ ] Исключить `FailureMessage` из runtime request и доказать это recording MBean test.
- [ ] Декодировать `AttributeList` по именам и fail-closed отклонять неполный, дублированный
  или неожиданный результат.
- [ ] Включить `RequiredCapabilities` в декодирование.
- [ ] Сверять Agent-required set с application expected set и применять exact/subset rules
  protocol v1 для каждого lifecycle.
- [ ] Проверять bounds, vocabulary и согласованность capability membership с пятью boolean
  attestations.
- [ ] Добавить cross-module test против реального MBeanInfo Agent readiness MBean.
- [ ] Доказать, что application main source не импортирует Agent implementation types.
- [ ] Не заявлять atomicity batch read в Javadoc/ADR.

Exit: malformed/partial/mismatched descriptor всегда fail-closed; Agent E2E остаётся green.

### Slice 3 — pure classifier и startup verifier

- [ ] Создать package-private `AgentReadinessClassifier`.
- [ ] Перенести в него state matrix и exact compatibility rules.
- [ ] Создать public `AgentStartupVerifier`.
- [ ] Перенести в verifier polling, timeout и enabled/disabled validation.
- [ ] Удалить неиспользуемый параметр `expectation` из observation boundary.
- [ ] Использовать elapsed-time subtraction вместо `deadline = now + timeout`.
- [ ] Удалить FORBIDDEN polling: проверять disabled absence один раз после Spring bootstrap.
- [ ] Удалить `platform.tracing.test.startup-readiness-timeout-ms` из production-кода,
  Gradle test wiring и configuration inventory.
- [ ] Фиксировать marker/global/user-bean signals один раз до первого probe; в REQUIRED loop
  повторять только readiness read для валидного `INITIALIZING`.
- [ ] При interrupt завершать startup fail-closed, сохранять interrupt flag и не переходить
  в busy loop.
- [ ] Реализовать endpoint-disappearance и bounded failure taxonomy.
- [ ] Ужесточить compatibility: packaged runtime принимает только exact nonblank version;
  controlled unit seam принимает только явно заданную пару `development`/`development`.
- [ ] Добавить `Implementation-Title`/`Implementation-Version` в manifest autoconfigure JAR
  и gate точного значения через чтение собранного archive.
- [ ] Удалить production fallback отсутствующей application version в `development`;
  оставить `development` только явным package-private unit-test input.
- [ ] Передавать readiness fixture текущую project version явно; добавить packaged exact,
  missing-extension и mismatch version E1 cases. Missing application version проверять
  unit-тестом supplier и archive manifest gate, без отдельного stripped-JAR fixture.
- [ ] Повторять read только для валидного transient `INITIALIZING`, не для terminal contract
  failures.
- [ ] Добавить exhaustive classifier tests без JMX.
- [ ] Добавить one-decision/no-hidden-retry tests verifier.

Exit: verifier является единственным владельцем startup decision; observer и validator ещё
могут существовать только как временные adapters до следующего commit.

### Slice 4 — composition migration и public-surface cleanup

- [ ] Перевести `TracingRuntimeAutoConfiguration` на `AgentStartupVerifier`.
- [ ] Flatten `TracingDeploymentDiagnostics` и переименовать `agentDetected` в
  `agentMarkerVisible`.
- [ ] Перевести startup health и `TracingStatusAssembler` на новый result.
- [ ] Явно назвать status snapshot startup/sticky; удалить вводящий в заблуждение live
  supplier test seam, если у него нет отдельного production owner.
- [ ] Переименовать `OtelAgentDetector` в `OtelJavaAgentMarkerDetector` и обновить
  webmvc/webflux/observation consumers.
- [ ] Удалить `AgentExtensionObserver`, `AgentExtensionDescriptor`,
  `AgentRuntimeContractValidator`, `AgentDeploymentExpectation`.
- [ ] Обновить public type allowlist и type ledger операциями CREATE/RENAME/DELETE.
- [ ] Добавить exact public-surface gate на четыре разрешённых типа.

Exit: package не содержит transitional wrappers, deprecated aliases или публичных
transport/classifier types.

### Slice 5 — documentation и release evidence

- [ ] Обновить `ADR-sdk-mode-detection.md` под Boot `3.5.16` и новую type topology.
- [ ] Обновить `docs/tracing/traceability.md` и удалить актуальные ссылки на `SdkMode*`.
- [ ] Исправить status/runbook examples (`authority`, runtime mode, agent startup state).
- [ ] Документировать sticky startup diagnostics и отсутствие live Agent monitor.
- [ ] Не переписывать historical discussion logs задним числом.
- [ ] Обновить inventory отдельной секцией `Post-refactoring verification`, сохранив
  исходный baseline как исторический факт.

Exit: код, ADR, runbook, allowlist и type ledger описывают одну архитектуру.

## 9. Тестовая стратегия

### 9.1 Unit

- exact transport type matrix для 12 application-side атрибутов;
- recording proof, что `FailureMessage` не запрошен;
- missing/duplicate/unexpected `AttributeList` names независимо от порядка;
- partial attribute list, duplicate names, null/blank/ISO-control strings;
- unknown lifecycle, wrong protocol/profile/version;
- empty, duplicate, unknown и incomplete capabilities;
- mismatch capability ↔ boolean attestation;
- all marker/endpoint/global/user-bean combinations;
- REQUIRED polling и FORBIDDEN one-shot validation;
- немедленный FORBIDDEN decision без двухсекундной задержки;
- отсутствие production/test metadata для `platform.tracing.test.startup-readiness-timeout-ms`;
- disappearance, access denied, invocation failure;
- interrupted REQUIRED wait с сохранённым interrupt flag;
- clock wrap и timeout boundary;
- immutable diagnostics/snapshot collections;
- exhaustive state/failureCode determinism.

### 9.2 Spring context

- enabled + ready Agent fixture → functional facade;
- enabled + absent/incomplete/incompatible Agent → startup failure;
- disabled + stable absence → NoOp facade;
- disabled + Agent/extension/external bean уже видим при однократной composition check →
  startup failure;
- один и два application `OpenTelemetry` beans → fail-fast;
- status и health используют тот же immutable startup result;
- servlet-only, reactive-only и combined classpath matrices.

### 9.3 Packaged Agent E2E

- Controlled Agent + extension + Spring application → `AGENT_READY`;
- marker visibility и current context visibility через раздельные classloaders;
- extension отсутствует при Agent marker → `EXTENSION_MISSING`;
- incompatible profile/protocol/version fixture → fail-closed;
- E2E readiness fixture содержит чувствительный sentinel, который не появляется в status/log;
  точное отсутствие getter-вызова доказывает unit recording `MBeanServer` по переданному
  массиву имён;
- disabled facade при активном Agent: Spring facade NoOp, Agent instrumentation остаётся
  активной согласно существующему контракту;
- no second SDK/processors/exporter registration;
- readiness MBean exact attribute/capability parity;
- autoconfigure и Agent extension JAR manifests содержат одинаковую project version;
  missing-extension/mismatched version fixture завершается fail-closed.

### 9.4 Architecture gates

- application main source не зависит от Agent extension implementation;
- starters не публикуют extension/SDK;
- public surface пакета равна exact allowlist;
- no ServiceLoader/application bean SPI для startup verifier;
- test-only timeout system property отсутствует в production bytecode и inventory;
- `TracingRuntimeAutoConfiguration` не создаёт SDK;
- ObjectName equality сохраняется между classloaders;
- wildcard import scan = 0.

## 10. Команды проверки

После каждого Java slice:

```powershell
.\gradlew.bat :platform-tracing-spring-boot-autoconfigure:compileJava `
  :platform-tracing-spring-boot-autoconfigure:compileTestJava --no-daemon

.\gradlew.bat :platform-tracing-spring-boot-autoconfigure:test --no-daemon
```

После rename detector и миграции sibling modules:

```powershell
.\gradlew.bat :platform-tracing-autoconfigure-webmvc:test `
  :platform-tracing-autoconfigure-webflux:test --no-daemon
```

После изменения wire verification:

```powershell
.\gradlew.bat :platform-tracing-otel-javaagent-extension:test `
  :platform-tracing-spring-boot-autoconfigure:test --no-daemon
```

Focused packaged attestation gate без Docker:

```powershell
.\gradlew.bat :platform-tracing-e2e-tests:test -PrunE2e `
  --tests "space.br1440.platform.tracing.e2e.probe.AgentExtensionAttestationE2ETest" `
  --no-daemon
```

Architecture/full verification:

```powershell
.\gradlew.bat pr1ModuleTaxonomyVerify pr4ArchitectureFitnessVerify --no-daemon
.\gradlew.bat build --no-daemon
```

Opt-in packaged E2E при доступном Docker:

```powershell
$env:DOCKER_HOST='tcp://192.168.100.70:2375'
.\gradlew.bat :platform-tracing-e2e-tests:test -PrunE2e --no-daemon
```

Финальные repository checks:

```powershell
git diff --stat
git diff --check
Select-String -Path (Get-ChildItem -Recurse -Filter *.java) `
  -Pattern "^\s*import\s+(static\s+)?[a-zA-Z0-9_.]+\.\*;"
```

Skipped E2E считается `NOT_RUN`, а не PASS.

## 11. Commit topology

Рекомендуемые commits:

1. `test(agent): characterize startup attestation state machine`
2. `refactor(agent): isolate readiness JMX transport`
3. `fix(agent): enforce complete batch readiness contract`
4. `refactor(agent): introduce startup verifier and pure classifier`
5. `refactor(agent): reduce public package surface`
6. `docs(agent): align startup attestation architecture`

Behavioral commits 3 и 4 не squash-ятся с extraction commit 2 до review: это сохраняет
видимость wire и state-machine изменений. Каждый commit обязан компилироваться и проходить
свой focused gate.

## 12. Rollback

- Slice 1 rollback механический: вернуть delegation в observer без изменения outcomes.
- Slice 2 rollback возвращает scalar probe, но не откатывает extracted transport boundary.
- Slice 3 rollback возвращает старую state machine adapter, сохраняя probe.
- Slice 4 выполняется только после зелёных Slice 2/3; его rollback возвращает composition
  wiring и allowlist, не Agent-side implementation.
- Ни один rollback не регистрирует второй SDK и не ослабляет fail-closed startup.

При первом расхождении packaged E2E с baseline работа останавливается на текущем зелёном
commit. Исправление E2E не должно менять Agent capability semantics без отдельного
архитектурного решения.

## 13. Definition of Done

План выполнен только когда одновременно истинны все условия:

- public surface пакета сокращена до четырёх обоснованных типов;
- JMX transport, pure classification и startup orchestration имеют разных владельцев;
- composition root использует один verifier facade;
- exact readiness wire contract включает `RequiredCapabilities`, 12 application-side
  атрибутов и явное исключение `FailureMessage`;
- physical Agent readiness MBean по-прежнему содержит 13 атрибутов, а разность с
  application request равна ровно `{FailureMessage}`;
- marker terminology нигде не обещает runtime readiness;
- packaged application/extension version берётся из проверяемых manifests и совпадает точно;
  missing, blank и mixed development/release отклоняются;
- endpoint disappearance и timeout имеют детерминированные тесты;
- startup presence-сигналы фиксируются один раз, а poll loop читает только readiness endpoint;
- FORBIDDEN startup не содержит искусственного ожидания, а REQUIRED timeout корректно
  обрабатывает interrupt и `nanoTime` wrap;
- status/health документированы как startup snapshot;
- нет SDK, processor, exporter или ServiceLoader ownership в Spring plane;
- focused, architecture, full build и обязательный packaged E2E зелёные;
- `git diff --check` зелёный, wildcard imports отсутствуют;
- type ledger, public allowlist, ADR, runbook и traceability синхронизированы;
- baseline `c1565430` сохранён, а последующие refactoring commits имеют собственный scope.

## 14. Итоговый go/no-go

`GO` для Slice 0 от baseline `c1565430` после повторного pre-flight.

`NO-GO` для production refactoring, пока:

- characterization tests не зафиксировали текущую state machine;
- не подтверждено, что физический 13-attribute MBean contract текущего HEAD и application
  12-attribute request отличаются ровно на `FailureMessage`;
- не добавлен и не проверен `Implementation-Version` autoconfigure JAR, без которого
  production version attestation фактически отключена.

`PRODUCTION ROLLOUT` остаётся отдельным решением и не разрешается фактом завершения этого
рефакторинга.
