# Operator / consumer examples (final names only)

Post-P6 cutover snippets. No dual-path aliases. Formal P5.9 / full-P6 PASS not claimed.

| File | Authority | Purpose |
|------|-----------|---------|
| [helm-bootstrap.env](helm-bootstrap.env) | HELM | Agent bootstrap env; restart to change; Category B `javaagent.*` |
| [config-server-category-a.yaml](config-server-category-a.yaml) | CONFIG_SERVER | Complete eight-leaf Category A → private `applySnapshot` |
| [dev-actuator-local.yaml](dev-actuator-local.yaml) | ACTUATOR + LOCAL | Enable typed leaves only |
| [dev-actuator-typed-leaves.sh](dev-actuator-typed-leaves.sh) | ACTUATOR + LOCAL | Eight `/tracingControl/...` commands |

Runbooks: [../runbook/runtime-policy-control.md](../runbook/runtime-policy-control.md), [../runbook/jmx-consumer-migration.md](../runbook/jmx-consumer-migration.md).

Banned legacy names: `../research/evidence/p6-cutover/legacy-names-v1.json`.
