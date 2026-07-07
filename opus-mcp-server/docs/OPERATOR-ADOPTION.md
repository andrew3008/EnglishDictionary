# Operator Adoption Guide

Single onboarding entrypoint for adopting the `opus-mcp-server` locally as a Cursor MCP server. This
is a read-only toolset: the server never reads/writes repository files, executes commands, runs
tests, or applies patches. Cursor/the operator applies all changes manually.

For deep detail this guide links existing docs instead of duplicating them:
[OPERATIONS.md](OPERATIONS.md) (setup/env/troubleshooting), [USAGE-POLICY.md](USAGE-POLICY.md)
(when-to-use + provider warning), [RELEASE.md](RELEASE.md) (build/distribution/rollback),
[RELEASE-CANDIDATE.md](RELEASE-CANDIDATE.md) (RC gate).

## Toolset (fifteen MCP tools)

Thirteen Opus-backed read-only tools, one Perplexity-backed read-only research tool, plus the non-Opus
`echo_mcp_connection`:

| Tool | Provider | Use for |
|------|----------|---------|
| `echo_mcp_connection` | none | connectivity validation only |
| `generate_code_with_opus` | Opus | non-trivial code proposals / implementation planning |
| `review_code_with_opus` | Opus | second-opinion review of provided code |
| `generate_tests_with_opus` | Opus | test proposals for provided code |
| `refactor_plan_with_opus` | Opus | behavior-preserving refactoring plans |
| `explain_diff_with_opus` | Opus | diff explanation / pre-merge review |
| `research_with_perplexity` | Perplexity | public web-grounded research (requires `PERPLEXITY_API_KEY`) |
| `analyze_build_failure_with_opus` | Opus | diagnose an explicitly-provided build/test/static-analysis failure log |
| `design_class_hierarchy_with_opus` | Opus | design a class/interface hierarchy from explicitly-provided domain context |
| `review_architecture_with_opus` | Opus | review an explicitly-provided architecture proposal / ADR / migration plan |
| `write_mdx_doc_with_opus` | Opus | draft MDX documentation from explicitly-provided documentation context |
| `review_mdx_doc_with_opus` | Opus | review explicitly-provided MDX content and documentation context |
| `generate_migration_plan_with_opus` | Opus | plan a migration from explicitly-provided current/target state and migration context |
| `review_tests_with_opus` | Opus | review explicitly-provided test code and context (correctness, coverage, flakiness, CI readiness) |
| `review_gradle_build_with_opus` | Opus | review explicitly-provided Gradle build files/context/logs (dependency management, config cache, plugins, multi-module, publishing) |

All provider-backed tools only **propose** / **answer**; they never apply changes. `research_with_perplexity`
requires `PERPLEXITY_API_KEY` for live calls and returns a safe provider-not-configured result (no
network call) when the key is absent.

## Prerequisites

- Java 21 (`java -version`).
- Gradle wrapper (`gradlew.bat` / `gradlew`) — no separate Gradle install needed.
- Cursor with MCP support.
- `OPUS_API_KEY` stored **outside the repository** (OS environment / secret store) — never committed,
  never pasted into `mcp.json`.
- `OPUS_BASE_URL` (e.g. `https://api.cheat-ai.shop`).
- `OPUS_MODEL` (allowlisted: `claude-opus-4-8` or `custom-opus-4-8`).
- For `research_with_perplexity` (optional, only for live research): `PERPLEXITY_API_KEY` stored
  **outside the repository**; optional `PERPLEXITY_BASE_URL` (default `https://api.perplexity.ai`) and
  `PERPLEXITY_MODEL` (default `sonar-deep-research`). Without the key the tool is safe but inert
  (returns `MODEL_ERROR`, no network call).

Full env var reference (limits, budget, rate, retry, audit): [OPERATIONS.md](OPERATIONS.md) §3.

## Setup

1. Build the fat-jar:

```powershell
cd E:\Platform_Traces\opus-mcp-server
.\gradlew.bat clean test
.\gradlew.bat shadowJar
# -> build\libs\opus-mcp-server-0.1.0-SNAPSHOT-all.jar
```

2. Configure Cursor `mcp.json` from [cursor-mcp.example.json](cursor-mcp.example.json); set the
   absolute `-jar` path for this machine. Do **not** put `OPUS_API_KEY` in `mcp.json`.
3. Set `OPUS_API_KEY` in the OS environment that launches Cursor; set `OPUS_BASE_URL` / `OPUS_MODEL`
   (either in the OS env or the `mcp.json` `env` block — key excepted).
