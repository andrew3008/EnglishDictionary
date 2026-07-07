# opus-mcp-server

Read-only Java MCP server for Cursor. Current slice: **Phase 0A + 0B + 1 + 2 + 3 + 4 + 5 + 6A + 6B + 6C + 7A + 7B + 7C + 7D + 8B + 8C + 8D + 8E + 9A + 10A + 11A + 12A + 13A + 14A**
(*Java MCP Opus Server* plan; Phase 8B = `research_with_perplexity` read-only tool, offline-first;
Phase 8C = research offline hardening + Cursor adoption readiness; Phase 8D = offline contract
finalization + architect readiness; Phase 8E = architect review pack (ADR, threat model, live gate,
provider approval, operator runbook) — live Perplexity research is **not yet verified** until a
key-based smoke passes and provider approval is signed off; Phase 9A = `analyze_build_failure_with_opus`
read-only build-failure analysis tool; Phase 10A = `design_class_hierarchy_with_opus`
read-only class-hierarchy design tool; Phase 11A = `review_architecture_with_opus`
read-only architecture review tool; Phase 12A = `write_mdx_doc_with_opus`
read-only MDX documentation draft tool; Phase 13A = `review_mdx_doc_with_opus`
read-only MDX documentation review tool; Phase 14A = `generate_migration_plan_with_opus`
read-only migration planning tool; Phase 15A = `review_tests_with_opus`
read-only test review tool; Phase 16A = `review_gradle_build_with_opus`
read-only Gradle build review tool).

Exposed MCP tools:

- `echo_mcp_connection` — connectivity validation (no external API)
- `generate_code_with_opus` — read-only code proposal via Opus `/v1/messages`
- `review_code_with_opus` — read-only structured code review of explicitly-provided code via Opus `/v1/messages`
- `generate_tests_with_opus` — read-only structured test-generation proposal for explicitly-provided code via Opus `/v1/messages`
- `refactor_plan_with_opus` — read-only structured refactoring plan for explicitly-provided code via Opus `/v1/messages`
- `explain_diff_with_opus` — read-only structured explanation/review of an explicitly-provided diff via Opus `/v1/messages`
- `research_with_perplexity` — read-only public web-grounded research via the Perplexity provider (`/chat/completions`); requires `PERPLEXITY_API_KEY` for live calls, otherwise returns a safe provider-not-configured result with no network call
- `analyze_build_failure_with_opus` — read-only structured analysis of an explicitly-provided build/test/static-analysis failure log (plus optional curated code/build context) via Opus `/v1/messages`; returns diagnosis, root-cause hypotheses, evidence, fix options, a textual minimal patch suggestion, and tests to rerun
- `design_class_hierarchy_with_opus` — read-only structured class/interface hierarchy design from an explicitly-provided domain context (plus optional existing-type summary, package context, constraints) via Opus `/v1/messages`; returns a design overview, proposed types, relationships, a package plan, implementation slices, extension points, alternatives, tests to add, risks, and anti-patterns
- `review_architecture_with_opus` — read-only structured architecture review of an explicitly-provided architecture proposal / ADR / design plan / migration plan (plus optional context, constraints) via Opus `/v1/messages`; returns a summary, verdict, review, findings, risk matrix, trade-offs, alternatives, open questions, tests to add, observability checks, rollout and rollback notes
- `write_mdx_doc_with_opus` — read-only MDX documentation draft from an explicitly-provided documentation context (doc subject, library context, plus optional public API, configuration properties, usage examples, doc style context, MDX components context, asset guidelines, constraints) via Opus `/v1/messages`; returns a summary, front matter, imports, MDX content, outline, examples, admonitions, assets needed, links to add, claims to verify, and a validation checklist. Does not read doc-portal/repository files, write MDX files, create assets, or run Docusaurus
- `review_mdx_doc_with_opus` — read-only MDX documentation review of explicitly-provided MDX content plus documentation context (doc subject, target audience, plus optional library context, style guide context, MDX components context, constraints) via Opus `/v1/messages`; returns a summary, verdict, review, findings, missing sections, incorrect/unverified claims, MDX issues, style issues, example issues, suggested edits, and a validation checklist. Does not read doc-portal/repository files, write MDX files, create assets, run Docusaurus, or apply patches
- `generate_migration_plan_with_opus` — read-only migration plan from an explicitly-provided current state, target state, and migration context (plus optional constraints) via Opus `/v1/messages`; returns a summary, migration overview, migration slices (id/title/goal/changes/verification/risk/rollback), compatibility notes, breaking risks, dependency/configuration changes, test plan, observability checks, rollout plan, rollback plan, docs updates, open questions, and risks. Does not read repository files, write files, upgrade dependencies, run Gradle, run tests, or apply patches
- `review_tests_with_opus` — read-only test review of explicitly-provided test code plus context (test intent, plus optional production context, failure logs, dependencies context, constraints) via Opus `/v1/messages`; returns a summary, verdict, review, findings (severity/category/title/details/recommendation), coverage gaps, assertion issues, flakiness risks, mocking issues, test data issues, integration-boundary issues, maintainability issues, suggested test cases, CI readiness checks, open questions, and risks. Does not read repository files, write files, run tests, collect coverage, run Gradle/Maven, or apply patches
- `review_gradle_build_with_opus` — read-only Gradle build review of explicitly-provided build files context plus context (settings, version catalog, gradle.properties, build logic, dependency context, optional build failure logs, constraints) via Opus `/v1/messages`; returns a summary, verdict, review, findings (severity/category/title/details/recommendation), configuration-cache issues, dependency issues, plugin issues, task graph issues, multi-module issues, test setup issues, publishing issues, performance issues, security issues, compatibility risks, recommended checks, suggested changes, open questions, and risks. Does not read repository files, write files, modify build scripts, run Gradle/Maven, run tests, resolve dependencies, publish artifacts, or apply patches

Cursor remains orchestrator/applier. This server never reads/writes repository files or executes commands.

## Operational docs

- [docs/OPERATOR-ADOPTION.md](docs/OPERATOR-ADOPTION.md) — single onboarding entrypoint: prerequisites, setup, smoke pack, recommended usage, safety, troubleshooting.
- [docs/RELEASE-CANDIDATE.md](docs/RELEASE-CANDIDATE.md) — release-candidate readiness checklist (offline gate + operator live gate).
- [docs/RC-1-STATUS.md](docs/RC-1-STATUS.md) — RC-1 status report: version, gate status, artifacts, known limitations, operator checklist, rollback, verdict.
- [docs/OPERATIONS.md](docs/OPERATIONS.md) — Windows setup, env var reference, smoke checklist, troubleshooting, operational readiness checklist.
- [docs/USAGE-POLICY.md](docs/USAGE-POLICY.md) — when to call `generate_code_with_opus` / `review_code_with_opus` / `generate_tests_with_opus` / `refactor_plan_with_opus` / `explain_diff_with_opus` / `research_with_perplexity` / `analyze_build_failure_with_opus` / `design_class_hierarchy_with_opus` / `review_architecture_with_opus` / `write_mdx_doc_with_opus` / `review_mdx_doc_with_opus` / `generate_migration_plan_with_opus` / `review_tests_with_opus` / `review_gradle_build_with_opus` and the external-provider security warning.
- [docs/RESEARCH-WITH-PERPLEXITY-CONTRACT.md](docs/RESEARCH-WITH-PERPLEXITY-CONTRACT.md) — architect-facing offline contract for `research_with_perplexity` (input/output/status/guardrails/audit/provider shape; live gate pending).
- Architect review pack (Phase 8E) for `research_with_perplexity` — [ADR](docs/decisions/ADR-research-with-perplexity-offline-first.md), [threat model](docs/RESEARCH-WITH-PERPLEXITY-THREAT-MODEL.md), [data-flow diagram](docs/diagrams/research-with-perplexity-flow.puml), [provider approval checklist](docs/PERPLEXITY-PROVIDER-APPROVAL-CHECKLIST.md), [live gate](docs/PERPLEXITY-LIVE-GATE.md), [operator runbook](docs/PERPLEXITY-OPERATOR-RUNBOOK.md).
- [docs/cursor-mcp.example.json](docs/cursor-mcp.example.json) — Cursor MCP config template (no API key).
- [docs/RELEASE.md](docs/RELEASE.md) — build/distribution, dependency locking, supply-chain report, release checklist, CI commands.
- [docs/SECURITY-SCAN-CONTRACT.md](docs/SECURITY-SCAN-CONTRACT.md) — corporate CVE/SCA scanner handoff contract, inventory format, FAIL/WARN/PASS and suppression policy.

## Requirements

- Java 21
- Gradle wrapper (`gradlew` / `gradlew.bat`)

