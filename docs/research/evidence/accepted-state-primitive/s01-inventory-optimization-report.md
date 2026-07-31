# S-01 Inventory Optimization Report

| Field | Value |
|-------|-------|
| Date | 2026-07-31 |
| HEAD before | `65cf036082e39778ab4e9ffa4369285e8d14072b` |
| Branch | `master` |
| Production classes added | **0** |
| New Gradle modules | **0** |
| New test/build-support classes | **1** (`S01InventoryDriftClassifier`) |
| Governance note | [`s01-governance.md`](./s01-governance.md) |

---

## 1. Executive verdict

**`PASS`**

S-01 architectural intent is preserved as mandatory rename-resistant semantic fitness. Exact FQCN/method topology remains visible as an explicit evidence/release task and no longer blocks ordinary aggregate `test` after a safe delivery-adapter rename.

---

## 2. Repository state

| Item | Value |
|------|-------|
| Working tree before | Clean at `65cf036` |
| After change | Dirty — S-01 split, Gradle wiring, inventory regen, governance docs |
| Runtime / production behavior | Unchanged |
| Docker E2E | NOT_RUN (no production/runtime wiring change) |

---

## 3. Original S-01 responsibilities

`S01OwnerInventoryEvidenceTest` mixed two concerns while running under `:platform-tracing-bench:test` (hence aggregate `test`):

1. **Semantic** — eight Category A leaves, one `targetOwner`, Category B exclusion, B1 owner role graph, migration map completeness.
2. **Structural / rename-sensitive** — `assertSite(..., "LegacyTracingDiagnosticsEndpoint", "tracing()")` and sibling exact FQCN delivery paths.

The second class of assertion is what failed the green refactoring when only a class rename occurred.

Related Gate E3 concurrency/JMH proofs (`S01OneWinnerCommitTest`, etc.) were already separate and remain mandatory under ordinary bench `test`.

---

## 4. Existing assertion classification

| Assertion (former test) | Risk protected | Kind | Rename-sensitive? | Elsewhere? | Disposition |
|-------------------------|----------------|------|-------------------|------------|-------------|
| Exactly 8 Category A leaves | Wrong accepted envelope shape | Semantic | Leaf names are contracts | `S01CategoryALeafContractTest` (production shape) | `KEEP_MANDATORY_SEMANTIC` |
| Single `targetOwner` | Duplicate accepted-state owners | Semantic | No (owner id string) | Agent `AcceptedStateArchitectureTest` (partial) | `KEEP_MANDATORY_SEMANTIC` |
| Category B excluded | Scrubbing/secrets in accepted state | Semantic | No | Leaf contract forbidden fragments | `KEEP_MANDATORY_SEMANTIC` |
| ScrubbingPolicyHolder deletion record | Category B holder resurrection narrative | Evidence + semantic flag | Historical class name | P6.7 deletion docs | `KEEP_MANDATORY_SEMANTIC` |
| Owner migration map exact leaf claims | Duplicate/missing leaf ownership | Semantic | Owner class names in JSON | — | `KEEP_MANDATORY_SEMANTIC` |
| Access-site required fields | Incomplete inventory schema | Structural schema | Field names only | — | `KEEP_MANDATORY_SEMANTIC` (schema) |
| B1 OWNER+READER+WRITER roles | Incomplete access graph | Semantic roles | Owner conceptual ids | — | `KEEP_MANDATORY_SEMANTIC` |
| Exact delivery FQCNs (`LegacyTracing...tracing()`) | Topology snapshot / review | Structural | **Yes** | Not a public contract | `KEEP_INFORMATIONAL_INVENTORY` |
| `NOT_*` absences for B1 | Explicit unrouted protocol | Semantic role | No | — | `KEEP_MANDATORY_SEMANTIC` |
| `RuntimeConfigApplier` absent | Legacy mutation path | Semantic | N/A | `ProductionControlPlaneNotMigratedArchTest`, P6 tests | `REMOVE_DUPLICATE_CHECK` (rely on existing) |
| Private `applySnapshot` writer | Single writer path | Semantic | Operation name is contract | S07 / PrivateControl* tests | `REMOVE_DUPLICATE_CHECK` |
| HELM/DISABLED no Spring writer discovery | Wrong discovery | Semantic | N/A | P56 matrix | `REMOVE_DUPLICATE_CHECK` |

