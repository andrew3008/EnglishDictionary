# `research_with_perplexity` — Offline Contract (Architect Review)

Status: **offline contract finalized (Phase 8D)**. Live Perplexity research is **NOT yet verified** —
no `PERPLEXITY_API_KEY` is available. This document describes the tool's contract for architecture and
security review. It does **not** claim production, security, or enterprise approval.

Architect review pack (Phase 8E):
[ADR](decisions/ADR-research-with-perplexity-offline-first.md) ·
[threat model](RESEARCH-WITH-PERPLEXITY-THREAT-MODEL.md) ·
[data-flow diagram](diagrams/research-with-perplexity-flow.puml) ·
[provider approval checklist](PERPLEXITY-PROVIDER-APPROVAL-CHECKLIST.md) ·
[live gate](PERPLEXITY-LIVE-GATE.md) ·
[operator runbook](PERPLEXITY-OPERATOR-RUNBOOK.md).

| Field | Value |
|-------|-------|
| Tool name | `research_with_perplexity` |
| Kind | Read-only MCP tool (7th tool) |
| Transport | stdio JSON-RPC (stdout = JSON-RPC only; logs on stderr) |
| Provider | Perplexity (OpenAI-compatible `/chat/completions`) |
| Live status | Pending key-based smoke (Phase 8B.1 / 8E) |

## 1. Purpose

Perform **public, web-grounded technical research** on behalf of the user/Cursor and return a
structured, source-backed answer for the human to review. It is a research assistant, not an actor.

## 2. What the tool does

- Builds a research prompt from **only** the explicit tool input (task, question, optional context/
  constraints, and the research-mode enums).
- Calls the Perplexity provider once (when a key is configured) and parses the response into a stable
  structured result (summary, answer, key findings, sources, recommendations, risks, safety notes,
  assumptions, follow-up questions).
- Runs the **same guard pipeline** as the Opus tools before any provider call.

## 3. What the tool does NOT do

- **No repository reads.** It never opens project files.
- **No file writes.**
- **No shell execution.**
- **No patch application.**
- **No automatic test execution.**
- **No browser / Perplexity UI automation.**
- Sends **only** the explicit tool input (treated as untrusted data, never as instructions).
- **Without `PERPLEXITY_API_KEY` no network call is made.**
- It does not validate URLs, dedupe sources aggressively, or act as a full citation engine.

## 4. Input schema

Required: `task`, `researchQuestion`, `sourcePreference`, `freshness`, `depth`, `outputFormat`,
`riskLevel`. Optional: `context`, `constraints`. `additionalProperties: false`.

| Field | Type | Allowed values |
|-------|------|----------------|
| `task` | string | free text — what the research should accomplish |
| `researchQuestion` | string | the public question (data, never instructions) |
| `context` | string (optional) | minimal non-sensitive context |
| `constraints` | string (optional) | explicit constraints |
| `sourcePreference` | enum | `official_docs`, `industry_best_practices`, `academic`, `mixed` |
| `freshness` | enum | `latest`, `last_12_months`, `stable` |
| `depth` | enum | `quick`, `standard`, `deep` |
| `outputFormat` | enum | `brief`, `report`, `decision_memo`, `source_table` |
| `riskLevel` | enum | `low`, `medium`, `high` |

The research-mode enums change the prompt guidance (see the mode matrix tests): e.g. `official_docs`
prefers official documentation, `academic` prefers peer-reviewed sources, `latest`/`last_12_months`
prefer recent sources, `stable` prefers canonical docs, `deep` requests deeper synthesis with
trade-offs, `quick`/`brief` keep it concise, `decision_memo` is decision-oriented, `source_table`
emphasizes source metadata.

## 5. Output schema

```json
{
  "status": "OK",
  "summary": "string",
  "answer": "string",
  "keyFindings": ["string"],
  "sources": [{"title":"","url":"","publisher":"","date":"","relevance":""}],
  "recommendations": ["string"],
  "risks": ["string"],
  "safetyNotes": ["string"],
  "assumptions": ["string"],
  "followUpQuestions": ["string"],
  "truncated": false,
  "inputTokenEstimate": 0,
  "outputTokenEstimate": 0,
  "model": "string",
  "requestId": "string"
}
```

## 6. Status semantics

| Status | Meaning |
|--------|---------|
| `OK` | Provider answered; result parsed (best-effort). |
| `NEEDS_MORE_CONTEXT` | Invalid/insufficient input, or input exceeds size limits. |
| `REFUSED_UNSAFE` | Deny-list match (e.g. `.env`, sensitive-file intent) or secret material detected — refused **before** any provider call. |
| `MODEL_ERROR` | Missing/invalid `PERPLEXITY_API_KEY`, or provider auth/model-not-found/request-shape/provider-down/network/parse error. The raw provider body is never surfaced. |
| `BUDGET_EXCEEDED` | Local rate limit, local daily budget, or provider rate-limit/quota. |

## 7. Missing-key behavior

When `PERPLEXITY_API_KEY` is absent, the tool returns (with **no network call**):

