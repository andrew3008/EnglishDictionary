# P2 Private Control Boundary

> Дата: 2026-07-28
> Ветка: `refactor/config-accepted-state-p1`
> Базовый commit: `d6e344d`
> Статус: `IMPLEMENTATION_COMPLETE / VERIFICATION_GREEN`

## 1. Scope

P2 productionizes S-03 private process-local JMX mechanism without switching the
supported production writer. Existing platform MBeans, legacy mutations and runtime
readers remain unchanged until the coherent cutover phases.

## 2. Implemented

- Agent-side factory-retained private `MBeanServer` lifecycle owner;
- exact six-attribute marker and exactly-one application-side locator;
- strict independent Agent/App Open-Type codecs for the approved v1 complete snapshot;
- one private `applySnapshot(CompositeData) -> CompositeData` operation;
- direct delegation to the single P1 `AcceptedPolicyStateStore`;
- exact type, required-field, enum, UUID, digest, ratio, route and payload-bound checks;
- `APPLIED`, `UNCHANGED`, `REJECTED`, `STALE`, `FAILED` response encoding;
- E7-approved eight-attribute read-only Control projection candidate for P6;
- updated ArchUnit ownership rule with exactly two server-scoped registrars:
  platform batch registrar and private-server owner.

All new implementation types are package-private. No shared DTO, dependency, module,
public API or publication metadata was added.

## 3. Preserved boundaries

- private owner and Spring locator are not wired into supported bootstrap;
- current `PlatformTracingJmxRegistrar` and JMX baseline are unchanged;
- `applySnapshot` is absent from the platform `MBeanServer`;
- legacy `applyPolicy` and domain mutations are not deleted in P2;
- read-only target projection is prepared but not registered before P6;
- P3 reader migration may start; P4 remains responsible for delivery adapters.

## 4. Verification

| Command | Result |
|---|---|
| Agent and Spring compile tasks | PASS |
| Agent private boundary focused tests | PASS |
| Spring independent codec focused test | PASS |
| Spring production locator zero/one/two-candidate tests | PASS |
| Agent module test suite | PASS after server-scoped assertion correction |
| Spring autoconfigure module test suite | PASS |
| `privateMBeanServerVerify --rerun-tasks` | PASS; M1-M12 and N1-N7 |
| `pr4ArchitectureFitnessVerify pr1ModuleTaxonomyVerify` | PASS |
| `:platform-tracing-e2e-tests:test -PrunE2e` | EXECUTED_PASSED; 28 classes, 65 tests, 0 failures/errors/skipped |
| `build` | PASS |
| `git diff --check` | PASS |

The full opt-in e2e suite ran against Docker 28.0.4 at
`tcp://192.168.100.70:2375` in approximately 9 minutes 20 seconds and passed.
`PrivateMBeanServerIsolationE2ETest` is intentionally excluded from the aggregate
e2e task by Gradle configuration; its contract is verified separately by the green
`privateMBeanServerVerify` task. The earlier normal `build` invocation skipped the
opt-in e2e task as designed.

## 5. Exit

P2 is implementation-complete and verification-green in test/spike posture. P3 is
unblocked. Supported production writer selection is unchanged, so target and legacy
writers are not simultaneously reachable through the supported runtime posture.

Legacy deletion remains blocked until P6. Production rollout remains forbidden.
