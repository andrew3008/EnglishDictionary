---
name: Рефакторинг конфигурации платформенного трейсинга
overview: >-
  Архитектурное направление утверждено. План содержит полный исполняемый
  backlog P0–P7 и подготовлен для рассмотрения архитектурным комитетом.
  Implementation GO не предоставлен. Закрыты E1, E2, E4, E5 и E9.
  Открыты E3, E6, E7 и E8.
status: architecture_plan_ready_for_committee_review
todos:
  - id: e1-jmx-contract-baseline
    content: "E1 COMPLETED: снять и закоммитить полный baseline JMX-контракта из 8 ObjectName и inventory потребителей"
    status: completed
  - id: e2-private-mbeanserver-proof
    content: "E2 COMPLETED: доказать private process-local MBeanServer, classloader isolation, remote-JMX isolation и lifecycle"
    status: completed
  - id: e4-configuration-inventory
    content: "E4 COMPLETED: создать воспроизводимый inventory 78 properties и 29 environment variables с exact-name disposition"
    status: completed
  - id: e5-category-a-contract
    content: "E5 COMPLETED: зафиксировать allowlist Category A из восьми runtime-mutable листьев"
    status: completed
  - id: e9-config-server-provenance
    content: "E9 COMPLETED: закоммитить и воспроизвести Config Server provenance Step 0B, T1–T10 и Git-backed process test"
    status: completed
  - id: architecture-plan-rewrite
    content: "PLAN COMPLETED: переписать канонический нормативный план для архитектурного комитета"
    status: completed
  - id: p0-e3-state-primitive-spike
    content: "P0/E3: сравнить generic и domain-specific accepted-state primitive по S-01"
    status: pending
  - id: p0-e3-state-primitive-decision
    content: "P0/E3: выбрать exact accepted-state primitive и зафиксировать API, placement и ownership"
    status: pending
  - id: p0-e3-performance-proof
    content: "P0/E3: доказать one-winner CAS, STALE без rebase, memory visibility и zero-allocation hot-path read"
    status: pending
  - id: p0-e6-cloud-bom-matrix
    content: "P0/E6: выполнить C1–C7 dependency matrix для Boot, Spring Cloud, BOM, starter и published consumer"
    status: pending
  - id: p0-e6-remove-eol-cloud-constraint
    content: "P0/E6: утвердить удаление misaligned EOL spring-cloud-context API constraint из публикуемого BOM"
    status: pending
  - id: p0-e6-refresh-delivery
    content: "P0/E6: выбрать production refresh delivery, владельца fan-out и требования авторизации"
    status: pending
  - id: p0-e7-jmx-read-delta
    content: "P0/E7: утвердить exact retain/delete/rewrite delta относительно baseline из 63 JMX members"
    status: pending
  - id: p0-e7-diagnostics-delta
    content: "P0/E7: утвердить итоговую read-only diagnostics projection и судьбу членов без named consumer"
    status: pending
  - id: p0-e8-implementation-go
    content: "P0/E8: получить явное Implementation GO архитектурного комитета"
    status: pending
  - id: p1-effective-policy-model
    content: "P1 ADD: реализовать immutable EffectiveTracingPolicy с ровно восемью Category A leaves"
    status: pending
  - id: p1-accepted-state-envelope
    content: "P1 ADD: реализовать accepted-state envelope с runtimeInstanceId, acceptedSequence и deterministic digest"
    status: pending
  - id: p1-accepted-state-store
    content: "P1 ADD: реализовать выбранный E3 accepted-state store с one-shot expected-base commit"
    status: pending
  - id: p1-apply-outcome-contract
    content: "P1 ADD: реализовать APPLIED, UNCHANGED, REJECTED, STALE и FAILED без скрытого rebase"
    status: pending
  - id: p1-policy-normalization-validation
    content: "P1 ADD: реализовать normalization, full-candidate validation, strict envelope и unknown-field rejection"
    status: pending
  - id: p1-canonical-digest
    content: "P1 ADD: реализовать versioned deterministic digest без Map.toString, locale drift и secret material"
    status: pending
  - id: p1-apply-diagnostics
    content: "P1 ADD: реализовать Agent apply diagnostics вне accepted-state CAS payload"
    status: pending
  - id: p1-audit-buffer
    content: "P1 ADD: реализовать metadata-only audit ring фиксированной ёмкости 32 записи"
    status: pending
  - id: p2-private-mbeanserver-owner
    content: "P2 ADD: productionize private MBeanServer owner с exactly-one lifecycle guard"
    status: pending
  - id: p2-private-marker
    content: "P2 ADD: реализовать marker protocol для discovery, protocol version и runtime instance"
    status: pending
  - id: p2-agent-open-type-codec
    content: "P2 ADD: реализовать strict Agent-side Open-Type codec"
    status: pending
  - id: p2-application-open-type-codec
    content: "P2 ADD: реализовать независимый Spring-side Open-Type codec без shared DTO"
    status: pending
  - id: p2-aggregate-writer
    content: "P2 ADD: зарегистрировать единственный internal aggregate writer на private MBeanServer"
    status: pending
  - id: p2-platform-control-projection
    content: "P2 REWRITE: подготовить read-only Control projection на platform MBeanServer под тем же ObjectName"
    status: pending
  - id: p2-jmx-registrar-lifecycle
    content: "P2 REWRITE: разделить platform/private registration и обеспечить unregister-before-release shutdown"
    status: pending
  - id: p2-private-boundary-regression
    content: "P2 TEST: перенести M1–M12 и N1–N7 в постоянные production-contract tests"
    status: pending
  - id: p3-sampling-reader-migration
    content: "P3 REWRITE: перевести CompositeSampler на единый immutable accepted policy snapshot"
    status: pending
  - id: p3-validation-reader-migration
    content: "P3 REWRITE: перевести ValidatingSpanProcessor на единый accepted policy snapshot"
    status: pending
  - id: p3-exporter-reader-migration
    content: "P3 REWRITE: перевести SafeSpanExporter gate на единый accepted policy snapshot"
    status: pending
  - id: p3-propagation-reader-migration
    content: "P3 REWRITE: перевести trace-control propagation gate на единый accepted policy snapshot"
    status: pending
  - id: p3-log-level-reader-migration
    content: "P3 REWRITE: перевести PlatformLogControl на единый accepted policy snapshot"
    status: pending
  - id: p3-remove-domain-holders
    content: "P3 DELETE: удалить SamplerStateHolder, ScrubbingPolicyHolder и ValidationPolicyHolder после equivalence proof"
    status: pending
  - id: p3-remove-duplicate-version-owners
    content: "P3 DELETE: удалить дублирующие version owners и ручные prev.version()+1 paths"
    status: pending
  - id: p3-hot-path-proof
    content: "P3 TEST: доказать behavior parity, memory visibility, zero allocation и P5/JMH budgets"
    status: pending
  - id: p4-authority-posture-guard
    content: "P4 COMPLETED: technological authority HELM/CONFIG_SERVER/ACTUATOR отделён от platform.environment; fail-closed matrix реализована"
    status: completed
  - id: p4-config-server-classifier
    content: "P4 COMPLETED: реализован per-key Config Server provenance classifier без local override и HELM fallback"
    status: completed
  - id: p4-complete-manifest-builder
    content: "P4 COMPLETED: complete manifest содержит ровно восемь Category A leaves"
    status: completed
  - id: p4-config-server-bridge
    content: "P4 COMPLETED: Config Server adapter использует общий TracingPolicySnapshotClient и private applySnapshot; activation отложена до P6 по C-05"
    status: completed
  - id: p4-refresh-coalescer
    content: "P4 COMPLETED: generation/dirty single-worker coalescing сохраняет final refresh"
    status: completed
  - id: p4-stale-retry-policy
    content: "P4 COMPLETED: Config Server выполняет один bounded full rebuild/retry только после STALE"
    status: completed
  - id: p4-dev-jmx-endpoint
    content: "P4 COMPLETED: один web-only namespace tracingControl содержит восемь typed ACTUATOR operations; private JMX остаётся внутренней delivery boundary"
    status: completed
  - id: p4-dev-command-service
    content: "P4 COMPLETED: SpringActuatorTracingPolicyAdapter выполняет read-copy-one-leaf-change-complete-snapshot без command service и STALE retry"
    status: completed
  - id: p4-application-readiness
    content: "P4 COMPLETED: application tracing-configuration readiness отделён от Agent readiness; только initial APPLIED/UNCHANGED даёт READY, initial failure блокирует readiness, runtime refresh не переписывает initial gate"
    status: completed
  - id: p4-actuator-read-model
    content: "P4 COMPLETED: secret-safe configuration projection встроена в canonical tracing endpoint и зависит только от AcceptedTracingPolicyReader; activation и legacy cutover остаются P6"
    status: completed
  - id: p5-complete-manifest-tests
    content: "P5 TEST: доказать initial missing-key readiness block и runtime missing-key REJECTED с LKG"
    status: pending
  - id: p5-refresh-storm-tests
    content: "P5 TEST: доказать convergence generation/dirty worker и отсутствие потерянного final refresh"
    status: pending
  - id: p5-config-server-outage-tests
    content: "P5 TEST: доказать initial fail-closed и runtime outage с сохранением LKG"
    status: pending
  - id: p5-secret-canary
    content: "P5 TEST: доказать отсутствие secret material в wire, digest, JMX, audit, metrics и logs"
    status: pending
  - id: p5-malformed-payload-tests
    content: "P5 TEST: доказать fail-closed для malformed, unknown, missing, null и oversized Open-Type payload"
    status: pending
  - id: p5-authority-negative-tests
    content: "P5 TEST: доказать invalid mode/authority combinations, local overrides и DISABLED no-discovery"
    status: pending
  - id: p5-jmh-performance
    content: "P5 TEST: выполнить JMH before/after для state reads, concurrent apply и allocation"
    status: pending
  - id: p5-m10-production-flow
    content: "P5 TEST: переписать и выполнить M10/M10c/M10d через Config Server и private writer"
    status: pending
  - id: p6-property-disposition
    content: "P6 REWRITE: применить exact-name disposition ко всем 78 properties и 29 environment variables"
    status: pending
  - id: p6-namespace-conflicts
    content: "P6 REWRITE: разрешить policy-version attribute, baggage families, force-record spelling и semconv contract"
    status: pending
  - id: p6-agent-parser-cutover
    content: "P6 REWRITE: атомарно переключить Agent bootstrap parser на final policy/javaagent names"
    status: pending
  - id: p6-spring-binding-cutover
    content: "P6 REWRITE: атомарно переключить Spring bindings, guard и configuration authority names"
    status: pending
  - id: p6-deployment-manifest-cutover
    content: "P6 REWRITE: синхронно обновить Helm, Config Server manifests, fixtures, samples и perf configuration"
    status: pending
  - id: p6-legacy-name-fail-fast
    content: "P6 ADD: реализовать exact legacy-name detector с replacement/deletion diagnostics"
    status: pending
  - id: p6-delete-refreshscope-mutation
    content: "P6 DELETE: удалить TracingRefreshScopeAutoConfiguration и RuntimeConfigApplier mutation path"
    status: pending
  - id: p6-delete-actuator-write
    content: "P6 DELETE: удалить HTTP @WriteOperation и mutation-enabled gate"
    status: pending
  - id: p6-delete-partial-control-protocol
    content: "P6 DELETE: удалить partial applyPolicy protocol, string-coercion codec и старые public control types"
    status: pending
  - id: p6-delete-domain-jmx-writes
    content: "P6 DELETE: удалить все 16 semantic platform-JMX mutations"
    status: pending
  - id: p6-delete-perf-admin-mutation
    content: "P6 DELETE: удалить PerfAdminController mutation path и устаревшие ObjectName"
    status: pending
  - id: p6-delete-test-benchmark-mutation
    content: "P6 DELETE: удалить direct mutation из test/benchmark сценариев после замены целевыми fixtures"
    status: pending
  - id: p6-zero-legacy-verification
    content: "P6 TEST: доказать ноль legacy writers, names, aliases, dual readers и platform JMX writes"
    status: pending
  - id: p6-coherent-cutover
    content: "P6 RELEASE: выполнить единый unreleased authority/namespace/writer switch без промежуточной публикации"
    status: pending
  - id: p7-supported-migration-changelog
    content: "P7 REWRITE: обновить SUPPORTED, MIGRATION и CHANGELOG по финальному контракту"
    status: pending
  - id: p7-public-contracts
    content: "P7 REWRITE: исправить public-contract inventory, semantic contract map и telemetry attribute migration"
    status: pending
  - id: p7-runbooks
    content: "P7 ADD: подготовить runbooks для HELM, CONFIG_SERVER, DEV_ACTUATOR, outage, drift и rollback"
    status: pending
  - id: p7-consumer-examples
    content: "P7 REWRITE: обновить samples, starter consumer fixtures, Helm и Config Server examples"
    status: pending
  - id: p7-jmx-consumer-migration
    content: "P7 REWRITE: задокументировать E7 JMX read delta и миграцию dashboards/operational consumers"
    status: pending
  - id: p7-deletion-scorecard
    content: "P7 ADD: сформировать measured scorecard по writers, owners, JMX/HTTP writes, names, types, tests и LOC"
    status: pending
  - id: p7-final-verification
    content: "P7 TEST: выполнить полный build, evidence tasks, published consumer, documentation и zero-legacy gates"
    status: pending
  - id: p7-release-readiness-review
    content: "P7 REVIEW: подтвердить выполнение release blockers и готовность полного unreleased train к rollout"
    status: pending