## Configuration (environment variables)

Set in OS environment / secret store — **never** commit `OPUS_API_KEY`:

| Variable | Required | Default |
|----------|----------|---------|
| `OPUS_API_KEY` | for `generate_code_with_opus` | — |
| `OPUS_BASE_URL` | for generation | — |
| `OPUS_MODEL` | optional | `claude-opus-4-8` |
| `OPUS_MAX_TOKENS` | optional | `4096` |
| `OPUS_REQUEST_TIMEOUT_SECONDS` | optional | `60` |
| `OPUS_MAX_CONTEXT_CHARS` | optional | `32000` (cap `2000000`) |
| `OPUS_MAX_CONSTRAINTS_CHARS` | optional | `8000` (cap `1000000`) |
| `OPUS_MAX_OUTPUT_CHARS` | optional | `64000` (cap `4000000`) |

All numeric values are clamped to safe caps; invalid/negative values fall back to defaults. `OPUS_MAX_TOKENS` is capped at `200000`, `OPUS_REQUEST_TIMEOUT_SECONDS` at `600`.

### Phase 3 — budget / rate / retry / audit

| Variable | Required | Default | Notes |
|----------|----------|---------|-------|
| `OPUS_DAILY_REQUEST_LIMIT` | optional | `0` (disabled) | max successful+attempted requests/day |
| `OPUS_DAILY_INPUT_CHAR_LIMIT` | optional | `0` (disabled) | max input chars/day |
| `OPUS_DAILY_ESTIMATED_TOKEN_LIMIT` | optional | `0` (disabled) | max estimated input tokens/day |
| `OPUS_DAILY_COST_LIMIT` | optional | `0` (disabled) | requires price vars below |
| `OPUS_PRICE_PER_1K_INPUT_TOKENS` | optional | `0` | for cost accounting only |
| `OPUS_PRICE_PER_1K_OUTPUT_TOKENS` | optional | `0` | for cost accounting only |
| `OPUS_REQUESTS_PER_MINUTE` | optional | `0` (disabled) | sliding-window rate limit |
| `OPUS_RETRY_MAX_ATTEMPTS` | optional | `3` (cap `10`) | total attempts incl. first |
| `OPUS_RETRY_BASE_DELAY_MS` | optional | `200` | exponential backoff base |
| `OPUS_RETRY_MAX_DELAY_MS` | optional | `2000` (cap `120000`) | backoff ceiling |
| `OPUS_AUDIT_INCLUDE_CONTENT` | optional | `false` | content audit is **not supported**; metadata only |

Limits default to **disabled** (`0`) so that an unconfigured server still works; set them to enforce a budget. Budget/rate state is **in-memory only** (no persistence in Phase 3); counters reset daily and on restart.

Verified provider example:

```powershell
$env:OPUS_BASE_URL="https://api.cheat-ai.shop"
$env:OPUS_MODEL="claude-opus-4-8"
# OPUS_API_KEY from OS env / secret store
```

Model allowlist: `claude-opus-4-8`, `custom-opus-4-8`.

### Security guardrails (Phase 3)

The request pipeline for `generate_code_with_opus` runs guards **before** any external model call:

```
validate input -> deny-list scan -> secret scan -> size limits
  -> config validation -> model allowlist -> rate limit -> budget pre-check
  -> model call (bounded retry) -> budget update -> safe audit log
```

- **SecretScanner** — refuses likely secret material (private key blocks, bearer tokens, AWS keys, `api_key=/password=/secret=` assignments) with `REFUSED_UNSAFE`. Violation messages never echo the secret.
- **DenyList** — refuses sensitive file references (`.env`, `id_rsa`/`id_ed25519`, `*.pem/*.key/*.p12/*.jks`, `credentials*`, `secrets*`, `.ssh/`, `.git/`, `kubeconfig`, `application-prod.yml`).
- **RateLimiter / BudgetTracker** — exceeding either returns `BUDGET_EXCEEDED`; the model is **not** called.
- **RetryPolicy** — retries only transient failures (HTTP 408/429/500/502/503/504, timeouts, network errors) with bounded exponential backoff + jitter. Never retries 400/401/403/404, validation, or budget failures.
- **Masking** — API key, bearer tokens, `x-api-key` values, secret assignments, and private key blocks are redacted before any log/error. MCP output never contains raw stack traces or secrets.

**What is logged** (audit, stderr/file only, metadata only): requestId, timestamp, tool, model, language, outputFormat, riskLevel, status, latencyMs, input char count, estimated input/output tokens, estimated cost, budget/rate decisions, HTTP status category.

**What is never logged or returned:** `task`, `context`, `constraints`, model output/result, the API key, or the raw provider response.

**Security warning:** the external provider receives your prompt. Do not send proprietary code or secrets in tool `context` unless the provider is approved. Unsafe input is refused before any network call.

### Error mapping (chosen semantics)

`blank/invalid input` -> `NEEDS_MORE_CONTEXT`; `deny-list`/`secret` -> `REFUSED_UNSAFE`; `context too large` -> `NEEDS_MORE_CONTEXT`; `model not allowlisted` / `missing key` / `invalid base URL` / HTTP `400/401/403/404` / `5xx after retries` / `parse failure` -> `MODEL_ERROR`; `rate limit` / `daily budget` / HTTP `429 after retries` -> `BUDGET_EXCEEDED`.

## Build & test

```powershell
./gradlew clean test
./gradlew shadowJar
# -> build/libs/opus-mcp-server-0.1.0-SNAPSHOT-all.jar
```

### CI-friendly verification

All tests run without a real API key, real network, or a Cursor process. For CI use either the
standard commands above or the convenience task:

```powershell
.\gradlew.bat verifyLocal     # full test suite + fat-jar
.\gradlew.bat releaseCheck    # deterministic release gate: test + fat-jar + artifact + dep report
```

The suite includes a stdio MCP integration test (spawns the server in a child JVM with `OPUS_*`
env stripped), tool-schema compatibility tests, and docs/config hygiene tests (valid example JSON,
no leaked secrets). Live provider smokes remain manual (see [docs/OPERATIONS.md](docs/OPERATIONS.md)).

CI must not require `OPUS_API_KEY` and must not call the real provider. Constrained CI without
subprocess support can skip the stdio integration test:

```powershell
.\gradlew.bat test -PskipStdioIntegration=true
```

### Supply-chain & release (Phase 6A)

Dependency resolution is locked via `gradle.lockfile` (`dependencyLocking`); refresh deliberately
with `.\gradlew.bat resolveAndLockAll --write-locks`. An offline supply-chain report writes a
dependency **inventory** (txt + JSON) for an out-of-band CVE scanner:

```powershell
.\gradlew.bat dependencySecurityReport   # -> build/reports/supply-chain/runtime-dependencies.{txt,json}
.\gradlew.bat securityHandoff            # validate inventory + print corporate-scanner handoff (no network)
```

> **Inventory is not a CVE verdict.** `dependencySecurityReport` lists *which* dependencies are
> present; it does not prove they are vulnerability-free. The CVE verdict comes from the approved
> corporate scanner consuming the inventory — see
> [docs/SECURITY-SCAN-CONTRACT.md](docs/SECURITY-SCAN-CONTRACT.md) for the handoff contract,
> FAIL/WARN/PASS policy, and suppression format.

Release packaging adds deterministic, offline artifacts (no network/credentials/timestamps):

```powershell
.\gradlew.bat generateReleaseChecksums   # SHA-256 sidecar for the fat-jar
.\gradlew.bat generateReleaseManifest    # release-manifest.json (project/version/tools/checksum)
.\gradlew.bat releasePackageCheck        # releaseCheck + checksum + manifest, cross-verified
```

Full versioning policy, build/distribution/rollback/version-verify steps, release notes template,
and the operator release checklist are in [docs/RELEASE.md](docs/RELEASE.md) and
[docs/RELEASE-NOTES-TEMPLATE.md](docs/RELEASE-NOTES-TEMPLATE.md). A full vulnerability scan (e.g.
OWASP dependency-check) is optional and intentionally not wired into normal CI (network +
vulnerability DB required).

## Connect to Cursor

See [`docs/cursor-mcp.example.json`](docs/cursor-mcp.example.json). Example:

```json
{
  "mcpServers": {
    "java-opus-mcp": {
      "command": "java",
      "args": ["-jar", "E:/Platform_Traces/opus-mcp-server/build/libs/opus-mcp-server-0.1.0-SNAPSHOT-all.jar"],
      "env": {
        "OPUS_BASE_URL": "https://api.cheat-ai.shop",
        "OPUS_MODEL": "claude-opus-4-8"
      }
    }
  }
}
```

