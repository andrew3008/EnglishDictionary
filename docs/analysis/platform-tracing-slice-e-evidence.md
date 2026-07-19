# Platform Tracing Slice E Evidence

> Дата: 2026-07-19  
> Ветка: `feature/runtime-control-hardening`  
> Статус: `IN_PROGRESS / CLARIFICATION REQUIRED`

## 1. Подтверждённый residual defect

Production `SdkModeResolver` не реализовывал утверждённую в Spike A mismatch-матрицу:

- functional `GlobalOpenTelemetry` без agent marker ошибочно классифицировался как `AGENT`;
- agent marker вместе с пользовательским `OpenTelemetry` bean не отклонялся;
- explicit `AGENT`, `STARTER` и `EXTERNAL` не проверялись на совместимость со средой.

Дефект исправлен без изменения `TracingRuntime`, control protocol или composition planes.
Agent mode теперь определяется только доказанным runtime marker. Неоднозначные и
несовместимые конфигурации завершают Spring startup диагностируемой ошибкой.

## 2. Verification Gates

| Gate | Результат |
|---|---|
| Spike A resolver matrix | PASS |
| Spring fail-fast: `AGENT` без marker | PASS |
| Spring fail-fast: `EXTERNAL` без runtime | PASS |
| Spring fail-fast: `STARTER` + external bean | PASS |
| Spring context matrix: external/disabled/custom/metered | PASS |
| `TracingImplementationArchTest` | PASS |
| Control `GoldenWireContractTest` | PASS |
| Runtime policy control fail-closed tests | PASS |
| `pr4ArchitectureFitnessVerify` | PASS |
| `pr1ModuleTaxonomyVerify` | PASS |
| Real-agent `ClassLoaderVisibilityE2ETest` | PASS, `tests=1`, `skipped=0`, `failures=0`, `errors=0` |

Real-agent команда:

```powershell
$env:DOCKER_HOST = "tcp://192.168.100.70:2375"
.\gradlew.bat :platform-tracing-e2e-tests:test -PrunE2e `
  --tests "*ClassLoaderVisibilityE2ETest" --rerun-tasks --no-daemon
```

## 3. Deployment Matrix Status

| Режим | Evidence | Статус |
|---|---|---|
| Spring без Agent | external SDK bean и mode ownership проверены; starter-owned SDK bootstrap отсутствует | PARTIAL |
| Spring + Agent | marker, разные class identities, isolation и current OTel context проверены packaged E2E | PASS для classloader/context gate |
| Direct SDK | `OtelTracingRuntimeFactory` и direct facade integration tests | PASS |
| Agent-only | packaged Agent/extension запускается без app-side facade/reader injection | PASS |
| Disabled без Agent | Spring context возвращает no-op facade | PASS |
| Disabled facade + Agent | resolver разрешает `DISABLED` при marker; packaged combined Spring proof отсутствует | PARTIAL |

## 4. Blocking Clarification

Authoritative plan §7.1 требует для `Spring без Agent` SDK bootstrap в autoconfigure.
Действующий `ADR-sdk-mode-detection.md` и production dependency model требуют обратное:
starter является agent-first consumer и никогда не создаёт `SdkTracerProvider`.

До решения нельзя безопасно добавить «минимальный SDK»: необходимо определить ownership
экспортера, resource/propagator/processor composition, shutdown lifecycle, global registration
и конфигурационный source of truth. Создание SDK без exporter формально дало бы valid spans,
но молча теряло бы telemetry и потому неприемлемо для production.

Slice E остаётся `IN_PROGRESS`. Допустимые решения:

1. утвердить starter-owned SDK bootstrap и exact lifecycle/configuration contract, supersede ADR;
2. сохранить agent-first ADR и скорректировать §7.1: Spring без Agent требует external
   `OpenTelemetry` runtime, а отсутствие runtime является fail-fast либо явно degraded mode.

Slice G по-прежнему отдельно заблокирован: `CP-2 = CLARIFICATION REQUIRED`.