---

## 5. Target semantic fitness model

**Layer A (mandatory, ordinary `test`):**

- New `S01SemanticArchitectureFitnessTest` — former semantic assertions + **role-based** Spring delivery checks (`READER_DELIVERY`, `WRITER_DELIVERY_TRIGGER`, `READER_INDIRECT` in `platform-tracing-spring-boot-autoconfigure`) without pinning adapter FQCNs.
- Existing Gate E3 primitive tests, ArchUnit / P4 / P6 / private-control / P56 gates unchanged.

**Not reimplemented as a graph framework** — role/module predicates over committed corrective JSON plus existing production arch tests.

---

## 6. Inventory and drift model

**Layer B:** `repository-symbol-inventory.json` remains the human/review topology evidence. Exact FQCN sites checked by `S01OwnerInventoryDriftTest` (`@Tag("s01-inventory-evidence")`).

**Layer C:** `S01InventoryDriftClassifier` classifies synthetic/before-after corrective JSON:

| Kind | Meaning |
|------|---------|
| `RENAME_OR_MOVE_ONLY` | Same `(owner, role)` set; class/method text changed |
| `NEW_ALLOWED_READER` | New reader role |
| `REMOVED_READER` | Reader role disappeared |
| `NEW_OWNER` / `DUPLICATE_OWNER` | Owner set / leaf-claim violation |
| `NEW_WRITER` | New writer role for an owner |
| `FORBIDDEN_BYPASS` | Spring-module writer that is not approved delivery/indirect |
| `UNKNOWN_SEMANTIC_DRIFT` | Other semantic delta |

Ordinary build fails on Layer A semantic violations; Layer B is evidence-only.

---

## 7. Gradle task changes

| Task | Behavior |
|------|----------|
| `:platform-tracing-bench:test` | `excludeTags 's01-inventory-evidence'` |
| `acceptedStatePrimitiveTest` | Same exclude (primitive ≠ topology snapshot) |
| `s01OwnerInventoryEvidenceTest` / `s01OwnerInventoryEvidenceVerify` | **New** — `includeTags 's01-inventory-evidence'` |
| `implementationReadinessVerify` | Unchanged (E8); does not hard-fail on FQCN rename |

Why new tasks (not a module/framework): mirror existing `acceptedStatePrimitiveVerify` / `priv07ProposalVerify` pattern — one Test task + Verify aggregator, tag-filtered, no production code.

---

## 8. Rename-resilience proof

`S01InventoryDriftClassifierTest` fixtures (no production rename):

- Changed FQCN for delivery site → `RENAME_OR_MOVE_ONLY`
- Changed method text, same role → `RENAME_OR_MOVE_ONLY`

Ordinary semantic test uses roles; renaming `LegacyTracingDiagnosticsEndpoint` in production while updating inventory `class` but keeping `READER_DELIVERY` keeps aggregate `test` green. Evidence task stays red until exact topology assertion/`class` field is updated deliberately.

---

## 9. Semantic violation proof

Classifier fixtures:

| Scenario | Result |
|----------|--------|
| Extra mutable owner | `NEW_OWNER` |
| New writer role | `NEW_WRITER` |
| Spring `WRITER` cache-like site | `FORBIDDEN_BYPASS` |
| Duplicate leaf claims | `DUPLICATE_OWNER` or `NEW_OWNER` |
| New reader role | `NEW_ALLOWED_READER` (not owner violation) |
| Delivery trigger removed | not `RENAME_OR_MOVE_ONLY` |

