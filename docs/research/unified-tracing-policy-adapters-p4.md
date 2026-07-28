# P4: Unified Tracing Policy Adapters

## Статус

```text
P4 CORRECTIVE IMPLEMENTATION READY FOR RE-REVIEW
P4 COMPLETION NOT YET GRANTED
P5 BLOCKED PENDING P4 ARCHITECTURE ACCEPTANCE
P4 ADAPTERS UNREACHABLE FROM SUPPORTED POSTURE UNTIL P6
LEGACY WRITERS RETAINED UNTIL P6
PRODUCTION ROLLOUT FORBIDDEN
```

## Архитектура

Config Server и Spring Boot Actuator формируют один полный immutable snapshot из восьми
Category A leaves и передают его одному application-side writer client. Read capability отделена
от write capability:

```text
Config Server provenance + complete manifest
                                 \
                                  -> TracingPolicySnapshotClient
                                 /   -> private applySnapshot
Actuator read-copy-one-leaf-change    -> Agent accepted-state owner

HELM / CONFIG_SERVER / ACTUATOR
    -> AcceptedTracingPolicyReader
    -> approved Agent read-only projections
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
неизменным. Неуспешная initial delivery завершает startup ошибкой. Runtime refresh сохраняет LKG
и не завершает процесс.

## Actuator

`SpringActuatorTracingPolicyAdapter` выполняет:

```text
read accepted snapshot
-> copy complete snapshot
-> change exactly one typed leaf
-> normalize/validate complete candidate
-> common snapshot client
```

Один web-only management namespace `tracingControl` реализован framework-specific
`@RestController` внутри management context. Контракт содержит ровно восемь статически
типизированных HTTP-маршрутов:

```text
POST /actuator/tracingControl/sampling/enabled
POST /actuator/tracingControl/sampling/ratio
PUT  /actuator/tracingControl/sampling/route-ratios
POST /actuator/tracingControl/validation/enabled
POST /actuator/tracingControl/validation/strict
POST /actuator/tracingControl/exporter/enabled
POST /actuator/tracingControl/propagation/trace-control-headers
POST /actuator/tracingControl/diagnostics/log-level
```

Каждый маршрут принимает собственный typed request DTO со строгим JSON binding. Generic
property mutation, operation selector, `Object value`, runtime command dispatcher, raw snapshot
HTTP operation, patch и `RuntimeConfigApplier` в новом пути отсутствуют. `STALE` не повторяется.

Поверхность создаётся только при `AGENT + ACTUATOR + LOCAL`. Явный выбор authority и
environment является opt-in activation contract; до P6 management configuration отсутствует
в production imports и недостижима из поддерживаемой конфигурации. Для
`SHARED` и `PRODUCTION` guard завершает startup ошибкой. Для `HELM`, `CONFIG_SERVER` и
`DISABLED` control endpoint и adapter отсутствуют.

`defaultAccess` к controller неприменим: это не Actuator endpoint abstraction. До P6
fail-closed baseline обеспечен физическим отсутствием production management import. Решение
P6 о включении поверхности обязано отдельно зафиксировать access/exposure policy и не может
полагаться только на сетевую доступность management port.

## Read model

Secret-safe configuration projection встроена в существующий canonical endpoint `tracing`.
Она получает только `AcceptedTracingPolicyReader`, не раскрывает write capability и не
публикует route keys или values. Источником policy truth являются утверждённые Agent
read-only projections. Проекция доступна в Agent mode для `HELM`, `CONFIG_SERVER` и
`ACTUATOR`; технологическая authority изменяет delivery semantics, но не observability.

Для `HELM` и `ACTUATOR` application readiness строится из фактического accepted snapshot и
его `acceptedSequence`. Для `CONFIG_SERVER` readiness остаётся `PENDING` до initial delivery;
неуспешная initial delivery является startup-fatal.

P4 context tests подтверждают eager read относительно application-side readiness composition.
Packaged proof порядка регистрации Agent read-only projections остаётся обязательным условием
P6 production wiring; P4 не активирует этот путь и не делает предположений о нём.

## Failure semantics

- отсутствие совместимого private control plane и race с удалением writer возвращают
  типизированный `FAILED` с безопасным message code;
- Agent-side typed `FAILED` сохраняется как delivery result;
- duplicate marker, несовместимый marker/protocol, malformed `CompositeData`, неизвестный
  outcome и codec invariant failure остаются исключениями и завершаются fail-fast;
- policy values и secrets в failure diagnostics не публикуются.

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
- фактический management HTTP binding восьми маршрутов в Servlet и WebFlux;
- HTTP-отклонение unknown fields, string coercion, null и oversized route payload;
- фактическое отсутствие write route для HELM и CONFIG_SERVER;
- startup fail-fast для ACTUATOR в SHARED и PRODUCTION;
- отсутствие endpoint/JMX-аннотаций у web-only controller;
- HELM/CONFIG_SERVER/ACTUATOR read-only projection из accepted Agent truth;
- startup-fatal initial Config Server delivery failure;
- actual accepted sequence для HELM/ACTUATOR readiness;
- fail-fast для неизвестного `platform.environment`;
- route-ratio bounds и replace-all semantics;
- отсутствие P4 adapters в supported auto-configuration;
- отсутствие Spring profile coupling и `Dev`-prefixed production types;
- frozen private JMX/Open-Type contracts.