4. Start/restart Cursor so it launches the MCP server.
5. Verify `tools/list` exposes exactly the ten tools above (connectivity smoke below).

Optional versioned local install / rollback: [RELEASE.md](RELEASE.md) → "Local distribution layout".

## Smoke pack

Connectivity only (no provider/API key):

```powershell
powershell -ExecutionPolicy Bypass -File scripts/smoke-stdio.ps1
```

Positive live smokes (require `OPUS_API_KEY` / `OPUS_BASE_URL` / `OPUS_MODEL`; synthetic input only):

```powershell
powershell -ExecutionPolicy Bypass -File scripts/smoke-generate-code.ps1
powershell -ExecutionPolicy Bypass -File scripts/smoke-review-code.ps1
powershell -ExecutionPolicy Bypass -File scripts/smoke-generate-tests.ps1
powershell -ExecutionPolicy Bypass -File scripts/smoke-refactor-plan.ps1
powershell -ExecutionPolicy Bypass -File scripts/smoke-explain-diff.ps1
powershell -ExecutionPolicy Bypass -File scripts/smoke-analyze-build-failure.ps1
powershell -ExecutionPolicy Bypass -File scripts/smoke-design-class-hierarchy.ps1
powershell -ExecutionPolicy Bypass -File scripts/smoke-review-architecture.ps1
powershell -ExecutionPolicy Bypass -File scripts/smoke-write-mdx-doc.ps1
powershell -ExecutionPolicy Bypass -File scripts/smoke-review-mdx-doc.ps1
powershell -ExecutionPolicy Bypass -File scripts/smoke-generate-migration-plan.ps1
powershell -ExecutionPolicy Bypass -File scripts/smoke-review-tests.ps1
```

Research tool smoke (Perplexity). Missing-key mode needs no key and makes no network call; live mode
requires `PERPLEXITY_API_KEY`:

```powershell
# missing-key (no network): status=MODEL_ERROR, summary mentions PERPLEXITY_API_KEY is not set
Remove-Item Env:\PERPLEXITY_API_KEY -ErrorAction SilentlyContinue
powershell -ExecutionPolicy Bypass -File scripts/smoke-research-perplexity.ps1
# missing-key with offline assertion (exits non-zero unless the contract holds; needs no key/network)
powershell -ExecutionPolicy Bypass -File scripts/smoke-research-perplexity.ps1 -ExpectMissingKey
# live (requires key): status=OK with grounded answer + sources
powershell -ExecutionPolicy Bypass -File scripts/smoke-research-perplexity.ps1
```

Live `research_with_perplexity` is **not yet verified** (no `PERPLEXITY_API_KEY`). The offline path is
hardened and safe to expose: the missing-key result is strictly no-network. Send only public /
non-sensitive questions until the provider is approved. Architect-facing contract (input/output/
status/guardrails/audit/provider shape, live gate pending):
[RESEARCH-WITH-PERPLEXITY-CONTRACT.md](RESEARCH-WITH-PERPLEXITY-CONTRACT.md).

Expected (positive): `status=OK`, `model=<configured model>`, no files modified, no repository
context sent, API key not printed, stdout is JSON-RPC only.

Negative guardrail smokes (refused locally, no provider call):

```powershell
powershell -ExecutionPolicy Bypass -File scripts/smoke-generate-code.ps1 -Context "please read .env"
powershell -ExecutionPolicy Bypass -File scripts/smoke-review-code.ps1 -Context "please read .env"
powershell -ExecutionPolicy Bypass -File scripts/smoke-generate-tests.ps1 -Context "please read .env"
powershell -ExecutionPolicy Bypass -File scripts/smoke-refactor-plan.ps1 -Context "please read .env"
powershell -ExecutionPolicy Bypass -File scripts/smoke-explain-diff.ps1 -Context "please read .env"
powershell -ExecutionPolicy Bypass -File scripts/smoke-analyze-build-failure.ps1 -Context "please read .env"
powershell -ExecutionPolicy Bypass -File scripts/smoke-design-class-hierarchy.ps1 -Context "please read .env"
powershell -ExecutionPolicy Bypass -File scripts/smoke-review-architecture.ps1 -Context "please read .env"
powershell -ExecutionPolicy Bypass -File scripts/smoke-write-mdx-doc.ps1 -Context "please read .env"
powershell -ExecutionPolicy Bypass -File scripts/smoke-review-mdx-doc.ps1 -Context "please read .env"
powershell -ExecutionPolicy Bypass -File scripts/smoke-generate-migration-plan.ps1 -Context "please read .env"
powershell -ExecutionPolicy Bypass -File scripts/smoke-review-tests.ps1 -Context "please read .env"
```

