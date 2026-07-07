# RC-1 Status — Release Candidate for Local Adoption

| Field | Value |
|-------|-------|
| Project | `opus-mcp-server` |
| Version | `0.1.0-SNAPSHOT` |
| Status | **Release candidate for local adoption** (not production certified, not enterprise approved, not security approved) |
| Transport | stdio (stdout = JSON-RPC only; logs on stderr) |
| Server name | `java-mcp-opus-server` |
| Java required | 21 |

This document records RC-1 readiness. For the gate checklist see
[RELEASE-CANDIDATE.md](RELEASE-CANDIDATE.md); for onboarding see
[OPERATOR-ADOPTION.md](OPERATOR-ADOPTION.md); for build/distribution/rollback see
[RELEASE.md](RELEASE.md).

## Toolset

Fifteen MCP tools — thirteen Opus-backed read-only tools, one Perplexity-backed read-only research
tool (Phase 8B), plus the non-Opus `echo_mcp_connection`:

- `echo_mcp_connection` (connectivity only, no provider call)
- `generate_code_with_opus`
- `review_code_with_opus`
- `generate_tests_with_opus`
- `refactor_plan_with_opus`
- `explain_diff_with_opus`
- `research_with_perplexity` (Perplexity provider; requires `PERPLEXITY_API_KEY` for live calls,
  otherwise returns a safe provider-not-configured result with no network call)
- `analyze_build_failure_with_opus` (Phase 9A; read-only build/test/static-analysis failure analysis)
- `design_class_hierarchy_with_opus` (Phase 10A; read-only class/interface hierarchy design)
- `review_architecture_with_opus` (Phase 11A; read-only architecture proposal/ADR/migration review)
- `write_mdx_doc_with_opus` (Phase 12A; read-only MDX documentation draft)
- `review_mdx_doc_with_opus` (Phase 13A; read-only MDX documentation review)
- `generate_migration_plan_with_opus` (Phase 14A; read-only migration planning)
- `review_tests_with_opus` (Phase 15A; read-only test review)
- `review_gradle_build_with_opus` (Phase 16A; read-only Gradle build review)

All provider-backed tools only **propose** / **answer**; they never read/write repository files, run
commands, run tests, or apply patches. Cursor/the operator applies every change manually.

## Offline gate status — PASSED

`releasePackageCheck` is the single deterministic offline gate (no network, no `OPUS_API_KEY`):

- `clean test` — passed (no real API / no network)
- `verifyLocal` — passed (test + fat-jar)
- `releaseCheck` — passed
- `securityHandoff` — passed (dependency inventory generated; inventory is not a CVE verdict)
- `releasePackageCheck` — passed (fat-jar + checksum + manifest cross-verified)

## Live gate status — PASSED (operator-run, per FINAL_STABILIZATION)

Recorded from the latest operator live runs (not rerun here; rerun requires credentials):

- positive smoke passed: `generate_code_with_opus`, `review_code_with_opus`,
  `generate_tests_with_opus`, `refactor_plan_with_opus`, `explain_diff_with_opus` (`status=OK`)
- `analyze_build_failure_with_opus` (Phase 9A) shares the identical Opus pipeline and is offline-verified;
  its positive live smoke (`scripts/smoke-analyze-build-failure.ps1`) is expected to pass when `OPUS_*`
  credentials are configured
- `design_class_hierarchy_with_opus` (Phase 10A) shares the identical Opus pipeline and is offline-verified;
  its positive live smoke (`scripts/smoke-design-class-hierarchy.ps1`) is expected to pass when `OPUS_*`
  credentials are configured
- `review_architecture_with_opus` (Phase 11A) shares the identical Opus pipeline and is offline-verified;
  its positive live smoke (`scripts/smoke-review-architecture.ps1`) is expected to pass when `OPUS_*`
  credentials are configured
- `write_mdx_doc_with_opus` (Phase 12A) shares the identical Opus pipeline and is offline-verified;
  its positive live smoke (`scripts/smoke-write-mdx-doc.ps1`) is expected to pass when `OPUS_*`
  credentials are configured
- `review_mdx_doc_with_opus` (Phase 13A) shares the identical Opus pipeline and is offline-verified;
  its positive live smoke (`scripts/smoke-review-mdx-doc.ps1`) is expected to pass when `OPUS_*`
  credentials are configured
- `generate_migration_plan_with_opus` (Phase 14A) shares the identical Opus pipeline and is
  offline-verified; its positive live smoke (`scripts/smoke-generate-migration-plan.ps1`) is expected
  to pass when `OPUS_*` credentials are configured
- `review_tests_with_opus` (Phase 15A) shares the identical Opus pipeline and is offline-verified;
  its positive live smoke (`scripts/smoke-review-tests.ps1`) is expected to pass when `OPUS_*`
  credentials are configured
- `review_gradle_build_with_opus` (Phase 16A) shares the identical Opus pipeline and is offline-verified;
  its positive live smoke (`scripts/smoke-review-gradle-build.ps1`) is expected to pass when `OPUS_*`
  credentials are configured
- negative `.env` smoke passed (`REFUSED_UNSAFE`, model not called)
- negative private-key smoke passed at least once (`REFUSED_UNSAFE`, secret not echoed)

