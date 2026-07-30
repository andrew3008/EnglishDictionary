# Runbook: runtime policy control

| Field | Value |
|-------|-------|
| Audience | SRE / on-call / platform operators |
| Scope | Authority postures, private `applySnapshot`, readiness, LKG, STALE, drift, rollback |
| Related | [SUPPORTED.md](../SUPPORTED.md) § Configuration authority; [MIGRATION.md](../MIGRATION.md) § P6 cutover; [jmx-consumer-migration.md](jmx-consumer-migration.md) |
| Status | Operator guidance for post-P6 cutover code. Formal **P5.9 PASS** / full-**P6 PASS** are **not** claimed. |

**No dual-path / aliases.** Do not use deleted writers or banned legacy property names.

Deleted (do not call): `RuntimeConfigApplier`, Actuator `@WriteOperation` on `/actuator/tracing`, public control protocol `applyPolicy`, platform-domain JMX setters. Sole mutation: private `applySnapshot`.

---

## 0. Quick authority matrix

| Authority (`platform.tracing.configuration.authority`) | Spring writer | Mutation path |
|---|---|---|
| **HELM** | None | Agent bootstrap only (env/`-D`); restart to change |
| **CONFIG_SERVER** | Locator / codec / snapshot client / coalescer | Complete Category A manifest → private `applySnapshot` |
| **ACTUATOR** + `platform.environment=LOCAL` | Typed management controller | Eight typed leaves → private `applySnapshot` (Agent source `DEV_ACTUATOR`) |
| **DISABLED** (`mode=DISABLED` or `configuration.enabled=false`) | No private-control discovery | Incompatible with `CONFIG_SERVER` / `ACTUATOR` (fail-fast) |

Exactly one authority at startup. Fallback and dual writers are forbidden.

---

## 1. HELM posture (Agent bootstrap only; no Spring writer)

**When:** Production / shared startup-only policy.

**Do:**

1. Set `platform.tracing.configuration.authority=HELM`.
2. Provide Category A and Category B as Agent startup env or `-D` system properties (see [examples/helm-bootstrap.env](../examples/helm-bootstrap.env)).
3. Roll pods / restart JVM to change policy. Accepted state is process-local and rebuilt at bootstrap.

**Do not:**

- Expect Spring Config Server adapter or Actuator typed writes.
- Expect `ConfigServerTracingPolicyAutoConfiguration` or ACTUATOR private-control beans (HELM never loads them).
- Call platform JMX mutators (there are none post-P6.6).

**Verify:**

- App starts without Config Server / Actuator private-control readiness probes.
- `GET /actuator/tracing` (if exposed) is diagnostics only.
- Platform Control projection is read-only (`StructuralDrift` / `RestartRequired` attributes exist; see §7).

---

## 2. CONFIG_SERVER posture (complete Category A → private `applySnapshot`)

**When:** Runtime Category A delivery from Config Server refresh.

**Do:**

1. Set `platform.tracing.configuration.authority=CONFIG_SERVER`.
2. Deliver a **complete** eight-leaf Category A manifest. Seven scalar keys are fail-closed if missing; the eighth leaf is `sampling.route-ratios` (may be empty):

```text
platform.tracing.policy.sampling.enabled
platform.tracing.policy.sampling.ratio
platform.tracing.policy.sampling.route-ratios.<route>   # optional entries; leaf may be empty
platform.tracing.policy.validation.enabled
platform.tracing.policy.validation.strict
platform.tracing.policy.exporter.enabled
platform.tracing.policy.propagation.trace-control-headers.enabled
platform.tracing.policy.diagnostics.log-level
```

3. Keys must come from recognised Config Server property sources (local overrides rejected).
4. Spring builds the complete snapshot and calls private `applySnapshot` only (coalesced refresh worker).

**Do not:**

- Send partial manifests or patch individual leaves over HTTP.
- Use Actuator typed leaves under CONFIG_SERVER (write surface not registered).
- Expect silent remap of legacy property names.

**Verify:**

- Startup readiness: private writer discoverable and `readAcceptedComplete` non-empty, then initial deliver.
- Refresh logs: `outcome=APPLIED|UNCHANGED|STALE|REJECTED|FAILED`.

Example: [examples/config-server-category-a.yaml](../examples/config-server-category-a.yaml).

---

## 3. DEV_ACTUATOR + LOCAL (typed leaf commands only)

**When:** Local/dev only. Spring authority label is `ACTUATOR`; Agent apply source is `DEV_ACTUATOR`.

**Gates (both required):**

```properties
platform.tracing.configuration.authority=ACTUATOR
platform.environment=LOCAL
```

`ACTUATOR` + `SHARED` / `PRODUCTION` → fail-fast. Tracing disabled + `ACTUATOR` → fail-fast.

**Typed leaves only** (management base-path + `/tracingControl/...`):

| Method | Path | Leaf |
|--------|------|------|
| POST | `/tracingControl/sampling/enabled` | `sampling.enabled` |
| POST | `/tracingControl/sampling/ratio` | `sampling.ratio` |
| PUT | `/tracingControl/sampling/route-ratios` | `sampling.route-ratios` |
| POST | `/tracingControl/validation/enabled` | `validation.enabled` |
| POST | `/tracingControl/validation/strict` | `validation.strict` |
| POST | `/tracingControl/exporter/enabled` | `exporter.enabled` |
| POST | `/tracingControl/propagation/trace-control-headers-enabled` | `propagation.trace-control-headers.enabled` |
| POST | `/tracingControl/diagnostics/log-level` | `diagnostics.log-level` |