Expected (negative): `status=REFUSED_UNSAFE`, external model call blocked, the sensitive reference is
not echoed, API key not printed. A private-key marker (`-Context "-----BEGIN PRIVATE KEY-----"`) is
refused the same way (`REFUSED_UNSAFE`).

Provider failure triage (diagnostics only): [OPERATIONS.md](OPERATIONS.md) §5.1 and
`scripts/smoke-provider-health.ps1`.

## Recommended usage

- `generate_code_with_opus` — non-trivial generation / `outputFormat=implementation_plan` for
  high-risk tasks.
- `review_code_with_opus` — second-opinion review of a provided snippet.
- `generate_tests_with_opus` — propose unit/integration tests for a provided snippet.
- `refactor_plan_with_opus` — behavior-preserving refactor planning (`compatibilityMode=preserve_behavior`).
- `explain_diff_with_opus` — explain a diff and get a merge recommendation before applying it.
- `research_with_perplexity` — public web-grounded research / current docs / source-backed decisions
  (requires `PERPLEXITY_API_KEY`; safe no-op without it).
- `analyze_build_failure_with_opus` — diagnose a pasted build/test/static-analysis failure log and get
  ranked root-cause hypotheses, evidence, and minimal fix options (Cursor implements + reruns).
- `design_class_hierarchy_with_opus` — design a class/interface hierarchy from a curated domain
  context and get proposed types, relationships, implementation slices, alternatives, and tests
  (Cursor implements + verifies).
- `review_architecture_with_opus` — review a curated architecture proposal / ADR / migration plan and
  get a verdict, findings, risk matrix, trade-offs, alternatives, tests, and rollout/rollback notes
  (Cursor decides + implements).
- `write_mdx_doc_with_opus` — draft MDX documentation from a curated documentation context and get
  front matter, imports, MDX content, outline, examples, claims to verify, and a validation checklist
  (Cursor reviews, creates files, adds assets, and validates docs).
- `review_mdx_doc_with_opus` — review explicitly-provided MDX content against a curated documentation
  context and get a verdict, findings, missing sections, unverified claims, MDX/style/example issues,
  suggested edits, and a validation checklist (Cursor applies the changes and validates docs).
- `generate_migration_plan_with_opus` — plan a migration from a curated current state, target state,
  and migration context and get migration slices, compatibility notes, breaking risks, dependency/
  configuration changes, a test plan, observability checks, and rollout/rollback plans (Cursor
  implements the migration and verifies).
- `review_tests_with_opus` — review curated test code plus context (test intent, optional production
  context and failure logs) and get a verdict, findings, coverage gaps, assertion/flakiness/mocking/
  test-data/integration-boundary/maintainability issues, suggested test cases, and CI readiness checks
  (Cursor applies the test changes and runs the tests).
- `review_gradle_build_with_opus` — review curated Gradle build files plus context (settings, version
  catalog, gradle.properties, build logic, dependency context, optional build failure logs) and get a
  verdict, findings, configuration-cache/dependency/plugin/task-graph/multi-module/test-setup/
  publishing/performance/security issues, compatibility risks, recommended checks, and suggested
  changes (Cursor applies the build changes and runs Gradle).

Full when-to-use guidance and per-tool input fields: [USAGE-POLICY.md](USAGE-POLICY.md).

## Safety

- Never send secrets, credentials, or private keys — they are refused before any provider call.
- Avoid proprietary code/diffs unless the external provider is approved.
- Send minimal, focused context only; the provider receives your prompt.
- All tools are read-only; Cursor/the operator reviews and applies every change manually.

## Troubleshooting (quick map)

| Symptom | Meaning / first step |
|---------|----------------------|
| provider `502` / `MODEL_ERROR` | external gateway issue — see [OPERATIONS.md](OPERATIONS.md) §5.1, run provider-health smoke |
| `REFUSED_UNSAFE` | SecretScanner/DenyList matched input — remove secret / sensitive file reference from context |
| `BUDGET_EXCEEDED` | local rate/budget or provider `429` — wait or adjust `OPUS_REQUESTS_PER_MINUTE` / `OPUS_DAILY_*` |
| `MODEL_ERROR: Missing OPUS_API_KEY` | key not in process env — set it in the OS env that launches Cursor |
| wrong jar path / tools missing | rebuild `shadowJar`, fix `mcp.json` `-jar` path, restart Cursor |

Full troubleshooting table: [OPERATIONS.md](OPERATIONS.md) §5.