Do **not** put `OPUS_API_KEY` in `mcp.json`. Adjust the absolute jar path per machine. Full setup,
env var reference, and troubleshooting are in [docs/OPERATIONS.md](docs/OPERATIONS.md).

## Cursor usage policy (summary)

Call `generate_code_with_opus` only for non-trivial generation, complex refactoring proposals,
architecture-sensitive planning, test planning, or second-opinion proposals. Avoid it for trivial
edits, formatting, renames, or anything involving secrets / proprietary code unless the provider is
approved. For high-risk tasks prefer `outputFormat=implementation_plan`, pass minimal context, never
pass secrets, and review/apply results manually. Full policy and provider security warning:
[docs/USAGE-POLICY.md](docs/USAGE-POLICY.md).

## Tool: `generate_code_with_opus`

Read-only proposal generation. Input (required: `task`, `language`, `outputFormat`, `riskLevel`):

```json
{
  "task": "Generate a Java method that adds two integers",
  "language": "java",
  "context": "minimal relevant context from Cursor only",
  "constraints": "Java 21, no external libraries",
  "outputFormat": "code_block",
  "riskLevel": "low"
}
```

Output statuses: `OK`, `NEEDS_MORE_CONTEXT`, `REFUSED_UNSAFE`, `MODEL_ERROR`, `BUDGET_EXCEEDED`.

When the model returns a structured response (`SUMMARY:` / `RESULT:` / `ASSUMPTIONS:` / `RISKS:` / `SAFETY_NOTES:` / `TESTS_TO_RUN:`), the `result` field contains **only the RESULT section body** (fenced code blocks preserved); the other sections are surfaced in `summary`, `assumptions`, `risks`, `safetyNotes`, and `testsToRun`. If no `RESULT:` section is present, `result` holds the full model output (e.g. a plain code block).

Use for non-trivial generation/refactoring/test planning. Cursor/user must review and apply results.

## Tool: `review_code_with_opus`

Read-only structured code review of code **explicitly provided** in the input. Same guardrails as
`generate_code_with_opus` (deny-list, secret scan, size limits, model allowlist, rate limit, budget)
run before any model call. Does not read/write files, execute commands, or apply patches.

Input (required: `task`, `language`, `code`, `reviewFocus`, `riskLevel`, `outputFormat`):

```json
{
  "task": "Review this method for correctness and overflow safety",
  "language": "java",
  "code": "public static int add(int a, int b) { return a + b; }",
  "context": "minimal relevant context from Cursor only",
  "constraints": "Java 21",
  "reviewFocus": "all",
  "riskLevel": "medium",
  "outputFormat": "structured_review"
}
```

Output keys: `status`, `summary`, `review`, `findings[]` (`severity`, `category`, `title`, `details`,
`recommendation`), `risks`, `safetyNotes`, `assumptions`, `testsToRun`, `truncated`,
`inputTokenEstimate`, `outputTokenEstimate`, `model`, `requestId`. Statuses match
`generate_code_with_opus`. Findings parsing is defensive: unknown severities fall back to `LOW`,
unknown categories to `other`, and a malformed finding never aborts the rest.

> Use only for second-opinion review of provided snippets. Do not send proprietary code or secrets
> unless the external provider is approved. See [docs/USAGE-POLICY.md](docs/USAGE-POLICY.md).

### Manual review smoke (real endpoint, synthetic code)

```powershell
powershell -ExecutionPolicy Bypass -File scripts/smoke-review-code.ps1
# negative (no model call): sensitive file reference -> REFUSED_UNSAFE
powershell -ExecutionPolicy Bypass -File scripts/smoke-review-code.ps1 -Context "please read .env"
```

## Tool: `generate_tests_with_opus`

Read-only test-generation proposal for code **explicitly provided** in the input. Same guardrails as
`generate_code_with_opus` (deny-list, secret scan, size limits, model allowlist, rate limit, budget)
run before any model call. Does not read/write files, execute commands, run tests, or apply patches.

Input (required: `task`, `language`, `code`, `testFramework`, `testType`, `coverageFocus`,
`riskLevel`, `outputFormat`):

```json
{
  "task": "Generate unit tests for the add method",
  "language": "java",
  "code": "public static int add(int a, int b) { return a + b; }",
  "context": "minimal relevant context from Cursor only",
  "constraints": "Java 21, no external libraries",
  "testFramework": "junit5",
  "testType": "unit",
  "coverageFocus": "edge_cases",
  "riskLevel": "medium",
  "outputFormat": "structured_tests"
}
```

Output keys: `status`, `summary`, `testPlan`, `testCode`, `testCases[]` (`name`, `type`, `purpose`,
`given`, `when`, `then`, `priority`), `risks`, `safetyNotes`, `assumptions`, `testsToRun`,
`truncated`, `inputTokenEstimate`, `outputTokenEstimate`, `model`, `requestId`. Statuses match
`generate_code_with_opus`. Test-case parsing is defensive: unknown types fall back to `other`,
unknown priorities to `MEDIUM`, and a malformed test case never aborts the rest. The tool only
**proposes** tests — Cursor/user must review, apply, and run them manually.

> Use only for test proposals on provided snippets. Do not send proprietary code or secrets unless
> the external provider is approved. See [docs/USAGE-POLICY.md](docs/USAGE-POLICY.md).

### Manual test-generation smoke (real endpoint, synthetic code)

```powershell
powershell -ExecutionPolicy Bypass -File scripts/smoke-generate-tests.ps1
# negative (no model call): sensitive file reference -> REFUSED_UNSAFE
powershell -ExecutionPolicy Bypass -File scripts/smoke-generate-tests.ps1 -Context "please read .env"
```

## Tool: `refactor_plan_with_opus`

Read-only refactoring **plan** for code **explicitly provided** in the input. Same guardrails as
`generate_code_with_opus` (deny-list, secret scan, size limits, model allowlist, rate limit, budget)
run before any model call. Does not read/write files, execute commands, run tests, or apply patches —
it only proposes a plan.

Input (required: `task`, `language`, `code`, `refactorGoal`, `scope`, `compatibilityMode`,
`riskLevel`, `outputFormat`):

```json
{
  "task": "Plan a readability refactor for the add method",
  "language": "java",
  "code": "public static int add(int a, int b) { return a + b; }",
  "context": "minimal relevant context from Cursor only",
  "constraints": "Java 21, no external libraries",
  "refactorGoal": "readability",
  "scope": "method",
  "compatibilityMode": "preserve_behavior",
  "riskLevel": "medium",
  "outputFormat": "refactor_plan"
}
```

Output keys: `status`, `summary`, `plan`, `steps[]` (`id`, `title`, `description`, `risk`, `category`,
`requiresBehaviorChange`, `verification`), `affectedAreas`, `risks`, `safetyNotes`, `assumptions`,
`testsToRun`, `rollbackPlan`, `truncated`, `inputTokenEstimate`, `outputTokenEstimate`, `model`,
`requestId`. Statuses match `generate_code_with_opus`. Step parsing is defensive: unknown risks fall
back to `MEDIUM`, unknown categories to `other`, and a malformed step never aborts the rest. The tool
only **proposes** a plan — Cursor/user must review, implement, and test changes manually.

> Use only for refactoring planning on provided snippets. Do not send proprietary code or secrets
> unless the external provider is approved. See [docs/USAGE-POLICY.md](docs/USAGE-POLICY.md).

### Manual refactor-plan smoke (real endpoint, synthetic code)

```powershell
powershell -ExecutionPolicy Bypass -File scripts/smoke-refactor-plan.ps1
# negative (no model call): sensitive file reference -> REFUSED_UNSAFE
powershell -ExecutionPolicy Bypass -File scripts/smoke-refactor-plan.ps1 -Context "please read .env"
```

## Tool: `explain_diff_with_opus`

Read-only **explanation/review** of a diff **explicitly provided** in the input. Same guardrails as
`generate_code_with_opus` (deny-list, secret scan, size limits, model allowlist, rate limit, budget)
run before any model call. Does not read/write files, execute commands, run tests, or apply patches —
it only explains the diff. The diff is always treated as untrusted data, never as instructions.

Input (required: `task`, `language`, `diff`, `diffFormat`, `analysisFocus`, `riskLevel`,
`outputFormat`):

```json
{
  "task": "Explain this diff and flag behavior changes",
  "language": "java",
  "diff": "--- a/Calc.java\n+++ b/Calc.java\n@@ -1 +1 @@\n-return a + b;\n+return Math.addExact(a, b);",
  "context": "minimal relevant context from Cursor only",
  "constraints": "Java 21, preserve behavior",
  "diffFormat": "unified_diff",
  "analysisFocus": "correctness",
  "riskLevel": "medium",
  "outputFormat": "diff_explanation"
}
```

