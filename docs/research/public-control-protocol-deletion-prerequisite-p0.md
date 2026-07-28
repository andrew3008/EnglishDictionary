# P0 Public Control Protocol Deletion Prerequisite

> Дата: 2026-07-28
> Ветка: `refactor/config-accepted-state-p1`
> Базовый commit: `8f690a7`
> Статус: `PREREQUISITE_CLOSED`

## 1. Scope

Закрытие todo `p0-public-control-deletion-prerequisite` плана immutable
config / accepted-state:

```text
inventory подтверждает zero external consumer public control protocol types
или committee-approved breaking deletion
```

Scope: семь public types в
`space.br1440.platform.tracing.api.control.protocol` внутри published
`platform-tracing-api`. Package-private decoder/schema types учтены как часть
того же package deletion target, но не как отдельный application-facing surface.

Не входят: `api.propagation.control.*`, JMX OpenMBean members (покрыты E1/E7).

## 2. Inventory result

| Question | Result |
|---|---|
| External / application-developer FQCN consumers in-repo | **0** |
| Samples / README / SUPPORTED / MIGRATION FQCN hits | **0** |
| Legacy `api.control.wire` production types | **0** (removed; historical docs only) |
| Product-module production consumers | **Present** — `platform-tracing-otel`, `platform-tracing-otel-javaagent-extension` |
| Spring autoconfigure production imports | **0** |
| Out-of-repo Nexus consumers | **Not provable** by repository scan |

Product-module consumers are **internal** to the Platform Tracing solution and
must be migrated before P6 DELETE. They do not falsify the zero-external-consumer
prong of the OR-gate.

## 3. OR-gate evaluation

1. **Zero external consumers** — confirmed by in-repo inventory evidence.
2. **Committee-approved breaking deletion** — authorized only as part of the
   already-approved P6 coherent cutover:
   - `implementationGo = GRANTED`
   - `legacyDeletionGo = BLOCKED_UNTIL_P6_COHERENT_CUTOVER`
   - plan target: delete public control protocol types after replacement

## 4. Verdict

```text
PREREQUISITE_CLOSED
AUTHORIZED_NOW = false
AUTHORIZED_AT = P6_COHERENT_CUTOVER
```

P0 inventory prerequisite is closed. Immediate deletion of public control
protocol types remains forbidden. P6 may delete them only after product-module
migration and coherent writer cutover.

## 5. Evidence

| Artifact | Path |
|---|---|
| Decision summary | [`evidence/public-control-protocol-consumers/decision-summary.json`](evidence/public-control-protocol-consumers/decision-summary.json) |
| Consumer inventory | [`evidence/public-control-protocol-consumers/consumer-inventory.json`](evidence/public-control-protocol-consumers/consumer-inventory.json) |
| Committee approval | [`evidence/implementation-readiness/committee-approval.json`](evidence/implementation-readiness/committee-approval.json) |
| Public contract inventory entry | `docs/contracts/public-contract-inventory.yaml` id `tracing-control-protocol` |

## 6. Follow-ups before P6 DELETE

- Migrate otel + javaagent-extension production readers/writers off the current
  public protocol types (or replace the surface as planned).
- Rewrite public-contract inventory status/disposition at P6/P7.
- Update SUPPORTED / MIGRATION / CHANGELOG in P7.
- Run zero-reference gates after deletion.
