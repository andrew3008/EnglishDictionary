# Platform log schema ownership

Дата: 2026-08-17.

## Owner

Канонический файл живёт в
`platform-tracing-otel-javaagent-extension/src/main/resources/platform-log-event.schema.json`.

Это build/test source of truth модуля Agent, не published resource
logging starter и не содержимое production `agentExtensionJar`.

Корень открыт (`additionalProperties: true`). `fields` типизирован
(`additionalProperties: { "type": "string", "maxLength": 512 }`).
`logger.minLength` = 1, `message.maxLength` = 512, `error_stacktrace.maxLength` = 4119.
Required: `timestamp`, `level`, `logger`, `message`, `event_code`.
`$id` совпадает с именем файла. `title` остаётся `platform-log-event-v1`.

`platform-log-fields-v1.yaml` и `platform-log-security-policy-v1.yaml` удалены:
allowlist не enforced в runtime, лимиты message/value принадлежат `AgentLogEvent`,
имена и `MAX_FIELDS` — `AgentLogFieldNamePolicy`. Grammar не публикуется как
`propertyNames` / `maxProperties` owner schema.

Owner gate: тесты модуля
`platform-tracing-otel-javaagent-extension`. `preparePlatformLogSchema`
нормализует LF и проверяет allowlist перед
`generateAgentLoggingSchemaIdentity`. Кросс-модульной configuration
`platformLogSchemaElements` больше нет.

## Agent

Runtime classpath Agent не содержит logging starter и не читает schema
в production. Schema нужна только для identity hash и conformance tests.

Production identity считается один раз задачей
`generateAgentLoggingSchemaIdentity` и даёт два производных представления:

- `META-INF/platform-tracing/agent-logging-schema.properties` в
  `agentExtensionJar`
- manifest fragment, который merge'ится в `META-INF/MANIFEST.MF`

Shallow library JAR identity не содержит. Distribution не дублирует
identity вне embedded extension. Оба production JAR исключают
`platform-log-event.schema.json`.

Алгоритм `PLATFORM_LOG_SCHEMA_SET_SHA256_V1`: Groovy-producer считает
production identity, Java-verifier независимо пересчитывает actual schema.
Frozen known-answer в тестах Агента якорит интерпретацию алгоритма
(локальный differential test, не отраслевой Gradle-стандарт).
Set содержит только schema.json.

`platform-log-masking-rules.yaml` остаётся в
`spring-boot-starter-platform-logging` и потребляется Agent только на
build/test через `platformLogMaskingElements`.

## Application profile

```text
CURRENT_APPLICATION_PROFILE_CONFORMANCE: NOT_IMPLEMENTED
FOLLOW_UP: APP-LOG-PROFILE-CONFORMANCE-001
```

`logback-spring-defaults.xml` не менялся. Это явный release gap, не
«unified logging completed».