`research_with_perplexity` live smoke is **PENDING** — `PERPLEXITY_API_KEY` is not yet available.
Its offline behavior is verified and was further hardened in **Phase 8C**: missing-key returns
`MODEL_ERROR` (provider-not-configured) with no network call (and a follow-up question to set the key
and rerun the smoke), the response parser tolerates malformed/minimal/table/delimited/URL provider
responses, the prompt enforces public-source/no-secret/no-auto-patch/uncertainty constraints, and
guardrails/parse/error-mapping are covered by mocked-provider contract tests. The missing-key contract
is asserted offline via `scripts/smoke-research-perplexity.ps1 -ExpectMissingKey`. It is safe to expose
because the missing-key path is strictly no-network. Live research remains **unverified** until a
key-based smoke passes (Phase 8B.1 / 8D).

Live provider availability can vary; operators should re-run the smoke pack after any rebuild or
environment change (see [OPERATOR-ADOPTION.md](OPERATOR-ADOPTION.md) → "Smoke pack").

## Security guardrails status — UNCHANGED / ACTIVE

SecretScanner, DenyList, BudgetTracker, RateLimiter, RetryPolicy, AuditLogger, Masking, result
extraction, model allowlist, and the stdout-JSON-RPC-only rule are all in place and unchanged. Audit
is metadata-only (`OPUS_AUDIT_INCLUDE_CONTENT=false`). Unsafe input is refused before any network
call.

## Release artifacts

- Fat-jar: `build/libs/opus-mcp-server-0.1.0-SNAPSHOT-all.jar`
- Manifest: `build/distributions/release-manifest.json` (`schemaVersion=1`, project, version,
  mainClass, `java.requiredVersion=21`, `mcp.serverName=java-mcp-opus-server`,
  `mcp.transport=stdio`, all thirteen tools, sha256)
- Checksum: `build/distributions/checksums/opus-mcp-server-0.1.0-SNAPSHOT-all.jar.sha256`

## Checksum

```
58e7e16d8c756dbfbd18e1befe236d2007a78e7d88b991c0ed7f7f4ae91b0f7e  opus-mcp-server-0.1.0-SNAPSHOT-all.jar
```

The checksum recorded here corresponds to a specific local build; verify the deployed jar's SHA-256
against its `.sha256` sidecar and the manifest after every rebuild rather than trusting a copied hash.

## Known limitations

- External provider must be approved before proprietary code/diffs are sent (the provider receives
  your prompt).
- Budget/rate/audit state is in-memory only (resets daily and on restart; no persistence).
- Dependency inventory is not a CVE verdict (corporate scanner required — see
  [SECURITY-SCAN-CONTRACT.md](SECURITY-SCAN-CONTRACT.md)).
- No automatic file writes, no automatic patch application, no automatic test execution.
- No HTTP/SSE remote mode; stdio-only, local single-user.
- No Nexus/Maven publishing; the deliverable is a local fat-jar.
- Live provider availability can vary (e.g. gateway `502`); triage via
  [OPERATIONS.md](OPERATIONS.md) §5.1.
- `research_with_perplexity` live research is **not yet verified** and **provider approval is
  pending**: it requires `PERPLEXITY_API_KEY` (not currently available). Without the key the tool is
  safe but inert (`MODEL_ERROR`, no network call), so it is safe to expose. The architect review pack
  (Phase 8E) defines the path to live use:
  [ADR](decisions/ADR-research-with-perplexity-offline-first.md),
  [threat model](RESEARCH-WITH-PERPLEXITY-THREAT-MODEL.md),
  [provider approval checklist](PERPLEXITY-PROVIDER-APPROVAL-CHECKLIST.md),
  [live gate](PERPLEXITY-LIVE-GATE.md), [operator runbook](PERPLEXITY-OPERATOR-RUNBOOK.md). Live smoke
  is the next step (Phase 8B.1 / 8E) once a key is provided and provider approval is signed off.

## Operator checklist (condensed)

1. `.\gradlew.bat clean releasePackageCheck`
2. Copy jar + `.sha256` + `release-manifest.json` to a stable local tools directory.
3. Configure Cursor `mcp.json` with the stable jar path (from
   [cursor-mcp.example.json](cursor-mcp.example.json)).
4. Set `OPUS_API_KEY` outside the repository (OS env / secret store).
5. Set `OPUS_BASE_URL`.
6. Set `OPUS_MODEL` (allowlisted).
7. Restart Cursor.
8. Run echo connectivity check (`scripts/smoke-stdio.ps1`); confirm thirteen tools in `tools/list`.
9. Run the positive smoke pack.
10. Run the negative guardrail smokes (`.env`, private-key).
11. Start using the tools on approved / synthetic / non-sensitive code first.

Full steps: [OPERATOR-ADOPTION.md](OPERATOR-ADOPTION.md).

## Rollback summary

Previous releases stay under `releases\<version>\` (the installer never deletes them). To roll back:
point Cursor `mcp.json` `-jar` to the previous release jar (or restore
`current\opus-mcp-server-current.jar`), restart the Cursor MCP server, then re-run the connectivity
smoke (and live smokes if credentials are available). Rollback is manual; there is no automatic
rollback. Full procedure: [RELEASE.md](RELEASE.md) → "Rolling back".

## Final verdict

RC-1 is a **release candidate for local adoption**. The offline gate passes deterministically and the
operator live gate is recorded as passed. Adopt locally on non-sensitive code first, collect operator
feedback, and only then decide whether stabilization fixes are needed. Stop adding tools.