Layer A still asserts delivery **roles** present — removing `WRITER_DELIVERY_TRIGGER` from committed inventory fails ordinary semantic fitness.

---

## 10. Performance and developer-workflow impact

| Measurement | Value |
|-------------|-------|
| `:platform-tracing-bench:test --tests "*S01*" --rerun-tasks` (after fix) | ~18.3s wall, PASS |
| Aggregate `test --rerun-tasks` (green rerun) | ~67.5s wall, PASS |
| `s01OwnerInventoryEvidenceVerify --rerun-tasks` | ~16.2s wall, PASS |
| Fragility | Safe delivery-adapter rename no longer fails aggregate `test` |
| Wall-clock claim | No meaningful suite-time reduction claimed; benefit is fewer false-red aggregates |

---

## 11. Files changed

| Path | Change |
|------|--------|
| `S01OwnerInventoryEvidenceTest.java` | Deleted (split) |
| `S01SemanticArchitectureFitnessTest.java` | Added — mandatory Layer A |
| `S01OwnerInventoryDriftTest.java` | Added — tagged evidence Layer B |
| `S01InventoryDriftClassifier.java` | Added — Layer C support (1 class) |
| `S01InventoryDriftClassifierTest.java` | Added — fixtures |
| `platform-tracing-bench/build.gradle` | Exclude evidence tag from ordinary test |
| `build.gradle` | `s01OwnerInventoryEvidenceVerify` + exclude tag on acceptedStatePrimitiveTest |
| `s01-governance.md` | Governance documentation |
| `tracing-configuration-inventory/*` | Regenerated after test FQCN churn |

---

## 12. Verification results

| Command | First | Rerun |
|---------|-------|-------|
| `:platform-tracing-bench:test --tests "*S01*" --rerun-tasks` | **FAIL** (classifier fixture) | **PASS** |
| `s01OwnerInventoryEvidenceVerify --rerun-tasks` | PASS | — |
| `test --rerun-tasks` | **FAIL** (config inventory drift) | **PASS** after `-PupdateTracingConfigurationInventory --rerun-tasks` |
| `tracingConfigurationInventoryVerify` (after forced regen) | PASS | — |
| `implementationReadinessVerify --rerun-tasks` | PASS | — |
| `git diff --check` | PASS (CRLF warnings only) | — |

Ordinary `*S01*` run includes semantic + classifier tests; does **not** include `S01OwnerInventoryDriftTest` (tag excluded).

---

## 13. First-run failures

1. `S01InventoryDriftClassifierTest.secondWriterIntroduced_isNewWriter` — same `WRITER` role classified as rename-equivalent; fixture was temporarily changed to `WRITER_ALTERNATE_PATH`. **Retained as the defect that required the cardinality correction (§ Same-role cardinality defect).**
2. Aggregate `test` — `TracingConfigurationInventoryGeneratorTest` drift from deleted/added S-01 test FQCNs; regenerated inventory with `--rerun-tasks`. Retained.

---

## 14. Remaining risks

| Risk | Notes |
|------|-------|
| Inventory still hand-maintained | No generator; evidence task catches FQCN drift at release time |
| Stale roles in JSON | Semantic test trusts committed roles; production ArchUnit/P56 still catch many real violations |
| Classifier is fixture-oriented | Not a full repo scanner — by design (budget) |
| `WRITER_INDIRECT` cardinality | Not exact-gated in Layer A (multiple approved indirect paths); evidence-only |
| Historical inventory may still list obsolete WRITER_INDIRECT paths | Out of scope cleanup |

---

## 15. Final recommendation

Adopt the layered model as the standing S-01 governance:

1. Ordinary development: rely on `S01SemanticArchitectureFitnessTest` (roles + exact writer cardinalities) + existing arch gates.
2. Architecture/release review: run `s01OwnerInventoryEvidenceVerify` and update `repository-symbol-inventory.json` when topology intentionally changes.
3. Do not reintroduce exact delivery FQCNs into root `test`.
4. Treat same-role physical-site cardinality as architecture, not rename noise.