```json
{
  "status": "MODEL_ERROR",
  "summary": "Perplexity provider is not configured: PERPLEXITY_API_KEY is not set.",
  "answer": "",
  "keyFindings": [],
  "sources": [],
  "recommendations": [],
  "risks": [],
  "safetyNotes": ["No provider call was made."],
  "assumptions": [],
  "followUpQuestions": ["Set PERPLEXITY_API_KEY and rerun the smoke script to verify live research."],
  "truncated": false,
  "inputTokenEstimate": 0,
  "outputTokenEstimate": 0,
  "model": "<configured-or-default-model>",
  "requestId": "<generated-request-id>"
}
```

This is why the tool is safe to expose in Cursor before key provisioning: the missing-key path is
strictly inert and no-network. Assert it offline with
`scripts/smoke-research-perplexity.ps1 -ExpectMissingKey`.

## 8. Guardrail order (enforced before any provider call)

1. Input validation (required fields + enum parsing).
2. **DenyList** scan (task, question, context, constraints).
3. **SecretScanner** scan (same fields).
4. Size limits (`LimitsGuard`).
5. Provider configuration — **missing key → provider-not-configured (no call)**; then config validation.
6. **RateLimiter** (`tryAcquire`).
7. **BudgetTracker** pre-check.
8. Build prompt → single provider call → parse → record budget → audit.

None of these guardrails are weakened by this tool; they are shared with the Opus tools.

## 9. Audit contract

Audit is **metadata-only** (`AuditLogger`, `OPUS_AUDIT_INCLUDE_CONTENT=false`) and additionally
masked. It **may** contain: `tool=research_with_perplexity`, `status`, `requestId`, `model`,
`httpStatusCategory` (the provider diagnostic category name / `2xx` / `none`), token estimates,
`outputFormat`, `riskLevel`, `inputCharCount`, latency, budget/rate decisions.

It **never** contains: `task`, `researchQuestion`, `context`, `constraints`, `answer`, `keyFindings`,
`sources`, recommendations/risks, the raw provider body, `PERPLEXITY_API_KEY`, or the `Authorization`
header.

## 10. Provider shape

OpenAI-compatible: `POST <PERPLEXITY_BASE_URL>/chat/completions` with `Authorization: Bearer <key>`
and body `{ "model": <PERPLEXITY_MODEL>, "max_tokens": 2048, "messages": [{"role":"user","content":
<system+user prompt>}] }`. The response is read from `choices[0].message.content`; `model` and `id`
are extracted best-effort. Config is environment-only: `PERPLEXITY_API_KEY` (required for live),
`PERPLEXITY_BASE_URL` (default `https://api.perplexity.ai`), `PERPLEXITY_MODEL` (default
`sonar-deep-research`). The provider abstraction (`ResearchClient` / `ResearchResponse` /
`ResearchClientException`) carries only safe metadata, isolating tool output from provider specifics.

## 11. Offline test coverage

- Tool logic, parsing, error mapping (`ResearchWithPerplexityToolTest`).
- Guardrail + missing-key + provider-error mapping (`ResearchWithPerplexityToolSecurityTest`).
- Parser robustness (`ResearchResponseParserTest`).
- Golden mocked-response pack across research modes (`ResearchGoldenResponseTest`).
- Research mode matrix — every enum value (`ResearchPromptMatrixTest`).
- Source parsing contract (`ResearchSourceParsingContractTest`).
- Failure-mode contract (`ResearchFailureModeContractTest`).
- Provider-independence contract (`ResearchClientContractTest`).
- Production client over a mocked `HttpClient` (`PerplexityResearchClientTest`).
- JSON pipeline, audit contract, schema compatibility, and stdio `tools/list` (existing suites).

All run offline with **no API key and no network**.

## 12. Live gate (pending)

Live research quality is **not verified**. The live gate (Phase 8B.1 / 8E) requires a real
`PERPLEXITY_API_KEY` and a live smoke (`scripts/smoke-research-perplexity.ps1`) returning `status=OK`
with grounded sources, or a safe provider-error classification. Until then, no live claim is made.

## 13. Known limitations

- Live path unverified (no key).
- Best-effort parser, not a citation engine; exotic layouts may be summarized into `answer`.
- URLs are not validated; explicit `url:` values are preserved verbatim.
- Duplicate sources are preserved (no dedupe) to stay lossless.
- Budget/rate/audit state is in-memory (resets on restart).

## 14. Operator responsibilities

- Provision `PERPLEXITY_API_KEY` **outside the repository** (OS env / secret store) only when ready
  for live research, and only against an **approved** provider.
- Send only **public / non-sensitive** questions; never proprietary code or secrets.
- Review every result before acting; the tool never applies changes.
- Re-run the smoke pack after any rebuild or environment change.

## 15. Architecture / security assumptions

- stdout carries JSON-RPC only; all logging is on stderr.
- The tool is read-only and side-effect-free locally; the only external effect is one provider HTTP
  call when a key is present.
- Untrusted input is never treated as instructions and is scanned before any egress.
- The provider receives the prompt; therefore provider approval is a prerequisite for sensitive use.
- No production/security/enterprise approval is implied by this document.