isProject: false
---

# План рефакторинга конфигурации платформенного трейсинга

## Нормативный словарь

| Термин | Смысл |
|---|---|
| **ОБЯЗАН** | Обязательное требование |
| **ЗАПРЕЩЕНО** | Явный запрет |
| **СОХРАНИТЬ** | Оставить без функционального удаления |
| **ПЕРЕПИСАТЬ** | Заменить реализацию при сохранении внешней роли |
| **УДАЛИТЬ** | Полностью убрать из поддерживаемого release |
| **ДОБАВИТЬ** | Ввести новый поддерживаемый элемент |
| **ДОПУСКАЕТСЯ** | Разрешено только при явных условиях ниже |

Идентификаторы **не переводятся**: `APPLIED`, `UNCHANGED`, `REJECTED`, `STALE`, `FAILED`, `E1`–`E9`, `ObjectName`, `MBeanServer`, `CompositeData`, Category A, Category B, Config Server, HELM, DEV_ACTUATOR.

## 1. Статус и исполнительное решение

| Поле | Значение |
|---|---|
| Архитектурное направление | **УТВЕРЖДЕНО** |
| Статус плана | **READY_FOR_ARCHITECTURE_COMMITTEE_REVIEW** |
| Implementation GO | **NOT_GRANTED** |
| Legacy deletion GO | **BLOCKED** |
| Фаза продукта | Pre-production; breaking cutover допускается |
| Binding decision | [14-category-a-binding-decision.md](../platform-tracing-configuration/14-category-a-binding-decision.md) |