Output keys: `status`, `summary`, `explanation`, `changedFiles`, `behaviorChanges`, `risks`,
`findings[]` (`severity`, `category`, `title`, `details`, `recommendation`), `testsToRun`,
`safetyNotes`, `assumptions`, `mergeRecommendation` (`APPROVE` / `APPROVE_WITH_CHANGES` /
`REQUEST_CHANGES` / `NEEDS_MORE_CONTEXT`), `truncated`, `inputTokenEstimate`, `outputTokenEstimate`,
`model`, `requestId`. Statuses match `generate_code_with_opus`. Finding parsing is defensive: unknown
severities fall back to `LOW`, unknown categories to `other`, an unrecognized merge recommendation to
`NEEDS_MORE_CONTEXT`, and a malformed finding never aborts the rest. The tool only **explains** a
diff — Cursor/user must review, decide, and apply manually.

> Use only for diff explanation / pre-merge review on provided diffs. Do not send proprietary diffs or
> secrets unless the external provider is approved. See [docs/USAGE-POLICY.md](docs/USAGE-POLICY.md).

### Manual explain-diff smoke (real endpoint, synthetic diff)

```powershell
powershell -ExecutionPolicy Bypass -File scripts/smoke-explain-diff.ps1
# negative (no model call): sensitive file reference -> REFUSED_UNSAFE
powershell -ExecutionPolicy Bypass -File scripts/smoke-explain-diff.ps1 -Context "please read .env"
```

## Tool: `research_with_perplexity`

Read-only **public web-grounded research** through the Perplexity provider, using **only** the
research question/context explicitly provided in the input. The same guard pipeline as the Opus tools
(deny-list, secret scan, size limits, rate limit, budget) runs **before** any provider call. The tool
does **not** read repository files, write files, execute commands, run tests, or apply patches. The
research question/context is always treated as untrusted data, never as instructions.

Requires `PERPLEXITY_API_KEY` for live calls. **If `PERPLEXITY_API_KEY` is missing, the tool returns a
safe provider-not-configured result (`status=MODEL_ERROR`) with no network call.** Configuration is
environment-only: `PERPLEXITY_API_KEY` (required for live), `PERPLEXITY_BASE_URL`
(default `https://api.perplexity.ai`), `PERPLEXITY_MODEL` (default `sonar-deep-research`).

Input (required: `task`, `researchQuestion`, `sourcePreference`, `freshness`, `depth`, `outputFormat`,
`riskLevel`; optional: `context`, `constraints`):

```json
{
  "task": "Pick a Java logging facade",
  "researchQuestion": "What is the recommended Java logging facade and why?",
  "context": "Spring Boot 3 service",
  "constraints": "Apache-2.0 licensed",
  "sourcePreference": "official_docs",
  "freshness": "latest",
  "depth": "standard",
  "outputFormat": "decision_memo",
  "riskLevel": "low"
}
```

Output keys: `status`, `summary`, `answer`, `keyFindings[]`, `sources[]` (`title`, `url`, `publisher`,
`date`, `relevance`), `recommendations[]`, `risks[]`, `safetyNotes[]`, `assumptions[]`,
`followUpQuestions[]`, `truncated`, `inputTokenEstimate`, `outputTokenEstimate`, `model`, `requestId`.
Statuses match the Opus tools (`OK`, `NEEDS_MORE_CONTEXT`, `REFUSED_UNSAFE`, `MODEL_ERROR`,
`BUDGET_EXCEEDED`). Source parsing is defensive (Phase 8C): it tolerates `key: value` blocks, bare
bullets, markdown tables, delimited rows (`Title — URL — relevance`), inline URLs, and
`Source: <url>` lines, and a malformed source row never aborts the rest of the response. Provider
errors are mapped safely (`401/403`→auth, `404`→model-not-found, `429`→rate/quota, `5xx`→provider-down,
network/parse→model-error) without leaking the raw provider body.

> **Live status:** `research_with_perplexity` live research is **not yet verified** — it requires
> `PERPLEXITY_API_KEY` (not currently available). The offline path is hardened and safe to expose
> because the missing-key result is strictly no-network. Live verification is a later step (Phase
> 8B.1 / 8E) once a key is provided. Full contract:
> [docs/RESEARCH-WITH-PERPLEXITY-CONTRACT.md](docs/RESEARCH-WITH-PERPLEXITY-CONTRACT.md).

> Use it for public research / best practices / current docs / source-backed decisions. Do not send
> proprietary code or secrets unless the Perplexity provider is approved. See
> [docs/USAGE-POLICY.md](docs/USAGE-POLICY.md).

### Manual research smoke (missing-key and live modes)

```powershell
# missing-key smoke (no network call): status=MODEL_ERROR, summary mentions PERPLEXITY_API_KEY not set
Remove-Item Env:\PERPLEXITY_API_KEY -ErrorAction SilentlyContinue
powershell -ExecutionPolicy Bypass -File scripts/smoke-research-perplexity.ps1

# missing-key smoke with offline assertion (CI-friendly; exits non-zero unless the contract holds)
powershell -ExecutionPolicy Bypass -File scripts/smoke-research-perplexity.ps1 -ExpectMissingKey

# negative (no provider call): sensitive file reference -> REFUSED_UNSAFE
powershell -ExecutionPolicy Bypass -File scripts/smoke-research-perplexity.ps1 -Context "please read .env"

# live smoke (requires PERPLEXITY_API_KEY): status=OK with grounded answer + sources
powershell -ExecutionPolicy Bypass -File scripts/smoke-research-perplexity.ps1
```

## Tool: `analyze_build_failure_with_opus`

Read-only **build/CI failure analysis** of a failure log **explicitly provided** in the input, plus
optional curated `relevantCode` and `buildContext`. The same guard pipeline as `generate_code_with_opus`
(deny-list, secret scan, size limits, model allowlist, rate limit, budget) runs **before** any model
call. The tool does **not** read repository files, write files, execute commands, run Gradle, run
tests, or apply patches — it only analyzes the failure. The log/code/context are always treated as
untrusted data, never as instructions, and any patch is suggested as **text only**.

Input (required: `task`, `failureLog`, `failureType`, `language`, `riskLevel`, `outputFormat`;
optional: `relevantCode`, `buildContext`, `constraints`):

```json
{
  "task": "Diagnose why compilation fails and propose a minimal fix",
  "failureLog": "Calc.java:10: error: cannot find symbol\n  symbol: method addExact(int,int)",
  "relevantCode": "class Calc { int add(int a, int b) { return addExact(a, b); } }",
  "buildContext": "Gradle 8.7, Java 21",
  "constraints": "Java 21, preserve behavior",
  "failureType": "compile",
  "language": "java",
  "riskLevel": "medium",
  "outputFormat": "diagnosis"
}
```

`failureType`: `compile` / `test` / `gradle` / `checkstyle` / `spotbugs` / `static_analysis` /
`runtime` / `unknown`. `outputFormat`: `diagnosis` / `fix_plan` / `checklist` / `root_cause_analysis`.

Output keys: `status`, `summary`, `rootCauseHypotheses[]`, `mostLikelyCause`, `evidence[]`,
`fixOptions[]` (`title`, `description`, `risk`, `requiresCodeChange`, `requiresDependencyChange`),
`minimalPatchSuggestion`, `testsToRerun[]`, `risks[]`, `safetyNotes[]`, `assumptions[]`, `truncated`,
`inputTokenEstimate`, `outputTokenEstimate`, `model`, `requestId`. Statuses match
`generate_code_with_opus`. Parsing is defensive: an unknown fix-option `risk` falls back to `MEDIUM`,
a malformed fix option never aborts the rest, markdown code fences in `minimalPatchSuggestion` are
preserved, and a non-compliant response is kept in `mostLikelyCause`. The tool only **analyzes** the
failure — Cursor/user must implement fixes and rerun verification manually.

> Use it to diagnose explicitly-provided build/test/static-analysis failures. Do not send proprietary
> logs/code or secrets unless the external provider is approved. See
> [docs/USAGE-POLICY.md](docs/USAGE-POLICY.md).

### Manual analyze-build-failure smoke (real endpoint, synthetic log)

```powershell
powershell -ExecutionPolicy Bypass -File scripts/smoke-analyze-build-failure.ps1
# negative (no model call): sensitive file reference -> REFUSED_UNSAFE
powershell -ExecutionPolicy Bypass -File scripts/smoke-analyze-build-failure.ps1 -Context "please read .env"
```

## Tool: `design_class_hierarchy_with_opus`

