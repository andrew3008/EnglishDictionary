# ADR: Agent logging backend

Дата: 2026-08-17.
Статус: ACCEPTED после production qualification выбранной реализации.

## Решение

Typed path: `AgentLogEvent` → encoder → `PlatformStderrSink` над `FileDescriptor.err`.
Residual SLF4J adapter отсутствует. `slf4j-api` и `slf4j-simple` удалены из deployable extension.

JUL path не сохраняется: process-global `LogManager` не изолирован. Это JUL isolation FAIL, не дефект stderr sink.

## Schema ownership

Canonical schema owner: один файл
`platform-log-event.schema.json` в resources модуля
`platform-tracing-otel-javaagent-extension`.
Схема не входит в published logging starter и не входит в production
`agentExtensionJar`. YAML fields/security-policy удалены.

Корень схемы открыт. Encoder закрыт. Версия в имени файла, не только в `$id`.
Published wire limits: `logger.minLength` 1, `message`/`fields` values `maxLength` 512,
`error_stacktrace.maxLength` 4119.

Agent считает identity из локального owner-файла. Production
`agentExtensionJar` не содержит `platform-log-event.schema.json` и
`space/br1440/platform/logging/**`; несёт только schema identity
(properties resource + derived manifest attributes). Layout и child-JVM
проверки controlled Agent принадлежат модулю distribution.

Порог и уровень события — один enum `AgentLogLevel`.
`OFF` допустим только как настроенный порог, не как уровень события.

Имена проверяет `AgentLogFieldNamePolicy`. Текст нормализует `AgentLogTextSanitizer`.
Encoder гарантирует UTF-8 JSONL ≤ 8192.
`platform-log-masking-rules.yaml` — build/test vocabulary, не runtime Agent input.
Sensitive-name denylist не является DLP.

```text
CURRENT_APPLICATION_PROFILE_CONFORMANCE: NOT_IMPLEMENTED
FOLLOW_UP: APP-LOG-PROFILE-CONFORMANCE-001
```

Canonical schema owner established. Agent conformance implemented.
Application Logback profile conformance not implemented.

## `PlatformLogControl`

Не владеет effective runtime state. `isEnabled` / `getLevel` / `setLevel`
проецируются commit-first через `AgentLoggingRuntime.applyLevel`.
Отказ проекции сохраняет предыдущий effective threshold.

## `PlatformAgentLogger`

Module-internal public type. Surface: event code, обязательный `message`,
severity и typed fields. Не экспортирует encoder, sink или runtime identity.

`error_stacktrace` — digest-only security-tradeoff против ECS `error.message` /
`error.type` / `error.stack_trace`.

`EmitResult` принадлежит Agent delivery model.

`EMITTED` означает: каноническое кодирование завершено, bounded payload передан sink, sink API не сообщил synchronous failure. Это не persistence и не защита от чужих writer'ов stderr.

## Residual

```text
directSlf4jCallSites: 0
immutableResidualSlf4jCallSites: 0
thirdPartySlf4jCallSites: 0
```

Custom SLF4J provider запрещён как unnecessary.

## Performance

Методика утверждена. Жёсткие нормы: `0 B/op` на disabled path (caller guard + `isEnabled`) и `maxEncodedBytes: 8192`. Остальные числа остаются `REFERENCE_TARGET` до отдельного JMH прогона.
