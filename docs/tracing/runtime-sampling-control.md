# Runtime policy control (superseded operational doc)

> **Filename retained** for link stability (`runtime-sampling-control.md`).
>
> **Canonical operator runbook (post-P6 cutover):**
> [../runbook/runtime-policy-control.md](../runbook/runtime-policy-control.md)
>
> **JMX consumer migration:**
> [../runbook/jmx-consumer-migration.md](../runbook/jmx-consumer-migration.md)
>
> **Examples (final names only):**
> [../examples/README.md](../examples/README.md)

## Supersession notice

Prior revisions of this file documented deleted mutation surfaces:

- platform-domain JMX setters / `update*Policy` mutators
- Actuator `POST /actuator/tracing/{property}/{value}` and `platform.tracing.actuator.mutation-enabled`
- `RuntimeConfigApplier` / RefreshScope reconcile
- public control-protocol `applyPolicy`

Those writers are **removed**. Do not follow historical PR-6/7/8/9J mutation recipes in older commits of this path.

## Current contract (summary)

| Item | Current |
|------|---------|
| Sole mutation | Private `applySnapshot` (+ `readAcceptedComplete`) |
| Platform JMX | Read-only (0 platform mutations) |
| Production / shared | `HELM` (restart) or `CONFIG_SERVER` (complete Category A manifest) |
| Local/dev writes | `ACTUATOR` + `platform.environment=LOCAL` → eight `/tracingControl/...` typed leaves |
| Category B names | `platform.tracing.javaagent.scrubbing.*`, `.sampling.drop-paths` / `.force-record-values`, `.propagation.baggage.*` |
| Aliases | None |

Architecture background (may still describe pre-cutover holders in older sections):
[runtime-policy-control-architecture.md](runtime-policy-control-architecture.md). Prefer the runbook above for operations.

Formal **P5.9 PASS** / full-**P6 PASS** are not claimed.
