# Slice E Spike E1: Agent Extension Attestation Evidence

> Дата: 2026-07-20  
> Ветка: `feature/runtime-control-hardening`  
> Исторический E1 evidence. Требование Controlled Distribution реализовано в E2 и проверено
> итоговым Slice E gate. Текущий нормативный статус: `CP-E PASS / RG-CONTROLLED-AGENT OPEN`;
> внешний release gate не блокирует Slice F.
> Статус: `SUPERSEDED AS DECISION INPUT`

## 1. E1 verdict

**E1 PARTIAL — CONTROLLED DISTRIBUTION REQUIRED.** Agent-first технически реализуем при
обязательной поставке OpenTelemetry Java Agent и совместимого platform extension как одного
неизменяемого, версионированного дистрибутива с pre-JVM проверкой целостности. Один starter-side
observer не обеспечивает fail-closed: stock Agent начинает auto-instrumentation и export до того,
как Spring может классифицировать отсутствие extension.

## 2. Repository evidence ledger

| Предположение | Кодовое доказательство | Исполняемое доказательство | Результат | Следствие |
|---|---|---|---|---|
| MBean ObjectName доказывает pipeline readiness | Старый registrar мог регистрировать доменные MBeans с отсутствующими компонентами | Fixture с readiness endpoint, но без processor, классифицирован как `EXTENSION_INCOMPATIBLE` | FALSE | Нужен versioned descriptor и capability validation |
| Agent marker доказывает готовность platform Agent | `OtelAgentDetector` проверяет только класс | Stock Agent без extension классифицирован как `EXTENSION_MISSING` | FALSE | Marker только evidence присутствия runtime, не readiness |
| NoOp facade останавливает Agent export | Facade и Agent принадлежат разным planes | Stock Agent экспортировал захваченный `Authorization` при NoOp facade | FALSE | Это policy/compliance bypass, не обычная деградация |
| Extension может подтвердить фактическую сборку pipeline | SPI callbacks известны в extension CL | Реальный Agent+extension достиг `READY` только после sampler, processors, sanitizer, propagator и protected export callback | CONFIRMED | Аттестация возможна без cross-CL object sharing |
| JMX wire classloader-neutral | Descriptor использует `String`, `Integer`, `Boolean`, `String[]` | Packaged app CL прочитал descriptor Agent extension CL | CONFIRMED | JMX boundary приемлем |
| `DISABLED` безопасно скрывает dual runtime | Старый resolver проверял disabled до collision | Unit и child-JVM `DISABLED`+Agent+app SDK завершаются ошибкой | FALSE/FIXED | Collision имеет приоритет над NoOp facade |
| Custom `TracingRuntime` безопасен как extension point | `@ConditionalOnMissingBean` позволял заменить owner | Spring topology test требует startup failure | FALSE/FIXED | Runtime принадлежит composition root |
| `GlobalOpenTelemetry.get()` безопасен при detection | `get()` может закрепить no-op | Autoconfigure использует `isSet()` + `getOrNoop()` до подтверждения runtime | FALSE/FIXED | Observer не изменяет global state |
| Полный extension sanitizes sensitive header | OAuth rule удаляет значение до export | Реальный Agent+extension экспортировал пустое значение без секрета | CONFIRMED | Защищённый export path доказан для Servlet HTTP |
| Stock Agent без extension безопасен | Stock Agent имеет собственный BSP/exporter | Реальный Jaeger получил исходный bearer token | FALSE | Нужен enforceable pre-start distribution gate |

## 3. Attestation protocol v1

ObjectName: `space.br1440.platform.tracing:type=Readiness,name=PlatformExtension`.

Профиль: `platform-agent-secure-v1`. Обязательные capabilities:

- `CONFIGURATION_LOADED`;
- `PLATFORM_SAMPLER_INSTALLED`;
- `REQUIRED_SPAN_PROCESSORS_INSTALLED`;
- `SANITIZER_INSTALLED`;
- `PROPAGATION_HOOKS_INSTALLED`;
- `SAFE_EXPORTER_INSTALLED`;
- `EXPORT_PATH_PROTECTED`.

Lifecycle имеет состояния `INITIALIZING`, `READY`, `FAILED`. `READY` вычисляется внутри
Agent extension только после получения всех capabilities. `FAILED` является sticky. JMX публикует
версию extension, protocol version, профиль, lifecycle, безопасные failure code/message, capability
array и независимые boolean-инварианты. Application observer проверяет protocol/profile/version,
полный набор capabilities, booleans, failure/lifecycle contradictions и не выполняет cross-CL casts.

## 4. Runtime state matrix