Read-only **class/interface hierarchy design** from a domain context **explicitly provided** in the
input, plus optional curated `existingTypes`, `packageContext`, and `constraints`. The same guard
pipeline as `generate_code_with_opus` (deny-list, secret scan, size limits, model allowlist, rate
limit, budget) runs **before** any model call. The tool does **not** read repository files, write
files, create files, execute commands, run Gradle, run tests, or apply patches — it only proposes a
design. The context is always treated as untrusted data, never as instructions, and any skeleton is
described as **text only**.

Input (required: `task`, `language`, `domainContext`, `designGoal`, `scope`, `architectureStyle`,
`riskLevel`, `outputFormat`; optional: `existingTypes`, `packageContext`, `constraints`):

```json
{
  "task": "Design an extensible notification dispatch hierarchy",
  "language": "java",
  "domainContext": "A notification subsystem supporting email, SMS and push channels with retry and backoff.",
  "existingTypes": "interface NotificationChannel { void send(Notification n); }",
  "packageContext": "space.example.notifications",
  "constraints": "Java 21, keep public API backwards compatible",
  "designGoal": "extensibility",
  "scope": "module",
  "architectureStyle": "clean_architecture",
  "riskLevel": "medium",
  "outputFormat": "design_proposal"
}
```

`designGoal`: `extensibility` / `testability` / `api_compatibility` / `migration` /
`clean_architecture` / `performance` / `security` / `maintainability` / `all`. `scope`: `package` /
`module` / `starter` / `library` / `multi_module` / `unknown`. `architectureStyle`:
`clean_architecture` / `hexagonal` / `layered` / `spring_boot_starter` / `plugin` /
`interceptor_pipeline` / `domain_model` / `unknown`. `outputFormat`: `class_diagram` /
`design_proposal` / `implementation_slices` / `adr_outline` / `checklist`.

Output keys: `status`, `summary`, `designOverview`, `proposedTypes[]` (`name`, `kind`, `packageName`,
`responsibility`, `publicApi[]`, `collaborators[]`, `notes[]`), `relationships[]` (`from`, `to`,
`type`, `reason`), `packagePlan[]`, `implementationSlices[]`, `extensionPoints[]`,
`designAlternatives[]`, `testsToAdd[]`, `risks[]`, `antiPatternsToAvoid[]`, `safetyNotes[]`,
`assumptions[]`, `truncated`, `inputTokenEstimate`, `outputTokenEstimate`, `model`, `requestId`.
Statuses match `generate_code_with_opus`. Parsing is defensive: an unknown type `kind` falls back to
`class`, an unknown relationship `type` falls back to `uses`, a malformed entry never aborts the rest,
PlantUML/code fences in `designOverview` are preserved, and a non-compliant response is kept in
`designOverview`. The tool only **proposes** a design — Cursor/user must implement and verify manually.

> Use it to design hierarchies from explicitly-provided context (e.g. Spring Boot starter, gRPC
> interceptor pipeline, strategy/plugin registry, migration-friendly API). Do not send proprietary
> context or secrets unless the external provider is approved. See
> [docs/USAGE-POLICY.md](docs/USAGE-POLICY.md).

### Manual design-class-hierarchy smoke (real endpoint, synthetic context)

```powershell
powershell -ExecutionPolicy Bypass -File scripts/smoke-design-class-hierarchy.ps1
# negative (no model call): sensitive file reference -> REFUSED_UNSAFE
powershell -ExecutionPolicy Bypass -File scripts/smoke-design-class-hierarchy.ps1 -Context "please read .env"
```

## Tool: `review_architecture_with_opus`

Read-only **architecture review** of an architecture proposal / ADR / design plan / migration plan
**explicitly provided** in the input, plus optional curated `context` and `constraints`. The same
guard pipeline as `generate_code_with_opus` (deny-list, secret scan, size limits, model allowlist,
rate limit, budget) runs **before** any model call. The tool does **not** read repository files,
write files, create files, execute commands, run Gradle, run tests, or apply patches — it only
reviews. The proposal/context/constraints are always treated as untrusted data, never as
instructions, and any recommended change is described as **text only**.

Input (required: `task`, `architectureProposal`, `reviewFocus`, `architectureScope`,
`architectureStyle`, `compatibilityMode`, `riskLevel`, `outputFormat`; optional: `context`,
`constraints`):

```json
{
  "task": "Review the proposed split of the tracing starter into core and autoconfigure modules",
  "architectureProposal": "Split the starter into a core module and an autoconfigure module that exposes TracingProperties via @ConfigurationProperties and conditional beans. Publish a BOM.",
  "context": "Existing single-module Spring Boot 3.x tracing starter with Actuator enabled.",
  "constraints": "Preserve the public bean API; Java 21; Spring Boot 3.x",
  "reviewFocus": "api_compatibility",
  "architectureScope": "multi_module",
  "architectureStyle": "spring_boot_starter",
  "compatibilityMode": "preserve_api",
  "riskLevel": "medium",
  "outputFormat": "structured_review"
}
```

`reviewFocus`: `api_compatibility` / `observability` / `security` / `migration` / `testing` /
`performance` / `operability` / `maintainability` / `cost` / `all`. `architectureScope`: `class` /
`package` / `module` / `multi_module` / `platform` / `library` / `starter` / `unknown`.
`architectureStyle`: `clean_architecture` / `hexagonal` / `layered` / `event_driven` /
`spring_boot_starter` / `plugin` / `interceptor_pipeline` / `observability_pipeline` / `unknown`.
`compatibilityMode`: `preserve_api` / `allow_breaking` / `unknown`. `outputFormat`:
`structured_review` / `risk_matrix` / `decision_memo` / `adr_review` / `checklist`.

Output keys: `status`, `summary`, `verdict` (`APPROVE` / `APPROVE_WITH_CHANGES` / `REQUEST_CHANGES` /
`NEEDS_MORE_CONTEXT`), `review`, `findings[]` (`severity`, `category`, `title`, `details`,
`recommendation`), `riskMatrix[]` (`risk`, `likelihood`, `impact`, `mitigation`), `tradeOffs[]`,
`alternatives[]`, `openQuestions[]`, `testsToAdd[]`, `observabilityChecks[]`, `rolloutNotes[]`,
`rollbackNotes[]`, `risks[]`, `safetyNotes[]`, `assumptions[]`, `truncated`, `inputTokenEstimate`,
`outputTokenEstimate`, `model`, `requestId`. Statuses match `generate_code_with_opus`. Parsing is
defensive: an unknown `severity` falls back to `MEDIUM`, an unknown `category` to `other`, an unknown
`likelihood`/`impact` to `MEDIUM`, an unknown `verdict` to `NEEDS_MORE_CONTEXT`, a malformed entry
never aborts the rest, a markdown table in the risk matrix is parsed, code/PlantUML fences in `review`
are preserved, and a non-compliant response is kept in `review`. The tool only **reviews** — Cursor/user
must decide and implement manually.

> Use it to review ADRs, Spring Boot starter architectures, migration plans, observability/tracing
> architectures, or multi-module Gradle layouts from explicitly-provided context. Do not send
> proprietary context or secrets unless the external provider is approved. See
> [docs/USAGE-POLICY.md](docs/USAGE-POLICY.md).

### Manual review-architecture smoke (real endpoint, synthetic proposal)

```powershell
powershell -ExecutionPolicy Bypass -File scripts/smoke-review-architecture.ps1
# negative (no model call): sensitive file reference -> REFUSED_UNSAFE
powershell -ExecutionPolicy Bypass -File scripts/smoke-review-architecture.ps1 -Context "please read .env"
```

## Tool: `write_mdx_doc_with_opus`

Read-only **MDX documentation draft** from a documentation context **explicitly provided** in the
input (`task`, `docSubject`, `libraryContext`, plus optional `publicApi`, `configurationProperties`,
`usageExamples`, `docStyleContext`, `mdxComponentsContext`, `assetGuidelines`, `constraints`). The
same guard pipeline as `generate_code_with_opus` (deny-list, secret scan, size limits, model
allowlist, rate limit, budget) runs **before** any model call. The tool does **not** read
doc-portal/repository files, write MDX files, create assets, run Docusaurus, run tests, or apply
patches — it only drafts. All provided context is treated as untrusted data, never as instructions,
and the model is instructed not to invent public API, configuration, or behavior beyond the input.

Input (required: `task`, `docSubject`, `targetAudience`, `libraryContext`, `docType`, `outputFormat`,
`riskLevel`; optional: `publicApi`, `configurationProperties`, `usageExamples`, `docStyleContext`,
`mdxComponentsContext`, `assetGuidelines`, `constraints`):