Закрытые гейты: **E1, E2, E4, E5, E9**.

Открытые гейты: **E3, E6, E7, E8**.

Платформа **ОБЯЗАНА** сохранить Agent-first production topology и установить один immutable accepted policy snapshot на стороне Agent. Runtime policy **ОБЯЗАНА** содержать ровно восемь typed leaves Category A. Spring **ОБЯЗАН** классифицировать authority конфигурации, доказывать Config Server provenance, строить complete desired snapshots и вызывать один private process-local JMX writer через JDK Open Types. Публичный platform `MBeanServer` **ОБЯЗАН** после cutover выставлять только read-only diagnostics. Startup-only Agent configuration **ОБЯЗАНА** оставаться вне runtime policy и участвовать только в structural drift detection. Все legacy writers и legacy names **ОБЯЗАНЫ** быть удалены в одном unreleased train после закрытия оставшихся гейтов.

Документ **не предоставляет** implementation GO и **не предоставляет** authority на legacy deletion.

## 2. Область и нецели

### 2.1 Область

Реализация **ОБЯЗАНА**:

- консолидировать ownership runtime policy в Agent extension;
- установить один atomic accepted-state owner, выбранный E3;
- productionize private `MBeanServer` boundary, доказанную S-03;
- реализовать Config Server и developer adapters против одного complete-snapshot writer;
- мигрировать все 78 `platform.tracing.*` configuration properties и 29 напрямую читаемых environment variables по exact-name disposition S-04;
- удалить шесть legacy mutation entry-point families после утверждённой E7 JMX delta;
- сохранить Agent-first runtime и classloader isolation.

### 2.2 Нецели

- **ЗАПРЕЩЕНО** SDK-only production support.
- **ЗАПРЕЩЕНЫ** compatibility aliases.
- **ЗАПРЕЩЁН** новый Gradle-модуль.
- **ЗАПРЕЩЁН** generic configuration framework.
- **ЗАПРЕЩЕНА** HTTP mutation.
- **ЗАПРЕЩЕНЫ** long-lived dual paths.
- **ЗАПРЕЩЕНЫ** migration shims.
- **ЗАПРЕЩЕНЫ** public control DTOs.
- **ЗАПРЕЩЕНЫ** second SDK, alternate writer, polling Config Server client, patch apply.
- **ЗАПРЕЩЕНА** production implementation до E8.

## 3. Подтверждённое текущее состояние репозитория

Закоммиченное executable evidence устанавливает:

- 16 активных Gradle-модулей;
- Agent-first production topology;
- 78 `platform.tracing.*` configuration properties;
- 29 напрямую читаемых environment variables;
- 17 дублирующих объявлений;
- 2 группы type drift;
- 2 группы default/ownership drift;
- 8 JMX `ObjectName`;
- 63 live Standard MBean members;
- 16 semantic JMX mutations и 47 read-only members;
- 15 direct ungated platform-server mutations; только aggregate `applyPolicy(CompositeData)` использует `RuntimeControlMutationPolicy`;
- текущий partial protocol распознаёт больше полей, чем применяет `JmxRuntimePolicyApplier`;
- `TracingRefreshScopeAutoConfiguration` выполняет ungated live mutation;
- published BOM сочетает Boot 3.5.5 с `spring-cloud-context:4.1.5` как API constraint — misaligned, end-of-life Cloud train.

**S-02** (`docs/research/config-server-provenance-step-0b-reproduction.md`) доказывает per-key Config Server provenance, local-override detection, full-snapshot rebuilding и observation `EnvironmentChangeEvent`. **S-03** (`docs/research/private-mbeanserver-spike-s03.md`) доказывает factory-retained private `MBeanServer`, discoverable across Agent/App classloaders по strict marker protocol, invisible для remote connectors platform server. **S-04** (`docs/research/tracing-configuration-key-disposition.md`) — authoritative name inventory. **E1** baseline: `jmx-management-contract-baseline.json`.

## 4. Целевая ownership-модель