---

## Same-role cardinality defect

The first classifier run exposed a real false negative: comparing only the set of `(owner, role)` treated

```text
before: 1 × (SafeSpanExporter, WRITER)
after:  2 × (SafeSpanExporter, WRITER)  // second physical class/method
```

as rename-equivalent when the set of semantic keys was unchanged. Changing the fixture role to `WRITER_ALTERNATE_PATH` made the test green without fixing detection of same-role duplicates.

## Cardinality-aware classifier correction

`S01InventoryDriftClassifier` now:

- strips line-number suffixes from `method` for physical identity;
- indexes physical sites as `(module, class, method)`;
- counts physical sites per semantic key `(owner, role)` (multiset cardinality);
- returns `NEW_WRITER` when a writer-role count increases (including same-role second site);
- returns `RENAME_OR_MOVE_ONLY` only when semantic keys **and** per-role counts are unchanged while physical keys change;
- treats writer-role count decreases as `UNKNOWN_SEMANTIC_DRIFT` (not rename-only).

No new support class. Production code unchanged.

## Same-role duplicate writer proof

Restored fixture `sameRoleSecondWriterSite_isNewWriter_notRenameOnly`:

- same owner, same `WRITER` role, one site → two distinct physical sites → `NEW_WRITER` (not `RENAME_OR_MOVE_ONLY`).

Mandatory Layer A: `approvedWriterRoleCardinalitiesAreExact` asserts B1 `WRITER` count == 2 (from committed inventory: owner + Platform*Control facade). A third same-role WRITER fails ordinary `test`.

## Same-role delivery-trigger proof

Fixture `sameRoleSecondDeliveryTrigger_isNewWriter_notRenameOnly` → `NEW_WRITER`.

Mandatory Layer A: B1 `WRITER_DELIVERY_TRIGGER` count == 1.

## Updated verification results

| Command | Result |
|---------|--------|
| `:platform-tracing-bench:test --tests "*S01*" --rerun-tasks` | PASS |
| `s01OwnerInventoryEvidenceVerify --rerun-tasks` | PASS |
| `test --rerun-tasks` | PASS (~59s) |
| `tracingConfigurationInventoryVerify --rerun-tasks` | PASS |
| `implementationReadinessVerify --rerun-tasks` | PASS |
| `git diff --check` | PASS (CRLF warnings only) |

## Final corrected verdict

```text
PASS
```

---

## 16. Commit and push status

| Action | Status |
|--------|--------|
| Commit | NOT PERFORMED |
| Push | NOT PERFORMED |

---

## Final summary

```text
HEAD before: 65cf036082e39778ab4e9ffa4369285e8d14072b
Working tree before: dirty with S-01 optimization (pre-cardinality fix)
Classifier comparison before: set of (owner, role) only — same-role second site → false RENAME_OR_MOVE_ONLY
Classifier comparison after: multiset counts per (owner, role) + physical (module,class,method)
Physical-site identity: module|class|method (line numbers stripped)
Semantic identity: owner|role
Same-role WRITER duplicate: NEW_WRITER (fixture restored; not role-rename workaround)
Same-role delivery-trigger duplicate: NEW_WRITER
Rename-only class change: RENAME_OR_MOVE_ONLY
Rename-only method change: RENAME_OR_MOVE_ONLY
Mandatory semantic gate: WRITER=2 and WRITER_DELIVERY_TRIGGER=1 for B1 owners
Evidence task: s01OwnerInventoryEvidenceVerify PASS (exact FQCN retained)
Production behavior changed: NO
Bench S01 tests: PASS
Aggregate test: PASS
Inventory verify: PASS
Implementation readiness: PASS
First-run failures: same-role false negative (retained); config inventory drift (retained)
Skipped gates: Docker E2E (not required)
Blockers: none
Verdict: PASS
Commit: NOT PERFORMED
Push: NOT PERFORMED
```