```json
{
  "task": "Draft a starter guide page for enabling distributed tracing",
  "docSubject": "Platform Tracing Starter",
  "targetAudience": "application_developers",
  "libraryContext": "A Spring Boot 3.x starter that auto-configures distributed tracing.",
  "publicApi": "TracingProperties, TracingAutoConfiguration",
  "configurationProperties": "platform.tracing.enabled (boolean, default false)",
  "usageExamples": "Add the starter dependency, then set platform.tracing.enabled=true",
  "docStyleContext": "Use second person, short sections, code fences for config.",
  "mdxComponentsContext": "import Tabs from '@theme/Tabs'; import TabItem from '@theme/TabItem';",
  "assetGuidelines": "Use SVG diagrams under static/img; describe diagrams, do not create them.",
  "constraints": "Keep it concise; Java 21; Spring Boot 3.x",
  "docType": "starter_guide",
  "outputFormat": "mdx_page",
  "riskLevel": "medium"
}
```

`targetAudience`: `platform_developers` / `application_developers` / `sre` / `architects` / `mixed`.
`docType`: `library_guide` / `starter_guide` / `migration_guide` / `how_to` / `reference` / `adr` /
`release_notes` / `troubleshooting` / `unknown`. `outputFormat`: `mdx_page` / `mdx_section` /
`outline` / `frontmatter_plus_body` / `reviewable_draft`.

Output keys: `status`, `summary`, `frontMatter`, `imports[]`, `mdxContent`, `outline[]`, `examples[]`,
`admonitions[]`, `assetsNeeded[]`, `linksToAdd[]`, `claimsToVerify[]`, `validationChecklist[]`,
`risks[]`, `safetyNotes[]`, `assumptions[]`, `truncated`, `inputTokenEstimate`, `outputTokenEstimate`,
`model`, `requestId`. Statuses match `generate_code_with_opus`. Parsing is defensive: `frontMatter`
and `mdxContent` are preserved as verbatim multi-line blocks, code fences and JSX components inside
`mdxContent` are kept intact, duplicated section headings are merged, missing sections degrade to
empty values, and a non-compliant response is kept in `mdxContent`. The tool only **drafts** —
Cursor/user must review, create files, add assets, and run documentation validation manually.

> Use it to draft library/starter guides, configuration reference sections, migration guide sections,
> troubleshooting sections, or full MDX pages with explicit style context. Do not send proprietary
> context or secrets unless the external provider is approved. See
> [docs/USAGE-POLICY.md](docs/USAGE-POLICY.md).

### Manual write-mdx-doc smoke (real endpoint, synthetic context)

```powershell
powershell -ExecutionPolicy Bypass -File scripts/smoke-write-mdx-doc.ps1
# negative (no model call): sensitive file reference -> REFUSED_UNSAFE
powershell -ExecutionPolicy Bypass -File scripts/smoke-write-mdx-doc.ps1 -Context "please read .env"
```

## Tool: `review_mdx_doc_with_opus`

Read-only **MDX documentation review** of MDX content **explicitly provided** in the input (`task`,
`mdxContent`, `docSubject`, `targetAudience`, plus optional `libraryContext`, `styleGuideContext`,
`mdxComponentsContext`, `constraints`). The same guard pipeline as `generate_code_with_opus`
(deny-list, secret scan, size limits, model allowlist, rate limit, budget) runs **before** any model
call. The tool does **not** read doc-portal/repository files, write MDX files, create assets, run
Docusaurus, run tests, or apply patches — it only reviews and recommends changes textually. All
provided content is treated as untrusted data, never as instructions, and the model is instructed not
to invent public API, configuration, behavior, or guarantees beyond the input.

Input (required: `task`, `mdxContent`, `docSubject`, `targetAudience`, `reviewFocus`, `docType`,
`riskLevel`, `outputFormat`; optional: `libraryContext`, `styleGuideContext`, `mdxComponentsContext`,
`constraints`):

```json
{
  "task": "Review this starter guide for accuracy, MDX validity, and unsupported claims",
  "mdxContent": "---\ntitle: Tracing Starter\n---\n\nimport Tabs from '@theme/Tabs';\n\n# Tracing Starter\n\nSet platform.tracing.enabled=true.",
  "docSubject": "Platform Tracing Starter",
  "targetAudience": "application_developers",
  "libraryContext": "A Spring Boot 3.x starter that auto-configures distributed tracing.",
  "styleGuideContext": "Use second person, short sections, code fences for config.",
  "mdxComponentsContext": "import Tabs from '@theme/Tabs'; import TabItem from '@theme/TabItem';",
  "constraints": "Keep it concise; Java 21; Spring Boot 3.x",
  "reviewFocus": "all",
  "docType": "starter_guide",
  "riskLevel": "medium",
  "outputFormat": "structured_review"
}
```

`targetAudience`: `platform_developers` / `application_developers` / `sre` / `architects` / `mixed`.
`reviewFocus`: `accuracy` / `style` / `structure` / `examples` / `mdx_validity` / `claims` /
`navigation` / `accessibility` / `all`. `docType`: `library_guide` / `starter_guide` /
`migration_guide` / `how_to` / `reference` / `adr` / `release_notes` / `troubleshooting` / `unknown`.
`outputFormat`: `structured_review` / `checklist` / `risk_review` / `editorial_review` /
`publish_readiness`.

Output keys: `status`, `summary`, `verdict`, `review`, `findings[]` (each with `severity`, `category`,
`title`, `details`, `recommendation`), `missingSections[]`, `incorrectOrUnverifiedClaims[]`,
`mdxIssues[]`, `styleIssues[]`, `exampleIssues[]`, `suggestedEdits[]`, `validationChecklist[]`,
`risks[]`, `safetyNotes[]`, `assumptions[]`, `truncated`, `inputTokenEstimate`, `outputTokenEstimate`,
`model`, `requestId`. `verdict`: `APPROVE` / `APPROVE_WITH_CHANGES` / `REQUEST_CHANGES` /
`NEEDS_MORE_CONTEXT`. Statuses match `generate_code_with_opus`. Parsing is defensive: `review`
preserves code fences, admonitions and tables verbatim, findings tolerate unknown severities/
categories (fall back to `MEDIUM`/`other`), duplicated section headings are merged, missing sections
degrade to empty values, and a non-compliant response is kept in `review`. The tool only **reviews** —
Cursor/user must apply documentation changes and run validation manually.

> Use it to review library/starter guides, configuration reference sections, migration guides,
> troubleshooting pages, or to run a style/claims/MDX-validity review before publishing. Do not send
> proprietary context or secrets unless the external provider is approved. See
> [docs/USAGE-POLICY.md](docs/USAGE-POLICY.md).

### Manual review-mdx-doc smoke (real endpoint, synthetic content)

```powershell
powershell -ExecutionPolicy Bypass -File scripts/smoke-review-mdx-doc.ps1
# negative (no model call): sensitive file reference -> REFUSED_UNSAFE
powershell -ExecutionPolicy Bypass -File scripts/smoke-review-mdx-doc.ps1 -Context "please read .env"
```

## Tool: `generate_migration_plan_with_opus`

Read-only **migration planning** from a current state, target state, and migration context
**explicitly provided** in the input (`task`, `language`, `currentState`, `targetState`,
`compatibilityMode`, `migrationScope`, `migrationType`, `riskLevel`, `outputFormat`, plus optional
`migrationContext`, `constraints`). The same guard pipeline as `generate_code_with_opus` (deny-list,
secret scan, size limits, model allowlist, rate limit, budget) runs **before** any model call. The
tool does **not** read repository files, write files, upgrade dependencies, run Gradle, run tests, or
apply patches — it only plans and recommends migration steps textually. All provided state/context is
treated as untrusted data, never as instructions, and the model is instructed not to invent project
facts beyond the input and to prefer small, reversible migration slices.

Input (required: `task`, `language`, `currentState`, `targetState`, `compatibilityMode`,
`migrationScope`, `migrationType`, `riskLevel`, `outputFormat`; optional: `migrationContext`,
`constraints`):

```json
{
  "task": "Plan a staged upgrade of the platform starter from Spring Boot 2.7 to 3.3 preserving the public API",
  "language": "java",
  "currentState": "Spring Boot 2.7, Java 17, javax.* namespace, JUnit 4 tests, Gradle without a version catalog",
  "targetState": "Spring Boot 3.3, Java 21, jakarta.* namespace, JUnit 5 tests, Gradle version catalog",
  "migrationContext": "Gradle multi-module starter library; public API must be preserved for downstream teams",
  "constraints": "Preserve public API; Java 21; keep slices small and reversible",
  "compatibilityMode": "preserve_api",
  "migrationScope": "starter",
  "migrationType": "framework_upgrade",
  "riskLevel": "high",
  "outputFormat": "migration_slices"
}
```