| Владелец | Исключительные обязанности |
|---|---|
| Agent extension | accepted runtime policy; private writer; strict Agent codec; structural drift status; apply diagnostics; bounded audit; read-only platform JMX projections |
| Spring autoconfigure | authority/posture guard; Config Server provenance и desired-state builder; event coalescing; application codec/client; DEV JMX endpoint; application readiness и Actuator read model |
| `platform-tracing-otel` | только generic reusable runtime primitives, переживающие E3; без Spring, JMX discovery и authority policy |
| `platform-tracing-api` | application tracing API only; без control-plane DTOs, state, codec и Open Types |

Accepted runtime state существует только в Agent classloader. Application objects **ЗАПРЕЩЕНО** передавать в Agent. Classloader boundary **ДОПУСКАЕТ** только JDK `CompositeData`, `CompositeType`, primitive wrappers, strings, arrays и maps, представимые approved Open Types. Application и Agent codecs **ОБЯЗАНЫ** быть независимыми реализациями одного versioned wire contract.

## 5. Mode/authority matrix

Authority выбирает единственный источник, которому разрешена запись `policy.*`; она не управляет обычными Spring adapter properties или внешними `otel.*` settings.

| Mode | Authority | Требуемое поведение |
|---|---|---|
| AGENT | HELM | Agent инициализирует final policy из env/`-D`; Spring не выполняет policy write |
| AGENT | CONFIG_SERVER | обязательны non-optional Config Client provenance и complete-snapshot writer |
| AGENT | DEV_ACTUATOR | local typed JMX commands только при explicit developer posture |
| DISABLED | HELM | нет Agent writer discovery; Spring tracing facade disabled |
| DISABLED | CONFIG_SERVER | fail-fast startup |
| DISABLED | DEV_ACTUATOR | fail-fast startup |

Ровно одна authority выбирается at startup. Missing, conflicting или unsupported authority configuration **ОБЯЗАНЫ** fail closed. Authority fallback **ЗАПРЕЩЁН**.

## 6. Configuration namespaces

| Namespace | Binding meaning | Owner |
|---|---|---|
| `platform.tracing.policy.*` | только восемь runtime Category A leaves | Agent accepted policy |
| `platform.tracing.javaagent.*` | Agent bootstrap, topology, structural policy, trust и safety envelopes | Agent |
| `platform.tracing.configuration.*` | authority, provenance, refresh orchestration и delivery posture | Spring |
| `platform.tracing.dev-control.*` | explicitly local developer control posture | Spring |
| retained `platform.tracing.*` | Spring adapter properties по S-04 | Spring |
| `platform.environment` | imported trust signal | deployment platform |
| `otel.*` / `OTEL_*` | external OpenTelemetry contract | OpenTelemetry/deployment |

Целевое пространство `platform.tracing.sdk.*` **ЗАПРЕЩЕНО**.

### 6.1 Явные исключения из построчной диспозиции S-04

S-04 **ОБЯЗАН** оставаться нормативным источником exact row disposition. Решение 14 и таблица пространств §6 **ДОПУСКАЮТ** переопределение S-04 **только** в закрытом перечне ниже. Blanket-правило переноса Agent-owned KEEP entries под `platform.tracing.javaagent.*` **ЗАПРЕЩЕНО**. Любое иное отклонение от S-04 **ОБЯЗАНО** иметь отдельную строку с inventory ID, текущим именем, целевым именем и обоснованием.

| Inventory ID | Текущее | Целевое | Обоснование |
|---|---|---|---|
| Decision 14 §5 (4 исключения Category A) | `sampling.drop-paths`, force-record-values, scrubbing built-in-rules/enabled | `platform.tracing.javaagent.*` | startup-only Category B по binding decision |
| CONFIG_PROPERTY-043 | `platform.tracing.sampling.force-record-header-values` | `platform.tracing.javaagent.sampling.force-record-values` | Decision 14 переопределяет S-04 `policy.*` target |
| CONFIG_PROPERTY-044 | `platform.tracing.sampling.force-record-values` | `platform.tracing.javaagent.sampling.force-record-values` | то же |
| CONFIG_PROPERTY-042 | `platform.tracing.sampling.force-record-header` | **KEEP** (текущее имя) | S-04 KEEP; blanket запрещён |
| CONFIG_PROPERTY-019…021 + baggage family | `platform.tracing.baggage.*` и `platform.tracing.propagation.baggage.*` | `platform.tracing.javaagent.propagation.baggage.*` | консолидация двух startup families |
| RESOURCE_ATTRIBUTE-001 | `platform.tracing.policy.version` (resource attribute) | `platform.tracing.resource.applied-policy-version` | конфликт с runtime namespace; **не** сливать с CONFIG_PROPERTY-036 |
| CONFIG_PROPERTY-036 | `platform.tracing.resource.policy-version` | **KEEP** | отдельный configuration key; не resource attribute |
| DOCUMENTED_ONLY_KEY-001 | `platform.tracing.semconv.validation.mode` | RENAME → `platform.tracing.semantic.validation-mode` | governance в P7 |
| CONFIG_PROPERTY-060 | `platform.tracing.semantic.validation-mode` | **KEEP** | активный Spring semantic-layer key |
| CONFIG_PROPERTY-024…028 (5 outbound) | Agent `platform.tracing.propagation.outbound.*` duplicates | удалить Agent declarations | DUPLICATED_ACTIVE; Spring canonical owner |
| FIXTURE_ONLY_KEY-005/006 | fixture `policy.sampling.ratio`, `policy.sampling.route-ratio` | DELETE fixtures; в P6 ввести Category A `route-ratios` | без dual-read и aliases |
| CONFIG_PROPERTY-032 | `platform.tracing.propagation.w3c.diagnostics-only` | DELETE | NO_READER |
| прочие fixture/dead keys | historical aliases, `service.k8s-pod-uid` и т.п. | DELETE | S-04 disposition |

Переименование resource attribute `applied-policy-version` — изменение telemetry contract; миграция dashboards **ОБЯЗАНА** выполняться в P7.

Ни один пункт §6.1 **не переоткрывает** Category A.

## 7. Category A

Runtime-mutable policy **ОБЯЗАНА** содержать ровно восемь leaves:

1. `platform.tracing.policy.sampling.enabled`
2. `platform.tracing.policy.sampling.ratio`
3. `platform.tracing.policy.sampling.route-ratios`
4. `platform.tracing.policy.validation.enabled`
5. `platform.tracing.policy.validation.strict`
6. `platform.tracing.policy.exporter.enabled`
7. `platform.tracing.policy.propagation.trace-control-headers.enabled`
8. `platform.tracing.policy.diagnostics.log-level`