HTTP outcomes: `200` APPLIED/UNCHANGED, `422` REJECTED, `409` STALE, `503` FAILED.

**Do not:**

- Use `POST /actuator/tracing/{property}/{value}` (deleted).
- Use `platform.tracing.actuator.mutation-enabled` (deleted / banned).
- Call platform-domain JMX setters.

Example: [examples/dev-actuator-local.yaml](../examples/dev-actuator-local.yaml), [examples/dev-actuator-typed-leaves.sh](../examples/dev-actuator-typed-leaves.sh).

---

## 4. Initial failure / readiness blocked

| Symptom | Likely cause | Action |
|---------|--------------|--------|
| Startup fail: missing / invalid `platform.tracing.configuration.authority` | Authority required; no fallback | Set exactly one of `HELM`, `CONFIG_SERVER`, `ACTUATOR` |
| Startup fail: ACTUATOR + SHARED/PRODUCTION | Posture gate | Use HELM or CONFIG_SERVER in shared/prod; LOCAL only for ACTUATOR |
| Startup fail: disabled + CONFIG_SERVER/ACTUATOR | Mode/enabled matrix | Use HELM for disabled tracing, or re-enable tracing |
| `[P6 CONFIG_SERVER readiness] private writer unreachable` / empty `readAcceptedComplete` | Agent private control plane not ready / version mismatch | Confirm Controlled Agent + extension; align app/agent versions; check private MBeanServer attach |
| `[P4 readiness probe]` ACTUATOR empty accepted policy | Same private-plane readiness | Same as above |
| Initial CONFIG_SERVER deliver: missing required leaf | Incomplete manifest | Add all required `platform.tracing.policy.*` scalar keys (complete eight-leaf Category A) |
| Local override rejected for policy key | Key from command-line / local file | Deliver leaf from Config Server source only |

Liveness must not depend on collector reachability. Tracing-configuration readiness is separate from business liveness.

---

## 5. Runtime outage + LKG retention

On `REJECTED` or `FAILED` apply:

- Agent retains last-known-good (LKG) accepted policy.
- Version / accepted sequence do not advance for rejected/failed applies.
- Telemetry continues on LKG.

**Operator steps:**

1. Read Control projection (platform MBean, read-only): `LastApplyOutcome`, `LastFailureCode`, `AcceptedSequence` / runtime instance fields as exposed.
2. Do **not** force mutation via platform JMX (unsupported).
3. Fix manifest / leaf payload; re-deliver via the selected authority.
4. If private plane is down: restore Agent/extension; until then LKG (or bootstrap HELM values after restart) applies.

CONFIG_SERVER logs explicitly: `REJECTED` / `FAILED` → `LKG retained`.

---

## 6. STALE apply response + bounded retry

**Meaning:** Caller’s `expectedSequence` / runtime instance did not match Agent witnessed state (concurrent apply or Agent restart between read and apply).

| Path | Retry behavior |
|------|----------------|
| **CONFIG_SERVER** coalescer | On first `STALE`: refresh sequence from `readAcceptedComplete`, **one** bounded retry. Second `STALE` → warn, **LKG retained**. |
| **ACTUATOR** typed leaves | **No** automatic retry. HTTP `409` + `"outcome":"STALE"`. Caller may re-read and retry once if appropriate. |

**Do not** invent unbounded retry loops. After bounded failure, inspect sequence / instance id and re-issue a complete apply.

---

## 7. Structural drift / restart required

**Facts in code:**

- Private `applySnapshot` response includes `restartRequired` and `restartRequiredKeys`.
- Platform Control projection exposes read-only `StructuralDrift` and `RestartRequired`.
- Current projection wiring supplies `false` for both attributes until a non-stub detector is connected; still treat `restartRequired=true` on an apply response as authoritative.

**Operator action when restart is required:**

1. Treat Category B / topology / Agent bootstrap keys as **restart-only** (not live Category A leaves). Examples of final Category B names:
   - `platform.tracing.javaagent.scrubbing.enabled` / `.built-in-rules`
   - `platform.tracing.javaagent.sampling.drop-paths` / `.force-record-values`
   - `platform.tracing.javaagent.propagation.baggage.*`
2. Update deployment env/`-D` / Helm values to final names.
3. Roll the pod / restart the JVM (whole process). Do not expect live `applySnapshot` to absorb Category B changes.
4. After restart, confirm bootstrap and (if CONFIG_SERVER) readiness + initial deliver.

---

## 8. Whole-train rollback

After the P6 cutover, the rollback unit is the **entire unreleased release train**.

**Forbidden:**

- Rolling back property names without writers (or vice versa)
- Mixing old platform JMX mutators with new private writer
- Compatibility aliases / dual-read bindings
- Independent rollback of manifests, readers, or Agent extension alone

**Allowed:** Coordinated revert of app starter + Agent extension + Config Server / Helm manifests + operator docs as one train. Accepted policy is process-local and rebuilt from the selected authority after restart.

See [MIGRATION.md](../MIGRATION.md) § Rollback and plan §25.

---

## Diagnostics (read-only)

| Surface | Role |
|---------|------|
| `GET /actuator/tracing` | Diagnostics / desired vs effective hints — **not** a writer |
| Platform domain MBeans + Control projection | Read-only post-P6.6 |
| Private control plane | `applySnapshot` + `readAcceptedComplete` only (not for dashboards) |

Banned legacy config names: `docs/research/evidence/p6-cutover/legacy-names-v1.json`.