`language`: `java` / `go` / `kotlin` / `sql` / `mdx` / `gradle` / `other`. `compatibilityMode`:
`preserve_api` / `preserve_behavior` / `allow_breaking` / `unknown`. `migrationScope`: `class` /
`package` / `module` / `multi_module` / `platform` / `library` / `starter` / `documentation` /
`build` / `unknown`. `migrationType`: `framework_upgrade` / `api_migration` / `dependency_upgrade` /
`architecture_migration` / `configuration_migration` / `documentation_migration` / `test_migration` /
`build_migration` / `unknown`. `outputFormat`: `migration_slices` / `checklist` / `risk_matrix` /
`rollout_plan` / `decision_memo`.

Output keys: `status`, `summary`, `migrationOverview`, `migrationSlices[]` (each with `id`, `title`,
`goal`, `changes[]`, `verification[]`, `risk`, `rollback`), `compatibilityNotes[]`, `breakingRisks[]`,
`dependencyChanges[]`, `configurationChanges[]`, `testPlan[]`, `observabilityChecks[]`,
`rolloutPlan[]`, `rollbackPlan[]`, `docsUpdates[]`, `openQuestions[]`, `risks[]`, `safetyNotes[]`,
`assumptions[]`, `truncated`, `inputTokenEstimate`, `outputTokenEstimate`, `model`, `requestId`.
Statuses match `generate_code_with_opus`. Parsing is defensive: slices tolerate unknown risk values
(fall back to `MEDIUM`), `changes`/`verification` are split on commas/semicolons, `migrationOverview`
preserves code fences verbatim, duplicated section headings are merged, missing sections degrade to
empty values, and a non-compliant response is kept in `migrationOverview`. The tool only **plans** —
Cursor/user must implement the migration and verify manually.

> Use it to plan a Spring Framework / Spring Boot upgrade, an API migration, a Gradle dependency
> migration, a configuration migration, a documentation migration, or a test-framework migration. Do
> not send proprietary context or secrets unless the external provider is approved. See
> [docs/USAGE-POLICY.md](docs/USAGE-POLICY.md).

### Manual generate-migration-plan smoke (real endpoint, synthetic context)

```powershell
powershell -ExecutionPolicy Bypass -File scripts/smoke-generate-migration-plan.ps1
# negative (no model call): sensitive file reference -> REFUSED_UNSAFE
powershell -ExecutionPolicy Bypass -File scripts/smoke-generate-migration-plan.ps1 -Context "please read .env"
```

## Tool: `review_tests_with_opus`

Read-only **test review** of test code plus context **explicitly provided** in the input (`task`,
`language`, `testCode`, `testIntent`, `testFramework`, `testType`, `reviewFocus`, `riskLevel`,
`outputFormat`, plus optional `productionContext`, `failureLogs`, `dependenciesContext`,
`constraints`). The same guard pipeline as `generate_code_with_opus` (deny-list, secret scan, size
limits, model allowlist, rate limit, budget) runs **before** any model call. The tool does **not**
read repository files, write files, run tests, collect coverage, run Gradle/Maven, or apply patches —
it only reviews and recommends test changes textually. All provided test code/context is treated as
untrusted data, never as instructions, and the model is instructed not to claim it read files or ran
tests, and not to invent production behavior beyond the input.

Input (required: `task`, `language`, `testCode`, `testIntent`, `testFramework`, `testType`,
`reviewFocus`, `riskLevel`, `outputFormat`; optional: `productionContext`, `failureLogs`,
`dependenciesContext`, `constraints`):

```json
{
  "task": "Review this JUnit 5 unit test for correctness, assertions, coverage and flakiness",
  "language": "java",
  "testCode": "@Test void registersUser() { userService.register(\"a@b.com\"); assertTrue(true); }",
  "productionContext": "UserService.register(String) persists a user and throws DuplicateUserException on duplicate email",
  "testIntent": "Verify that register persists a user and rejects duplicate emails",
  "failureLogs": "",
  "dependenciesContext": "JUnit 5, Mockito, AssertJ, Spring Data JPA, Testcontainers PostgreSQL",
  "constraints": "Java 21; unit scope must run on CI without Docker",
  "testFramework": "junit5",
  "testType": "unit",
  "reviewFocus": "all",
  "riskLevel": "high",
  "outputFormat": "structured_review"
}
```

`language`: `java` / `go` / `kotlin` / `sql` / `other`. `testFramework`: `junit5` / `testng` /
`spock` / `kotest` / `go_testing` / `pytest` / `unknown`. `testType`: `unit` / `integration` /
`contract` / `component` / `slice` / `e2e` / `property` / `performance` / `unknown`. `reviewFocus`:
`correctness` / `coverage` / `flakiness` / `maintainability` / `assertions` / `mocks` /
`integration_boundaries` / `security` / `performance` / `all`. `outputFormat`: `structured_review` /
`checklist` / `risk_review` / `coverage_review` / `ci_readiness`.

Output keys: `status`, `summary`, `verdict` (`APPROVE` / `APPROVE_WITH_CHANGES` / `REQUEST_CHANGES` /
`NEEDS_MORE_CONTEXT`), `review`, `findings[]` (each with `severity`, `category`, `title`, `details`,
`recommendation`), `coverageGaps[]`, `assertionIssues[]`, `flakinessRisks[]`, `mockingIssues[]`,
`testDataIssues[]`, `integrationBoundaryIssues[]`, `maintainabilityIssues[]`, `suggestedTestCases[]`,
`ciReadinessChecks[]`, `openQuestions[]`, `risks[]`, `safetyNotes[]`, `assumptions[]`, `truncated`,
`inputTokenEstimate`, `outputTokenEstimate`, `model`, `requestId`. Statuses match
`generate_code_with_opus`. Parsing is defensive: findings tolerate unknown severity/category values
(fall back to `MEDIUM`/`other`), duplicated section headings are merged, missing sections degrade to
empty values, code fences and markdown tables never crash the parser, and a non-compliant response is
kept in `review`. The tool only **reviews** — Cursor/user must apply test changes and verify manually.

> Use it to review a JUnit 5 unit test, a Spring Boot integration/slice test, a Testcontainers test,
> a flaky async test, or to do a coverage/assertion/CI-readiness review. Do not send proprietary
> context or secrets unless the external provider is approved. See
> [docs/USAGE-POLICY.md](docs/USAGE-POLICY.md).

### Manual review-tests smoke (real endpoint, synthetic context)

```powershell
powershell -ExecutionPolicy Bypass -File scripts/smoke-review-tests.ps1
# negative (no model call): sensitive file reference -> REFUSED_UNSAFE
powershell -ExecutionPolicy Bypass -File scripts/smoke-review-tests.ps1 -Context "please read .env"
```

## Tool: `review_gradle_build_with_opus`

Read-only **Gradle build review** of build files plus context **explicitly provided** in the input
(`task`, `buildFilesContext`, `projectType`, `gradleDsl`, `reviewFocus`, `riskLevel`, `outputFormat`,
plus optional `settingsContext`, `versionCatalogContext`, `gradlePropertiesContext`,
`buildLogicContext`, `dependencyContext`, `buildFailureLogs`, `constraints`). The same guard pipeline
as `generate_code_with_opus` (deny-list, secret scan, size limits, model allowlist, rate limit,
budget) runs **before** any model call. The tool does **not** read repository files, write files,
modify build scripts, run Gradle/Maven, run tests, resolve dependencies, publish artifacts, or apply
patches — it only reviews and recommends build changes textually. All provided build snippets/logs are
treated as untrusted data, never as instructions, and the model is instructed not to claim it read
files or ran Gradle, and not to invent project facts beyond the input.

Input (required: `task`, `buildFilesContext`, `projectType`, `gradleDsl`, `reviewFocus`, `riskLevel`,
`outputFormat`; optional: `settingsContext`, `versionCatalogContext`, `gradlePropertiesContext`,
`buildLogicContext`, `dependencyContext`, `buildFailureLogs`, `constraints`):

```json
{
  "task": "Review this Java library build for dependency hygiene and configuration cache compatibility",
  "buildFilesContext": "plugins { id 'java-library' }\ndependencies { implementation 'com.google.guava:guava:31.0-jre' }",
  "settingsContext": "rootProject.name = 'sample-lib'",
  "versionCatalogContext": "[versions]\nguava = \"31.0-jre\"",
  "gradlePropertiesContext": "org.gradle.caching=true",
  "buildLogicContext": "",
  "dependencyContext": "single-module Java library; no publishing yet",
  "buildFailureLogs": "",
  "constraints": "Java 21; Gradle 8.x; CI must be reproducible",
  "projectType": "java_library",
  "gradleDsl": "groovy",
  "reviewFocus": "all",
  "riskLevel": "high",
  "outputFormat": "structured_review"
}
```