Schema **ЗАПРЕЩЕНО** содержать maps произвольных property names, unknown fields, structural values, secrets, file paths и pipeline-composition rules.

Канонические типы: `boolean`, bounded `double`, immutable `Map<String, Double>`, closed log-level enum `OFF|ERROR|WARN|INFO|DEBUG|TRACE`. Route maps сохраняют numeric values и deterministic ordering; **ЗАПРЕЩЕНО** flatten в strings. Ratio validation остаётся `[0.0, 1.0]`. Collection/key bounds, ещё не доказанные, помечаются `BOUND_TO_BE_DERIVED_FROM_EXISTING_VALIDATOR`.

`validation.strict` valid только под startup envelope `platform.tracing.javaagent.validation.strict-runtime-allowed` (Category B; **ЗАПРЕЩЕНО** удалять или ослаблять).

Явно исключены из Category A (startup-only Category B):

- `platform.tracing.javaagent.sampling.drop-paths`
- `platform.tracing.javaagent.sampling.force-record-values`
- `platform.tracing.javaagent.scrubbing.built-in-rules`
- `platform.tracing.javaagent.scrubbing.enabled`

Policy **ОБЯЗАНА** передаваться и валидироваться как один complete snapshot.

## 8. Category B и structural drift

Category B values effective только из startup environment variables или system properties, читаемых Agent. Config Server **ДОПУСКАЕТ** carry non-secret desired values solely для drift comparison. Raw secrets **не имеют** desired-state, digest, audit или drift representation.

Initial startup mismatch effective vs authoritative desired Category B **ОБЯЗАН** block application readiness. Mismatch после readiness **ОБЯЗАН** retain accepted runtime policy, set `restartRequired=true`, expose bounded key names и require rollout/restart. **ЗАПРЕЩЕНО** вводить новый `DEGRADED` Agent lifecycle state.

Structural drift **ортогонален** runtime apply outcome:

- policy unchanged + drift: `UNCHANGED`, sequence unchanged;
- policy changed + drift: `APPLIED`, sequence incremented;
- invalid desired policy: accepted policy retained, `REJECTED`.

## 9. Accepted-state contract (E3)

Agent **ОБЯЗАН** владеть одной концептуальной ролью:

```text
AcceptedPolicyStateStore
    exact implementation selected by Gate E3 / S-01
```

`AcceptedPolicyStateStore` — **концептуальная роль**, а не одобренное имя production type. F1/F2 **не выбираются** в этом плане.

Implementation-independent binding contract E3:

- один Agent-side mutable accepted owner;
- одна loaded immutable reference на hot path;
- one expected-base commit;
- one winner; loser → `STALE`;
- **ЗАПРЕЩЕНЫ** internal retry/rebase;
- один `acceptedSequence` owner;
- zero allocation, lock и JMX на hot-path reads;
- current accepted state = logical LKG;
- attempt metadata, source revision, audit и failure text **вне** CAS payload.

E3 **ОБЯЗАН** выбрать и доказать exact primitive, API, placement, memory visibility, allocation behavior и conflict semantics до начала P1.

## 10. Apply outcomes

| Outcome | CAS | `acceptedSequence` | LKG | Retry |
|---|---|---|---|---|
| `APPLIED` | ровно один successful expected-base CAS | `+1` | replaced | none |
| `UNCHANGED` | no | unchanged | unchanged | none |
| `REJECTED` | no | unchanged | unchanged | none |
| `STALE` | attempted precondition/commit loses; no state change | unchanged | unchanged | store: none; Config Server adapter: one bounded full rebuild/retry |
| `FAILED` | no successful CAS | unchanged | unchanged | none |

**APPLIED:** ровно один successful expected-base CAS; `acceptedSequence +1`.

**UNCHANGED:** semantically identical normalized Category A policy; CAS отсутствует; `acceptedSequence` unchanged; идентичный digest → `UNCHANGED` даже при изменении source revision.

**REJECTED:** invalid, incomplete, untrusted или structurally forbidden candidate; CAS отсутствует.

**STALE:** runtime instance/sequence mismatch или проигранный one-shot CAS; **ЗАПРЕЩЕН** hidden rebase; accepted state unchanged.

**FAILED:** internal handler/codec/invariant failure; не ordinary CAS conflict.

Каждая попытка получает `attemptId`. Source revision **ДОПУСКАЕТСЯ** обновлять delivery diagnostics, но **ЗАПРЕЩЕНО** создавать новую accepted policy version без semantic change.

Structural drift остаётся ортогонален outcome.

## 11. Private JMX boundary

Production boundary **ОБЯЗАН** реализовать S-03 mechanism:

1. Agent вызывает `MBeanServerFactory.createMBeanServer`.
2. Agent регистрирует exact marker до writer.
3. Spring вызывает `MBeanServerFactory.findMBeanServer(null)`, фильтрует по marker, validates protocol/version/signature/runtime id/writer/lifecycle, requires exactly one valid marker.
4. **Один и тот же логический Control ObjectName** регистрируется на **обоих** серверах с разделением write exposure:
   - **Private MBeanServer:** тот же logical Control ObjectName; internal aggregate writer; internal read operation при необходимости.
   - **Platform MBeanServer:** тот же logical Control ObjectName; только E7-approved read-only projection; **ноль** writable attributes; **ноль** mutating operations.
   - Uniqueness `ObjectName` scoped к одному `MBeanServer`. S-03 доказала discovery и isolation; dual registration — **целевой контракт**, не результат спайка.
5. Shutdown явно unregisters writer, marker, projections и remaining private MBeans; `releaseMBeanServer`; clears references; verifies disappearance.
6. `mode=DISABLED` — no discovery.

Production tests **ОБЯЗАНЫ** сохранить M1–M12 и N1–N7 invariants, включая proof что old remote platform mutation удалена.

**E7 boundary:** preservation `ObjectName` — binding; platform write removal — binding; private writer — binding; exact platform read-member delta — **E7**. **ЗАПРЕЩЕНО** обещать сохранение всех read members; **ЗАПРЕЩЕНО** удалять read member только из-за отсутствия repository consumer.

## 12. Config Server lifecycle

`CONFIG_SERVER` authority **ОБЯЗАН** требовать:

- non-optional authoritative Config Data import (`spring.config.import=configserver:`);
- modern `configClient` marker;
- complete manifest всех восьми Category A leaves;
- per-key effective и remote-desired source evidence;
- fail-closed rejection command-line, system, environment или local-file overrides `policy.*`;
- legacy `configService` bootstrap → fail-fast с migration message;
- `EnvironmentChangeEvent` как observation trigger (keys — triggers only; bridge строит full snapshot);
- one generation/dirty coalescer и one worker;
- complete snapshot rebuilding, **never** patch apply;
- one bounded rebuild/retry после `STALE`;
- **ЗАПРЕЩЕНЫ** polling client и HELM fallback;
- один владелец defaults; complete manifest authoritative; `sourceRevision` **не** ordering token; initial failure ≠ runtime outage.

**C-01 — удаление remote key:**

| Фаза | Missing required Category A leaf |
|---|---|
| Initial load | candidate **REJECTED**; application configuration readiness **blocked** |
| Runtime refresh | candidate **REJECTED**; accepted policy unchanged; `acceptedSequence` unchanged; LKG retained |

Возврат к default: Config Server **ОБЯЗАН** опубликовать explicit canonical default value.

**ЗАПРЕЩЕНО** восстанавливать deleted managed key из HELM bootstrap, current accepted state, Spring field default, Java primitive default или другого PropertySource.

Initial fetch, provenance, manifest, validation, private-writer discovery и initial apply **ОБЯЗАНЫ** succeed до application configuration readiness. Runtime Config Server outage retains LKG, records delivery failure, не стирает accepted policy.

Exact Boot/Cloud versions и refresh fan-out mechanism — под E6.

## 13. HELM lifecycle

При HELM authority:

- Agent premain читает final `policy.*` и `javaagent.*` из env/`-D`;
- initial accepted state built и validated до instrumentation hooks ready;
- Spring **не** выполняет policy write и private-writer discovery;
- YAML-only Agent policy invalid (Spring YAML не конфигурирует isolated Agent bootstrap);
- **ЗАПРЕЩЁН** runtime writer для Spring и remote management;
- HELM **не** становится runtime fallback для CONFIG_SERVER.

## 14. DEV_ACTUATOR lifecycle

DEV_ACTUATOR **ОБЯЗАН** expose typed commands только для восьми Category A leaves. **ЗАПРЕЩЕНЫ** Category B commands и generic raw snapshot command.

Каждая команда:

1. reads runtime instance id и expected accepted sequence;
2. copies complete accepted policy;
3. changes exactly one typed leaf;
4. validates complete candidate;
5. sends complete snapshot через private JMX writer;
6. **не** retries `STALE`.

Endpoint — local developer posture, не production remote writer. Production startup **ОБЯЗАН** reject DEV_ACTUATOR authority.

## 15. Readiness и health

Четыре состояния **ОБЯЗАНЫ** оставаться distinct:

| State | Meaning |
|---|---|
| Agent readiness | controlled extension, processors/hooks, private writer и accepted store ready |
| Application tracing-configuration readiness | selected authority, provenance/manifest, writer discovery и initial apply valid |
| Runtime delivery diagnostics | latest Spring-to-Agent attempt/outcome, revision, conflict и outage evidence |
| Structural drift diagnostics | effective vs desired Category B и restart requirement |

Capability `CONFIG_SNAPSHOT_V1` означает protocol support only. Health **ЗАПРЕЩЕНО** infer accepted truth из Spring properties или delivery status.

## 16. Security

Target **ОБЯЗАН** enforce:

- private writer isolation;
- read-only platform JMX после cutover;
- exact bounded Open Types;
- schema allowlist и unknown-field rejection;
- secret exclusion;
- bounded strings, collections, payloads, audit records, failure text;
- per-key Config Server provenance;
- **ЗАПРЕЩЕНЫ** signed snapshots без independent signer и key owner;
- **ЗАПРЕЩЁН** remote writer fallback;
- **ЗАПРЕЩЕНА** generic raw snapshot developer command.

### C-09 — явное принятие текущего security debt (pre-production)

**Владелец риска:** команда `platform-tracing`.

Принятие действует **только** для pre-production до coherent P6 cutover. **ЗАПРЕЩЕНО** создавать промежуточный P0 security design в рамках этого review.

| Условие | Требование |
|---|---|
| Production contract | текущие platform JMX writes **не** являются supported production contract |
| Production release | **ЗАПРЕЩЁН** до удаления всех legacy mutation paths |
| Remote JMX | **ЗАПРЕЩЁН** write access в shared/external environments |
| P6 cutover | удалить все 16 JMX mutations, ungated RefreshScope path, Actuator write, partial protocol, `PerfAdminController` mutation path, test/benchmark direct mutation |
| Platform MBeanServer до release | **ноль** writable attributes; **ноль** mutating operations |
| Production writer | private writer — **единственный** |
| Release gate | нарушение любого условия **блокирует** release независимо от прочих задач |

Подтверждённый текущий debt: 15 direct ungated platform-server mutations; successful remote invocation; ungated RefreshScope mutation; partial-protocol silent success.

P2 создаёт isolated writer до legacy deletion. P4 switches supported writers. P6 удаляет atomically с namespace cutover.

## 17. Diagnostics и audit

| Truth category | Owner |
|---|---|
| accepted policy, digest, runtime id, sequence | Agent accepted store |
| structural drift и restart requirement | Agent drift projection |
| Agent apply-attempt evidence | Agent apply diagnostics |
| Spring delivery/provenance evidence | Spring delivery diagnostics |

Agent audit — 32-entry bounded in-memory metadata-only ring; не replay, persistence, recovery или compliance store. **ЗАПРЕЩЕНО** хранить raw policy values, routes, headers, secrets, stack traces, unbounded messages.

Committed consumer inventory различает `CLIENT`, `ACTUATOR`, `HEALTH`, `TEST`, `DOC` и `NO_NAMED_CONSUMER_FOUND`. E7 решает member-level read projection.

## 18. Module-by-module changes

