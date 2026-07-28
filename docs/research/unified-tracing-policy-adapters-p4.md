# P4: Unified Tracing Policy Adapters

## Статус

```text
P4 IMPLEMENTATION COMPLETE
P4 ADAPTERS UNREACHABLE FROM SUPPORTED POSTURE UNTIL P6
LEGACY WRITERS RETAINED UNTIL P6
PRODUCTION ROLLOUT FORBIDDEN
```

## Архитектура

Config Server и Spring Boot Actuator формируют один полный immutable snapshot из восьми
Category A leaves и передают его одному application-side клиенту:

```text
Config Server provenance + complete manifest
                                 \
                                  -> TracingPolicySnapshotClient
                                 /   -> private applySnapshot
Actuator read-copy-one-leaf-change    -> Agent accepted-state owner
```

HELM остаётся startup-only authority и использует существующий Agent bootstrap. Spring не
создаёт второго accepted-state owner и не считает bound properties effective truth.

Application authority содержит только `HELM`, `CONFIG_SERVER`, `ACTUATOR`.
`platform.environment` является отдельной типизированной осью со значениями `LOCAL`,
`SHARED`, `PRODUCTION`, `UNKNOWN`. Профили Spring, hostname и иные эвристики не участвуют
в решении.

## Config Server

`ConfigServerTracingPolicyAdapter` выполняет:

- fail-closed проверку non-optional `spring.config.import=configserver:`;
- per-key provenance verification;
- построение complete manifest без default/LKG fallback;
- обработку `EnvironmentChangeEvent` только как trigger;
- generation/dirty coalescing одним worker;
- один полный rebuild/retry только после `STALE`;
- bounded secret-safe delivery diagnostics;
- отдельную application configuration readiness.

Missing или non-authoritative leaf возвращает `REJECTED`; Agent accepted state остаётся
неизменным.

## Actuator

`SpringActuatorTracingPolicyAdapter` выполняет:

```text
read accepted snapshot
-> copy complete snapshot
-> change exactly one typed leaf
-> normalize/validate complete candidate
-> common snapshot client
```

Один web-only Actuator namespace `tracingControl` содержит восемь typed POST operations.
Generic property mutation, raw snapshot HTTP operation, patch и `RuntimeConfigApplier`
в новом пути отсутствуют. `STALE` не повторяется.

Поверхность создаётся только при `AGENT + ACTUATOR + LOCAL + control-enabled`. Для
`SHARED` и `PRODUCTION` guard завершает startup ошибкой. Для `HELM`, `CONFIG_SERVER` и
`DISABLED` control endpoint и adapter отсутствуют.

## Read model

Secret-safe configuration projection встроена в существующий canonical endpoint `tracing`.
Она получает только `AcceptedTracingPolicyReader`, не раскрывает write capability и не
публикует route keys или values. Источником policy truth являются утверждённые Agent
read-only projections.

## Frozen contracts и C-05

- private JMX schema не расширена;
- `readAcceptedSnapshot` и `AcceptedSnapshot.v1` отсутствуют;
- private boundary содержит единственную mutation operation `applySnapshot`;
- Agent accepted-state owner и `acceptedSequence` owner не изменены;
- P4 auto-configuration намеренно отсутствует в `AutoConfiguration.imports`;
- supported writer switch и legacy deletion остаются P6.

Frozen P0 wire token `DEV_ACTUATOR` преобразуется только внутри private JMX infrastructure
client; application authority и конфигурационный контракт используют `ACTUATOR`.

## Проверка

Покрыты:

- exact authority enum и fail-closed posture matrix;
- complete восьмилистный manifest;
- Config Server provenance, coalescing и bounded `STALE` retry;
- отказ от `optional:configserver:` для authoritative CONFIG_SERVER posture;
- восемь one-leaf Actuator operations и complete snapshot delivery;
- route-ratio bounds и replace-all semantics;
- отсутствие P4 adapters в supported auto-configuration;
- отсутствие Spring profile coupling и `Dev`-prefixed production types;
- frozen private JMX/Open-Type contracts.

Web-only controller endpoint выбран намеренно: стандартный portable Actuator endpoint не
может представить восемь фиксированных typed sub-routes без generic selector dispatch.