| Наблюдение | Application state | Поведение facade |
|---|---|---|
| `sdk.mode=DISABLED`, Agent отсутствует | `DISABLED` | NoOp |
| `sdk.mode=DISABLED`, Agent активен | readiness Agent (`AGENT_READY`/missing/failed) | Facade NoOp; Agent state не скрывается |
| Agent и readiness отсутствуют | `AGENT_MISSING` | Пока `STARTER`/NoOp; финальная policy остаётся Slice E decision |
| Agent есть, endpoint отсутствует | `EXTENSION_MISSING` | NoOp + гарантированный ERROR |
| Lifecycle `INITIALIZING` | `EXTENSION_INITIALIZING` | NoOp + ERROR |
| Protocol/profile/version/capability mismatch | `EXTENSION_INCOMPATIBLE` | NoOp + ERROR |
| Lifecycle `FAILED` | `EXTENSION_FAILED` | NoOp + ERROR с безопасной причиной |
| Полный совместимый descriptor | `AGENT_READY` | Agent-owned `GlobalOpenTelemetry`, рабочая facade |
| Agent runtime и application `OpenTelemetry` bean | `DUAL_SDK_DETECTED` | Startup failure, включая configured `DISABLED` |

`STARTER` и `EXTERNAL` не удалялись: E1 не утверждает финальную fleet-wide mode policy.

## 5. Security and fail-closed result

`AgentExtensionFailClosedSecurityE2ETest` выполнил два отдельных child JVM против Jaeger в
Gentoo Docker (`tcp://192.168.100.70:2375`):

1. Stock Agent 2.28.1 без extension экспортировал server span с
   `http.request.header.authorization=["Bearer e1-sensitive-value"]`.
2. Тот же запрос с настоящим platform extension экспортировал span без чувствительного значения;
   sanitizer оставил безопасное пустое array value.

Следовательно, Spring-side NoOp/error является только диагностикой после старта и не предотвращает
первый автоматически созданный span. До первого span действует только pre-JVM мера. Реализован
prototype `platformAgentDistribution`: pinned Agent + extension + SHA-256 manifest + launcher,
который проверяет оба JAR до `exec java`. Для production эта форма поставки должна стать
единственным разрешённым способом запуска; произвольный stock Agent остаётся compliance bypass.

## 6. Packaged scenarios

| Сценарий | Результат |
|---|---|
| No Agent | PASS, `AGENT_MISSING` |
| Stock Agent без extension | PASS, `EXTENSION_MISSING`, facade NoOp |
| Реальный compatible extension | PASS, `AGENT_READY`, context виден app facade |
| Protocol v99 | PASS, `EXTENSION_INCOMPATIBLE` |
| Endpoint без required processor | PASS, `EXTENSION_INCOMPATIBLE` |
| Lifecycle `INITIALIZING` | PASS |
| Sanitizer initialization failure / `FAILED` | PASS |
| Explicit `AGENT` без compatible extension | PASS, диагностируемый startup failure |
| Agent + application SDK | PASS, startup failure |
| `DISABLED` + Agent + application SDK | PASS, collision не скрыт |
| Stock/secure Servlet HTTP export | PASS, небезопасный и защищённый outcomes доказаны |
| Normal JVM exit | PASS; extension устанавливает idempotent shutdown cleanup для MBeans |
| WebFlux Agent context propagation | PASS: traceId и scoped remote-service переживают `publishOn` без второго SDK |
| Kafka Agent parity/batch links | UNVERIFIED: packaged Kafka Agent fixture в репозитории не найден |
| Declarative Agent configuration | Поддерживается OTel `-D`/env channel; отдельный declarative-file fixture не заявлен |

## 7. Verification commands

```powershell
.\gradlew.bat :platform-tracing-spring-boot-autoconfigure:test :platform-tracing-otel-extension:test --no-daemon
.\gradlew.bat :platform-tracing-e2e-tests:test --tests "*AgentExtensionAttestationE2ETest" -PrunE2e --no-daemon
.\gradlew.bat :platform-tracing-e2e-tests:test --tests "*SpringAgentCompositionE2ETest" -PrunE2e --no-daemon
$env:DOCKER_HOST='tcp://192.168.100.70:2375'
.\gradlew.bat :platform-tracing-e2e-tests:test --tests "*ReactorContextPropagationAgentE2ETest" -PrunE2e --no-daemon
$env:DOCKER_HOST='tcp://192.168.100.70:2375'
.\gradlew.bat :platform-tracing-e2e-tests:test --tests "*AgentExtensionFailClosedSecurityE2ETest" -PrunE2e --no-daemon
.\gradlew.bat :platform-tracing-otel-extension:platformAgentDistribution :platform-tracing-otel-extension:verifyPlatformAgentDistribution --no-daemon
```

Все перечисленные узкие проверки завершились успешно. Первый security-run был красным из-за
случайного test-classpath `OpenTelemetry` bean и подтвердил работу dual-runtime guard; fixture был
исправлен исключением test-only Boot OTel auto-configurations. Следующие два запуска сначала
зафиксировали array wire shape, затем безопасное пустое значение; финальный тест зелёный.

## 8. Remaining gates

1. Архитектурно утвердить controlled distribution как обязательный production deployment contract.
2. Добавить CI/deployment policy, запрещающую отдельный stock Agent и запуск в обход launcher/preflight.
3. Создать Kafka/batch-link packaged evidence до заявления полной Agent-mode parity.
4. Решить исходный Spring-without-Agent выбор A2/B1/B2; E1 не выбирает его автоматически.