| Module | ДОБАВИТЬ | ПЕРЕПИСАТЬ | СОХРАНИТЬ | УДАЛИТЬ |
|---|---|---|---|---|
| `platform-tracing-bom` | Cloud constraints после E6 | Cloud alignment | Boot/OTel imports | 4.1.5 API constraint |
| `platform-tracing-api` | — | semconv correction | tracing facade, span API | public control protocol после replacement |
| `platform-tracing-otel` | E3 primitive if domain-neutral | `VersionedStateHolder` if S-01 reuse | validators, snapshots | duplicate version owners |
| `platform-tracing-spring-boot-autoconfigure` | authority guard, provenance, builder, coalescer, client | `TracingProperties`, Actuator, JMX client | Spring adapters | `RuntimeConfigApplier`, writes, RefreshScope mutation |
| `platform-tracing-autoconfigure-webmvc` | — | readiness if needed | MVC adapters | accidental mutation |
| `platform-tracing-autoconfigure-webflux` | — | readiness if needed | WebFlux adapters | accidental mutation |
| `platform-tracing-spring-boot-starter-servlet` | — | metadata after E6 | composition | leaky transitive deps |
| `platform-tracing-spring-boot-starter-reactive` | — | metadata after E6 | composition | leaky transitive deps |
| `platform-tracing-otel-javaagent-extension` | accepted store, codec, private server, diagnostics | registrar, readers | SPI, processors | 16 legacy writes после E7/P6 |
| `platform-tracing-collector-config` | — | docs if keys renamed | pipeline | stale refs |
| `platform-tracing-test` | arch rules if needed | fixtures | support | obsolete fixtures |
| `platform-tracing-samples` | final examples | exact keys | API samples | legacy names |
| `platform-tracing-bench` | microbenchmarks if E3 here | fixtures | JMH harness | legacy benchmarks |
| `platform-tracing-e2e-tests` | authority matrix, delivery, JMX fixture | S-02/S-03 fixtures | evidence source sets | legacy fixtures |
| `platform-tracing-perf-tests` | M10 scenarios | manifests | macro scenarios | legacy mutation |
| `platform-tracing-perf-harness` | P5 outputs | wiring | reference tier | obsolete aliases |

Каждый новый production type **ОБЯЗАН** быть mapped в implementation PR.

## 19. Implementation phases P0–P7

### P0 — Remaining evidence closure

| | |
|---|---|
| Preconditions | E5 closed |
| ДОБАВИТЬ | S-01 state proof, S-06 dependency matrix, E7 JMX delta checklist |
| ПЕРЕПИСАТЬ | plan only if evidence falsifies contract |
| УДАЛИТЬ | none |
| Tests | S-01 concurrency; C1–C7 matrix; JMX delta verifier |
| Exit | E3, E6, E7 closed; E8 explicitly granted |
| Rollback | evidence commits independently revertible |

**Public-control deletion prerequisite (`p0-public-control-deletion-prerequisite`): CLOSED
(2026-07-28).** In-repo inventory confirms zero external/non-product FQCN consumers of
`api.control.protocol` public types; product-module consumers remain and must migrate
before deletion; committee `legacyDeletionGo` keeps breaking deletion blocked until P6.
Evidence: `docs/research/public-control-protocol-deletion-prerequisite-p0.md`.

### P1 — Accepted-state foundation

| | |
|---|---|
| Preconditions | E3 и E8 closed |
| ДОБАВИТЬ | accepted policy, store, validator, digest, outcomes, diagnostics, audit |
| ПЕРЕПИСАТЬ | generic primitive per E3 |
| УДАЛИТЬ | duplicate version owner after equivalence proof |
| Tests | race, stale, unchanged, LKG, visibility, allocation, digest |
| Exit | one state owner; no production writer switched |
| Rollback | P1 commit set |

### P2 — Private control boundary

| | |
|---|---|
| Preconditions | P1 green; S-03 accepted |
| ДОБАВИТЬ | private server owner, marker, codec, aggregate writer |
| ПЕРЕПИСАТЬ | registrar lifecycle, read projections |
| УДАЛИТЬ | none |
| Tests | M1–M12, N1–N7, malformed Open Types, RMI isolation |
| Exit | private writer works; legacy unchanged |
| Rollback | P2 commit set |

### P3 — Agent reader migration

| | |
|---|---|
| Preconditions | P2 green |
| ДОБАВИТЬ | adapters accepted policy → consumers |
| ПЕРЕПИСАТЬ | sampler, validation, exporter, propagation, diagnostics readers |
| УДАЛИТЬ | three domain holders per E3 |
| Tests | hot-path parity, golden sampling, strict envelope |
| Exit | все 8 leaves read from one owner |
| Rollback | P3 + P1/P2 |

**C-05 — unreleased overlap до P6 допускается только когда:**

- target production path недостижим из любой supported posture;
- target и legacy writer **не** активны одновременно;
- intermediate artifact **не** публикуется;
- tests используют spike/test-only activation;
- P6 выполняет единый switch и deletion.

**ЗАПРЕЩЕНЫ** два live policy owners или writers в supported runtime.

### P4 — Config Server и DEV adapters

| | |
|---|---|
| Preconditions | E6 closed; P3 green |
| ДОБАВИТЬ | authority guard, provenance, builder, coalescer, client, DEV commands, readiness |
| ПЕРЕПИСАТЬ | Actuator read model, JMX client |
| УДАЛИТЬ | no writer deletion yet |
| Tests | manifest, local override, **deleted-key REJECTED**, outage, stale retry, DEV no-retry, mode matrix |
| Exit | one behavior per authority |
| Rollback | P4 commit set |

### P5 — Failure, performance, security proof

| | |
|---|---|
| Preconditions | P4 green |
| ДОБАВИТЬ | secret canary, cardinality, JMH, M10/M10c/M10d |
| Tests | refresh storm, malformed input, RMI, concurrency, budgets |
| Exit | budgets и security invariants pass |
| Rollback | P5 adjustments |

### P6 — Coherent namespace и writer cutover

| | |
|---|---|
| Preconditions | P5 green; E7 approved delta; public control deletion prerequisite closed |
| ДОБАВИТЬ | final names, fail-fast legacy detector |
| ПЕРЕПИСАТЬ | Agent parser, Spring binding, manifests, fixtures, docs, wire schema |
| УДАЛИТЬ | все 6 legacy families, RefreshScope mutation, partial protocol, old names, public control types после prerequisite |
| Tests | 78/29 disposition, deleted-key REJECTED, JMX delta, full build |
| Exit | no legacy writer/name in supported release |
| Rollback | **entire unreleased train** |

### P7 — Documentation и scorecard

