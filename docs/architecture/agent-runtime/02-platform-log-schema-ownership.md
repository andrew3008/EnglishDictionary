# Platform log schema ownership

Дата: 2026-08-17.

## Owner

Канонический файл живёт в
`spring-boot-starter-platform-logging/src/main/resources/space/br1440/platform/logging/`
и входит в published starter JAR:

- `platform-log-event-v1.schema.json`

Корень открыт (`additionalProperties: true`). `fields` типизирован
(`additionalProperties: { "type": "string", "maxLength": 512 }`).
`logger.minLength` = 1, `message.maxLength` = 512, `error_stacktrace.maxLength` = 4119.
Required: `timestamp`, `level`, `logger`, `message`, `event_code`.

`platform-log-fields-v1.yaml` и `platform-log-security-policy-v1.yaml` удалены:
allowlist не enforced в runtime, лимиты message/value принадлежат `AgentLogEvent`,
имена и `MAX_FIELDS` — `AgentLogFieldNamePolicy`. Grammar не публикуется как
`propertyNames` / `maxProperties` owner schema.

Owner gate: `verifyPlatformLogSchema`. Named outgoing configuration
`platformLogSchemaElements` публикует нормализованный directory artifact
с `builtBy(verifyPlatformLogSchema)`. Это закрытый intra-build contract:
без attributes, capability и variant matching. Внешним consumers имя
configuration не обещается.

## Agent

Agent потребляет schema только на build/test через resolvable
`platformLogSchema` → `configuration: 'platformLogSchemaElements'`,
`transitive = false`. Runtime classpath Agent не содержит logging starter.

`platformLogSchema` — намеренное исключение ролевой модели на одну
intra-build зависимость: declaration и resolution живут в одной
configuration. Это не repository-wide pattern. При второй dependency
configuration разделяется на dependency scope + resolvable view.

Production identity считается один раз задачей
`generateAgentLoggingSchemaIdentity` и даёт два производных представления:

- `META-INF/platform-tracing/agent-logging-schema.properties` в
  `agentExtensionJar`
- manifest fragment, который merge'ится в `META-INF/MANIFEST.MF`

Shallow library JAR identity не содержит. Distribution не дублирует
identity вне embedded extension.

Алгоритм `PLATFORM_LOG_SCHEMA_SET_SHA256_V1`: Groovy-producer считает
production identity, Java-verifier независимо пересчитывает actual schema.
Frozen known-answer в тестах Агента якорит интерпретацию алгоритма
(локальный differential test, не отраслевой Gradle-стандарт).
Set содержит только schema.json.

## Application profile

```text
CURRENT_APPLICATION_PROFILE_CONFORMANCE: NOT_IMPLEMENTED
FOLLOW_UP: APP-LOG-PROFILE-CONFORMANCE-001
```

`logback-spring-defaults.xml` не менялся. Это явный release gap, не
«unified logging completed».
