# Platform Tracing Autoconfigure: after-state

## Назначение

`platform-tracing-spring-boot-autoconfigure` является application-classloader composition plane. Он не создаёт второй OpenTelemetry SDK и не владеет Agent processors. Модуль связывает platform API с Agent runtime через OpenTelemetry Context и private process-local JMX OpenType transport.

## Границы

- Root содержит только `TracingRuntimeAutoConfiguration` и `TracingProperties`.
- `bootstrap` один раз разрешает startup intent в immutable `TracingBootstrapSettings`.
- `agent` наблюдает фактическое состояние Agent/extension и сверяет его с intent.
- `control.policy` содержит Spring-free domain models и три capability-порта: projected read, complete read, apply.
- `control.jmx` владеет application-side private JMX transport; `control.jmx.protocol` Spring-free.
- `status` формирует единый read-only endpoint `tracingStatus` и не получает complete/apply capability.
- Servlet management adapter находится в webmvc-модуле; WebFlux adapter — в webflux-модуле.
- `integrations` группирует AOP, async, Kafka, Observation и error-handling adapters.

## Startup lifecycle

Infrastructure BFPP из `control.authority` строго читает enabled, authority и posture. Он регистрирует один immutable bootstrap snapshot и в том же вызове выполняет guard. Core повторно не читает Environment и не парсит эти значения.

`platform.tracing.enabled` — boolean startup intent. Неизвестное, пустое или несовместимое
значение завершает startup ошибкой. Config Server refresh не меняет bootstrap snapshot.

Runtime composition разводит четыре независимых понятия:

- tracing enabled — startup intent `true|false`;
- effective platform facade state — значение `tracingEnabled`;
- observed Agent state — classloader-neutral readiness descriptor, проверяемый до создания runtime;
- Agent auto-instrumentation state — физически подключённый Agent pipeline, которым Spring facade не управляет.

При `tracingEnabled=true` Agent обязан быть READY. При `tracingEnabled=false` Agent должен
отсутствовать в startup validation window; runtime создаёт NoOp facade с причиной
`platform.tracing.enabled=false`. Свойство является частью immutable bootstrap snapshot и
применяется только после restart; dynamic Agent attach после Spring startup не поддерживается.

## Status contract

`tracingStatus` имеет schema version 1 и секции `agent`, `runtime`, `policy`. Runtime section
публикует фактические `TracingRuntime.state().mode/reason`. Policy projection не содержит route
values и других закрытых данных. Причина отсутствия policy типизирована
`PolicyAvailabilityReason`; endpoint не содержит write operations.

## Проверки

- Spring registry FQCN integrity.
- Exact root/package tree и dependency directions.
- Reviewed public-type allowlist.
- Internal cross-module consumer allowlists.
- ObjectName и OpenType parity между application и Agent classloader planes.
- Двухшаговый tracing configuration inventory gate.
