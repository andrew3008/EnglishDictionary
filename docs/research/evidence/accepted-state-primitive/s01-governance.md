# S-01 governance model (post inventory optimization)

| Field | Value |
|-------|-------|
| As of | 2026-07-31 |
| Related spike | Gate E3 / accepted-state primitive |
| Report | [`s01-inventory-optimization-report.md`](./s01-inventory-optimization-report.md) |

## What S-01 protects

- Exactly one Agent-side accepted-state owner for the eight Category A leaves
- Exactly one supported private complete-snapshot writer path (`applySnapshot`)
- No Spring-owned accepted-state cache / second source of truth
- No resurrection of `RuntimeConfigApplier`
- Config Server and DEV control deliver via approved Spring roles (`WRITER_DELIVERY_TRIGGER`, private snapshot client)
- Hot-path readers use Agent accepted state
- No duplicate Category A leaf owners
- Exact approved **cardinality** for writer-like delivery roles (see below)
- Explicit `NOT_*` absences for unrouted protocol paths

## Identity model

| Identity | Fields | Purpose |
|----------|--------|---------|
| **Semantic** | `owner` + `role` | Architecture capability (what is allowed) |
| **Physical** | `module` + `class` + `method` (line-number suffixes stripped) | Concrete site topology |

A safe rename/move changes physical identity but preserves semantic identity **and** per-`(owner,role)` physical-site cardinality.

A same-role duplicate adds a second physical site for the same semantic identity and is **not** rename-only.

## Approved writer-role cardinality (B1 owners)

Derived from committed `repository-symbol-inventory.json` (not guessed):

| Role | Cardinality | Rationale |
|------|-------------|-----------|
| `WRITER_DELIVERY_TRIGGER` | **exactly 1** | One Config Server refresh trigger per owner |
| `WRITER` | **exactly 2** | Owner field writer + `Platform*Control` facade |
| `WRITER_INDIRECT` | not exact-gated in Layer A today | Multiple Spring indirect paths (JMX client / private applySnapshot / legacy); topology evidence only |

Ordinary `S01SemanticArchitectureFitnessTest` enforces `WRITER` and `WRITER_DELIVERY_TRIGGER` exact counts for `SafeSpanExporter`, `PlatformPropagationGate`, and `PlatformLogControl`.

## Mandatory vs informational

| Layer | Runs under | Fails ordinary build? |
|-------|------------|------------------------|
| **A — Semantic fitness** (`S01SemanticArchitectureFitnessTest` + existing ArchUnit / P4 / P6 / private-control / P56 tests) | `test` / `check` | Yes — role presence, leaf set, **writer cardinality** |
| **B — Exact topology inventory** (`repository-symbol-inventory.json` + `S01OwnerInventoryDriftTest`) | `s01OwnerInventoryEvidenceVerify` | No |
| **C — Drift classifier** (`S01InventoryDriftClassifier`) | Unit fixtures in ordinary `test` | Cardinality-aware: same-role second site → `NEW_WRITER`, not `RENAME_OR_MOVE_ONLY` |

## What blocks ordinary builds

- Wrong Category A leaf set / Category B leakage into the envelope map
- More than one `targetOwner` for Category A leaves
- Missing OWNER/READER/WRITER role graph for B1 owners
- Missing Spring delivery **roles** (`READER_DELIVERY`, `WRITER_DELIVERY_TRIGGER`) in autoconfigure module
- Same-role writer cardinality drift (`WRITER` ≠ 2 or `WRITER_DELIVERY_TRIGGER` ≠ 1 for B1 owners)
- Existing production arch gates (`RuntimeConfigApplier` absence, private MBean writer contract, HELM/DISABLED discovery, etc.)

## What does **not** block ordinary builds

- Renaming a delivery adapter class while keeping the same role and cardinality
- Line-number-only method text changes
- Exact FQCN mismatch vs committed topology snapshot (evidence task only)

## Regenerating / updating inventory

```powershell
.\gradlew.bat s01OwnerInventoryEvidenceVerify --no-daemon
.\gradlew.bat :platform-tracing-bench:test --tests "*S01Semantic*" --no-daemon
```

## Drift classification

| Kind | Meaning |
|------|---------|
| `RENAME_OR_MOVE_ONLY` | Same semantic keys **and** same per-role counts; physical keys changed |
| `NEW_WRITER` | Writer-role semantic count increased (includes same-role second physical site) |
| `NEW_ALLOWED_READER` | Reader-role count increased with a new role |
| `REMOVED_READER` | Reader-role count decreased |
| `NEW_OWNER` / `DUPLICATE_OWNER` / `FORBIDDEN_BYPASS` / `UNKNOWN_SEMANTIC_DRIFT` | As named |

## Historical evidence

`decision-summary.json`, `candidate-scorecard.json`, concurrency/JMH proofs remain authoritative for Gate E3 primitive selection.