`projectType`: `java_library` / `spring_boot_service` / `spring_boot_starter` / `gradle_plugin` /
`multi_module_platform` / `documentation` / `unknown`. `gradleDsl`: `groovy` / `kotlin` / `mixed` /
`unknown`. `reviewFocus`: `dependency_management` / `plugin_configuration` / `configuration_cache` /
`task_graph` / `multi_module_governance` / `test_setup` / `publishing` / `performance` / `security` /
`all`. `outputFormat`: `structured_review` / `checklist` / `risk_review` / `build_health` /
`migration_review`.

Output keys: `status`, `summary`, `verdict` (`APPROVE` / `APPROVE_WITH_CHANGES` / `REQUEST_CHANGES` /
`NEEDS_MORE_CONTEXT`), `review`, `findings[]` (each with `severity`, `category`, `title`, `details`,
`recommendation`), `configurationCacheIssues[]`, `dependencyIssues[]`, `pluginIssues[]`,
`taskGraphIssues[]`, `multiModuleIssues[]`, `testSetupIssues[]`, `publishingIssues[]`,
`performanceIssues[]`, `securityIssues[]`, `compatibilityRisks[]`, `recommendedChecks[]`,
`suggestedChanges[]`, `openQuestions[]`, `risks[]`, `safetyNotes[]`, `assumptions[]`, `truncated`,
`inputTokenEstimate`, `outputTokenEstimate`, `model`, `requestId`. Statuses match
`generate_code_with_opus`. Parsing is defensive: findings tolerate unknown severity/category values
(fall back to `MEDIUM`/`other`), duplicated section headings are merged, missing sections degrade to
empty values, code fences and markdown tables never crash the parser, and a non-compliant response is
kept in `review`. The tool only **reviews** — Cursor/user must apply build changes and verify manually.

> Use it to review a Groovy or Kotlin DSL `build.gradle(.kts)`, a multi-module `settings.gradle`, a
> `libs.versions.toml` version catalog, `gradle.properties`, a buildSrc/convention plugin, a Spring
> Boot starter build, a Gradle plugin project, a publishing setup, or a Gradle build failure log. Do
> not send proprietary context or secrets unless the external provider is approved. See
> [docs/USAGE-POLICY.md](docs/USAGE-POLICY.md).

### Manual review-gradle-build smoke (real endpoint, synthetic context)

```powershell
powershell -ExecutionPolicy Bypass -File scripts/smoke-review-gradle-build.ps1
# negative (no model call): sensitive file reference -> REFUSED_UNSAFE
powershell -ExecutionPolicy Bypass -File scripts/smoke-review-gradle-build.ps1 -Context "please read .env"
```

### Manual Phase 2 smoke (real endpoint, synthetic context)

```powershell
$env:OPUS_API_KEY="..."                        # OS env only
$env:OPUS_BASE_URL="https://api.cheat-ai.shop"
$env:OPUS_MODEL="claude-opus-4-8"
./gradlew shadowJar
powershell -ExecutionPolicy Bypass -File scripts/smoke-generate-code.ps1
```

Expected: `tools/call` response with `"status":"OK"`, `model=claude-opus-4-8`, Java code in `result`. No files modified, no repository context sent, API key not printed, budget counters / audit metadata updated.

### Manual negative safety smoke (no model call)

These must be refused locally without contacting the provider:

```powershell
# Secret material in context -> REFUSED_UNSAFE
powershell -ExecutionPolicy Bypass -File scripts/smoke-generate-code.ps1 -Context "-----BEGIN PRIVATE KEY-----"

# Sensitive file reference in context -> REFUSED_UNSAFE
powershell -ExecutionPolicy Bypass -File scripts/smoke-generate-code.ps1 -Context "please read .env"
```

Expected: `tools/call` response with `"status":"REFUSED_UNSAFE"` and no secret echoed.

### Manual Phase 0A connectivity (no Opus API)

```powershell
powershell -ExecutionPolicy Bypass -File scripts/smoke-stdio.ps1
```

### Phase 0B endpoint smoke (standalone, not MCP tool)

```powershell
java -cp build/libs/opus-mcp-server-0.1.0-SNAPSHOT-all.jar `
  space.br1440.platform.devtools.opusmcp.smoke.OpusEndpointSmokeCheck
```

On a non-2xx response this prints diagnostics: `statusDescription`, a safe (masked, length-capped)
`errorBodyPreview`, `server`/`cfRay` headers, and a `diagnosticCategory`
(e.g. Cloudflare `502` → `PROVIDER_DOWN`).

### Provider health smoke (diagnostics only, not MCP tool)

Distinguishes provider/gateway failures from MCP logic. Sends only the synthetic prompt
`Reply with exactly: OK` (no repository context); never prints the API key.

```powershell
powershell -ExecutionPolicy Bypass -File scripts/smoke-provider-health.ps1 -Models "claude-opus-4-8,custom-opus-4-8"
```

Per model it prints `statusCode`, `statusDescription`, safe headers (`Date`, `Server`, `CF-RAY`,
`Content-Type`, `Content-Length`, `Retry-After`), a masked `errorBodyPreview`, and a
`diagnosticCategory`. Categories: `OK`, `RESPONSE_PARSE_ERROR`, `AUTH_ERROR`, `REQUEST_SHAPE_ERROR`,
`MODEL_ROUTE_DOWN`, `RATE_LIMIT_OR_QUOTA`, `NETWORK_ERROR`, `PROVIDER_DOWN`, `UNKNOWN_PROVIDER_ERROR`.
See [docs/OPERATIONS.md](docs/OPERATIONS.md) → "Provider returns HTTP 502 / Cloudflare Bad Gateway".

## Perplexity provider spike (Phase 8A — diagnostics only)

An **isolated** provider compatibility spike (`PerplexityEndpointSmokeCheck`) verifies that a
Perplexity-compatible endpoint can be called from Java. As of **Phase 8B**, the read-only
`research_with_perplexity` MCP tool (see above) builds on this spike and **is** registered with the
MCP server. This section documents the standalone diagnostic spike class, which remains a
manual-only connectivity check (not an MCP tool).

- Lives in its own package (`...opusmcp.perplexity`), fully separate from the Anthropic
  `/v1/messages` client — Perplexity uses the OpenAI-compatible `/chat/completions` shape with
  `Authorization: Bearer`.
- Configuration is environment-only:

| Variable | Required | Default |
|----------|----------|---------|
| `PERPLEXITY_API_KEY` | for the live spike | — |
| `PERPLEXITY_BASE_URL` | optional | `https://api.perplexity.ai` |
| `PERPLEXITY_MODEL` | optional | `sonar-deep-research` |

- The API key is read only from the environment, sent as a Bearer token, and **never logged**; error
  previews mask the literal key and generic secret shapes.
- The smoke sends **only** a synthetic prompt (`Reply with exactly: OK`) — no repository context, no
  code, no secrets. It never reads/writes files or runs commands.
- Intended future use (only if Phase 8B proceeds): public web research / current docs / best
  practices — **not** for proprietary code unless the provider is approved.

Manual spike (requires `PERPLEXITY_API_KEY`; build the fat-jar first):

```powershell
.\gradlew.bat shadowJar
powershell -ExecutionPolicy Bypass -File scripts/smoke-perplexity-provider-health.ps1
# or directly:
java -cp build/libs/opus-mcp-server-0.1.0-SNAPSHOT-all.jar `
  space.br1440.platform.devtools.opusmcp.perplexity.PerplexityEndpointSmokeCheck
```

It prints `ok`, `statusCode`, `statusDescription`, `model`, `requestId`, a capped text preview, and a
`diagnosticCategory` (`OK`, `RESPONSE_PARSE_ERROR`, `AUTH_ERROR`, `REQUEST_SHAPE_ERROR`,
`MODEL_NOT_FOUND`, `RATE_LIMIT_OR_QUOTA`, `NETWORK_ERROR`, `PROVIDER_DOWN`,
`UNKNOWN_PROVIDER_ERROR`). If `PERPLEXITY_API_KEY` is unset, the spike exits without a network call.

## Not implemented (Phase 4+)

Additional MCP tools (`architecture_review_with_opus`, `summarize_pr_with_opus`, etc.),
HTTP/SSE transport,
Spring Boot, remote multi-user mode, auth server, file writes, repository reads, shell execution,
automatic patch apply, automatic test execution, background generation, database persistence,
external metrics, and audit **content** logging (metadata-only in Phase 3).

## Notes

- Standalone project — not part of parent `platform-tracing` multi-module build.
- Gradle Groovy DSL, no `libs.versions.toml`.
- MCP Java SDK `2.0.0`; stdout = JSON-RPC only, logs on stderr.