| | |
|---|---|
| Preconditions | P6 green |
| ДОБАВИТЬ | runbooks, authority examples, measured scorecard |
| ПЕРЕПИСАТЬ | SUPPORTED, MIGRATION, CHANGELOG, contracts |
| Exit | committee acceptance visible |
| Rollback | P7 docs; code rollback = whole train |

## 20. Legacy deletion matrix

### Шесть mutation entry-point families (C-03)

| # | Family | Current entry | Gate | Target replacement | Phase | Zero-ref |
|---|---|---|---|---|---|---|
| 1 | RefreshScope reconcile | `TracingRefreshScopeAutoConfiguration` → `RuntimeConfigApplier` | **none** | private aggregate writer via Config Server / DEV only | P6 | Spring scan |
| 2 | Actuator HTTP write | `TracingActuatorEndpoint` `@WriteOperation` | `mutation-enabled` default false | DEV typed JMX only (non-prod) | P6 | endpoint introspection |
| 3 | Partial Control protocol | `applyPolicy(CompositeData)` | gated | private aggregate writer | P6 | protocol tests |
| 4 | Direct domain JMX | 15 ungated domain mutators | **none** | private writer only | P6 | `jmxManagementContractVerify` |
| 5 | PerfAdminController | HTTP admin endpoints | **none** (dead ObjectNames) | delete | P6 | perf module scan |
| 6 | Test/benchmark direct | JMH, smoke mains | test-only | test fixtures only | P6 | test scan |

### Live JMX surface

```text
8 writable attributes
8 mutating operations
= 16 semantic JMX mutations
47 read-only members (baseline E1)
```

E7 **ОБЯЗАН** approve exact 63-member before/after delta. P6 **ОБЯЗАН** delete every legacy write с platform MBeanServer. `resetSamplerCounters` — mutation; **не** survives as diagnostic-only.

`RuntimeConfigApplier`, ungated RefreshScope, partial protocol apply, old Spring writes, direct domain setters **ОБЯЗАНЫ** быть удалены. Member deletion blocked до E7 и green private writer/read projections.

## 21. Property/env migration

S-04 generated inventory — source of truth для 78 properties и 29 environment variables. P6 **ОБЯЗАН** provide one exact disposition row per name. Wildcard migration, aliases, dual-read **ЗАПРЕЩЕНЫ**.

Legacy names **ОБЯЗАНЫ** fail fast с exact replacement или deletion message. Agent parser и Spring bindings switch together. Config Server manifest и Helm values switch в same release train.

## 22. Testing strategy

Required proof:

- accepted-state concurrency, one-winner CAS, stale и unchanged;
- strict allowlist codec и deterministic digest;
- private JMX lifecycle, marker ambiguity, attach/RMI isolation;
- full mode-authority matrix;
- S-02 provenance и non-optional import;
- complete eight-leaf manifest и **deleted-key REJECTED semantics (C-01)**;
- deleted legacy key behavior без aliases;
- refresh storm coalescing и one bounded stale retry;
- secret canary;
- exact JMX contract delta от committed baseline;
- fixed metrics cardinality;
- separate Agent/application readiness;
- M10/M10c/M10d;
- JMH/P5 budgets;
- published starter consumer и BOM matrix.

Skipped opt-in tests **ОБЯЗАНЫ** report `SKIPPED/NOT_RUN`, never PASS.

Evidence tasks: `configProvenanceVerify`, `jmxManagementContractVerify`, `privateMBeanServerVerify`, `tracingConfigurationInventoryVerify`.

## 23. Architecture fitness rules

Build **ОБЯЗАН** enforce:

- inventory deterministic at 78 properties и 29 env variables;
- runtime schema = exactly eight E5 leaves; reject unknown fields;
- `platform-tracing-api` без control DTO;
- no new Gradle module;
- only Agent owns accepted state и private writer;
- platform MBeanServer: zero writes после cutover;
- Spring: no domain setter, no HTTP write;
- no Config Client/Agent leak через starters;
- every legacy name absent после P6;
- BOM matches E6.

## 24. Dependency/BOM plan

Current: Boot 3.5.5 + `spring-cloud-context:4.1.5` API constraint (misaligned/EOL).

E6 **ОБЯЗАН** select corporate Boot patch, compatible Cloud train, C1–C7 resolution, remove 4.1.5 constraint, prove starter publication, update SUPPORTED и BOM atomically, rerun S-02 на approved stack, select refresh fan-out. Production bridge **ЗАПРЕЩЁН** до E6.

## 25. Cutover и rollback

До P6: P1–P5 revertible by train. После cutover: rollback unit = **entire unreleased release train**. State, property names, writers, manifests, readers **ЗАПРЕЩЕНО** rollback independently.

**ЗАПРЕЩЕНЫ** aliases и dual paths при rollback. Accepted state process-local; rebuilt from selected authority after restart.

## 26. Measured scorecard

P7 **ОБЯЗАН** calculate from final Git diff:

| Measure | Baseline | Target |
|---|---:|---|
| runtime writers | 16 JMX + refresh path | 0 (scans) |
| platform JMX writes | 16 | 0 |
| HTTP writes | Actuator mutation | 0 |
| properties/env | 78/29 | disposition diff |
| active modules | 16 | 16 |

## 27. Remaining gates

| Gate | Status | Closure |
|---|---|---|
| E3 | OPEN | S-01 accepted-state primitive |
| E6 | OPEN | Boot/Cloud/BOM + refresh delivery |
| E7 | OPEN | read-only JMX delta vs 63-member baseline |
| E8 | OPEN | committee grants Implementation GO |

E1, E2, E4, E5, E9 — **CLOSED**.

## 28. Implementation GO checklist

- [ ] E3 closed с executable concurrency и hot-path evidence
- [ ] E6 closed с C1–C7, BOM consumer, refresh fan-out
- [ ] E7 closed с exact JMX retain/delete/add
- [ ] Category A = exactly eight E5 leaves
- [ ] Unknown fields и generic maps rejected
- [ ] Category B names и strict-validation envelope fixed
- [ ] Agent/App Open Types и bounds approved
- [ ] Initial/readiness, refresh, stale, invalid, outage, drift accepted
- [ ] P0–P7 rollback units accepted
- [ ] Security debt: explicit pre-production acceptance до P6 cutover (§16)
- [ ] No new module, alias, public DTO, HTTP writer, second SDK, generic framework
- [ ] Committee records E8 closure separately

До последнего пункта: Implementation GO **NOT_GRANTED**; Legacy deletion **BLOCKED**.
