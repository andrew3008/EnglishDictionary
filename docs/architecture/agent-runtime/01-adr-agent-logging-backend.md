# ADR: Agent logging backend after artifact-boundary cutover

Статус: ACCEPTED
Дата: 2026-08-17

## Решение

Одна production-реализация:

```text
PlatformAgentLogger
  → AgentLoggingRuntime
  → PlatformLogEventEncoder
  → PlatformStderrSink(FileDescriptor.err)
```

Осей backend/adapter нет. `JUL_DIRECT` и residual SLF4J adapter удалены: process-global `LogManager` не изолирован classloader'ом агента, а после миграции call-sites `directSlf4jCallSites: 0`.

`slf4j-api`, `slf4j-simple` и `simplelogger.properties` отсутствуют в deployable extension и distribution.

## Delivery semantics

`EMITTED` означает: каноническое кодирование завершилось, bounded payload передан sink, sink API не сообщил synchronous failure.

Sink — `FileOutputStream(FileDescriptor.err)`. `System.setErr` не перехватывает записи.
`PrintStream` sticky error flag не используется как per-event receipt.

`EmitResult` принадлежит Agent delivery model. Rate-limit и security принадлежат `PlatformAgentLogger`. Сбой кодирования или записи — `DELIVERY_FAILED`. Пустой `message` и запрещённое имя поля — `REJECTED_BY_SECURITY_POLICY`.

## PlatformLogControl

`PlatformLogControl` не владеет effective runtime state. `isEnabled` / `getLevel` / `setLevel`
проецируются commit-first через `AgentLoggingRuntime.applyLevel`. Отказ проекции сохраняет предыдущий порог.

## Schema ownership

Canonical schema owner: один файл
`platform-log-event.schema.json` в resources
`platform-tracing-otel-javaagent-extension`.
`$id` совпадает с именем файла. `title` остаётся `platform-log-event-v1`.
Dual v1/v2 не вводим: решение pre-production.

Корень схемы открыт (`additionalProperties: true`): аддитивное поле не ломает строгих потребителей.
Encoder закрыт: пишет только известные ключи, без reflection.
`fields` — `additionalProperties: { "type": "string", "maxLength": 512 }`.
`logger.minLength` = 1. Logger bound в policy — `MAX_LOGGER_CHARS` (512), в schema `maxLength` нет.
`message` и field values — `maxLength` 512 (`AgentLogEvent.MAX_ARGUMENT_CHARS`).
`error_stacktrace.maxLength` = 4119 (renderer body 4096 + truncation suffix).

Agent считает schema identity на build/test из локального owner-файла и вшивает только
`PLATFORM_LOG_SCHEMA_SET_SHA256_V1` identity в `agentExtensionJar`
(properties + derived manifest fragment). Production JAR schema не содержит.
YAML-дубли fields/security-policy удалены.
Лимиты message/value живут в `AgentLogEvent`. Имена и `MAX_FIELDS` —
в `AgentLogFieldNamePolicy`. Текст — в `AgentLogTextSanitizer`.
Это не DLP: denylist не ищет секреты в values.

`AgentLogLevel` задаёт и порог, и уровень события. `OFF` непредставим на событии.

```text
CURRENT_APPLICATION_PROFILE_CONFORMANCE: NOT_IMPLEMENTED
FOLLOW_UP: APP-LOG-PROFILE-CONFORMANCE-001
```

Canonical schema owner established. Agent conformance implemented.
Application Logback profile conformance not implemented.

## Facade surface

`PlatformAgentLogger` — module-internal public type. Публичные методы принимают
event code, обязательный человеческий `message`, optional `Throwable` и typed string fields.
Не экспортирует encoder, sink или runtime identity.

Цепочка emit:

```text
PlatformAgentLogger
  → AgentLogFieldNamePolicy / AgentLogTextSanitizer
  → bounded AgentLogEvent
  → PlatformLogEventEncoder
  → полное событие ИЛИ PTA-ENCODE-001 / ENCODE_FALLBACK
```

Единственный production caller `AgentLogEvent` constructor — `PlatformAgentLogger`.

Порядок ключей JSONL: `timestamp`, `level`, `logger`, `message`, `event_code`, `fields`,
optional `error_stacktrace`.

Имена root-полей запрещены как keys внутри `fields`.
Encoder fallback не копирует исходные logger/message/fields/throwable.
Message fallback: `Platform Agent log event could not be encoded safely`.

## `error_stacktrace` digest-only

`error_stacktrace` — bounded digest, не ECS `error.message` / `error.type` / `error.stack_trace`.

Сознательный security-tradeoff: в JSON нет raw exception message и raw frame class names.
Поля ECS error.* не добавляем. Digest пишется только если throwable был передан.

## Performance

Методика и baseline `CLEAN_BOUNDARY_SIMPLE` утверждены.
Жёсткие нормы: `0 B/op` на disabled path (caller guard + `isEnabled`), `maxEncodedBytes: 8192`.
Остальные числа остаются `REFERENCE_TARGET` до отдельного JMH-прогона на чистой topology.
