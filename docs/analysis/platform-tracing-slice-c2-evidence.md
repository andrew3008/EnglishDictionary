# Platform Tracing Slice C2 Evidence

> Дата: 2026-07-19  
> Ветка: `feature/runtime-control-hardening`  
> Базовый HEAD перед изменениями: `b0a5272`  
> Решение CP-C2: `APPROVED AS PROPOSED`

## 1. Результат

Slice C2 реализован в утверждённой форме. `platform-tracing-api` больше не содержит OTel-типы в main metadata и ABI. WebMVC, WebFlux и Kafka outbound adapters зависят от OTel-free `PlatformOutboundPropagation` и записывают типизированные slots `OutboundPropagationHeaders` в собственные carriers.

OTel-зависимые `OtelTraceparentReader`, `PlatformTraceContextKeys` и `TraceControlHeaderInjector` перенесены в implementation-модуль `platform-tracing-core`. Agent-side propagator продолжает использовать явный `Context` через внутренний контракт; application/agent classloader boundary не переносит OTel objects.

## 2. Intentional ABI Delta

Удалено из public API:

- `api.propagation.OtelTraceparentReader`;
- `api.propagation.control.PlatformTraceContextKeys`;
- `api.propagation.control.TraceControlHeaderInjector`.

Добавлено в public API:

- `PlatformOutboundPropagation`;
- `OutboundPropagationHeaders`;
- `OutboundPropagationHeaders.Header`.

`PublicSurfaceAllowlistTest` и `AbiSnapshotTest` обновлены только на эту утверждённую delta и проходят.

## 3. Verification

| Проверка | Результат |
|---|---|
| Compile production и test source sets затронутых модулей | PASS |
| API/core/Spring/WebMVC/WebFlux/extension/test module tests | PASS |
| `pr4ArchitectureFitnessVerify pr1ModuleTaxonomyVerify` | PASS |
| `build --no-daemon` | PASS |
| API main imports `io.opentelemetry.*` | 0 |
| Outbound WebMVC/WebFlux/Kafka imports OTel или moved internal types | 0 |
| Ссылки на удалённые API FQN и setter-классы | 0 |
| `git diff --check` | PASS |

Полный локальный набор:

```powershell
.\gradlew.bat :platform-tracing-api:test :platform-tracing-core:test `
  :platform-tracing-spring-boot-autoconfigure:test `
  :platform-tracing-autoconfigure-webmvc:test `
  :platform-tracing-autoconfigure-webflux:test `
  :platform-tracing-otel-extension:test :platform-tracing-test:test `
  pr4ArchitectureFitnessVerify pr1ModuleTaxonomyVerify --no-daemon
.\gradlew.bat build --no-daemon
```

Packaged-agent/classloader baseline выполнен через Gentoo Docker `tcp://192.168.100.70:2375`:

```powershell
$env:DOCKER_HOST = "tcp://192.168.100.70:2375"
.\gradlew.bat :platform-tracing-e2e-tests:test -PrunE2e `
  --tests "*ClassLoaderVisibilityE2ETest" `
  --tests "*MapWireRoundTripE2ETest" `
  --rerun-tasks --no-daemon
```

Оба теста реально исполнены: `tests=1`, `skipped=0`, `failures=0`, `errors=0` для каждого.

## 4. Остаточные Gates

CP-2 имеет статус `CLARIFICATION REQUIRED`: конкретный вариант Sampling SPI не выбран. Slice G остаётся `NO-GO`; в рамках C2 его production-код не изменялся.

Следующий разрешённый этап по dependency graph: Slice C3. Его отдельный gate обязан проверить опубликованные POM/Gradle module metadata и внешний consumer compile/runtime, а не только project dependency внутри monorepo.
