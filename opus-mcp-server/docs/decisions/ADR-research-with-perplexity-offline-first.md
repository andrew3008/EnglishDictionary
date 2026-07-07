# ADR: `research_with_perplexity` — Offline-First Read-Only Research Tool

- **Status:** Accepted for offline implementation; **live gate pending** (no `PERPLEXITY_API_KEY`).
- **Date:** 2026-06-29
- **Deciders:** architecture owner, security owner, DevTools/operator owner (approvals pending — see
  [PERPLEXITY-PROVIDER-APPROVAL-CHECKLIST.md](../PERPLEXITY-PROVIDER-APPROVAL-CHECKLIST.md)).
- **Scope:** `opus-mcp-server` MCP tool `research_with_perplexity` only. No change to the six Opus
  tools.

This ADR does **not** claim production, security, or enterprise approval.

## Context

`opus-mcp-server` is a local, read-only Java MCP server for Cursor (stdio JSON-RPC). Engineers want
public, web-grounded research (current docs, best practices, source-backed decisions) without leaving
Cursor. Perplexity exposes an OpenAI-compatible `/chat/completions` endpoint suitable for this.

Constraints at decision time:

- `PERPLEXITY_API_KEY` is **not available**, so the provider cannot be exercised live.
- The server must remain read-only and side-effect-free locally (no repo reads, file writes, shell,
  or patch/test execution).
- The shared guardrails (SecretScanner, DenyList, BudgetTracker, RateLimiter, RetryPolicy,
  AuditLogger, Masking, stdout-JSON-RPC-only) must not be weakened.

## Decision

Expose `research_with_perplexity` as a **read-only MCP tool with safe missing-key behavior**:

- It uses **only** the explicit tool input (treated as untrusted data, never instructions).
- It runs the **same guard pipeline** as the Opus tools **before** any provider call.
- When `PERPLEXITY_API_KEY` is absent it returns a structured `MODEL_ERROR`
  (provider-not-configured) **with no network call**.
- **Live Perplexity research remains disabled/unverified until `PERPLEXITY_API_KEY` is provisioned
  and the live smoke passes** (see [PERPLEXITY-LIVE-GATE.md](../PERPLEXITY-LIVE-GATE.md)).

The full behavioral contract is in
[RESEARCH-WITH-PERPLEXITY-CONTRACT.md](../RESEARCH-WITH-PERPLEXITY-CONTRACT.md).

## Alternatives considered

1. **Do not add a research tool.** Rejected: high recurring value; engineers otherwise context-switch
   to a browser, increasing the chance of pasting proprietary content into unmanaged tools.
2. **Wait for the API key before any implementation.** Rejected: the offline-first design lets us land
   guardrails, parsing, schema, and tests safely now; only the live quality gate is deferred.
3. **Use the existing Anthropic/Opus client for research.** Rejected: different API shape and intent;
   reuse would blur the read-only research boundary and the provider isolation.
4. **Add a generic multi-provider research abstraction now.** Rejected as scope creep; a single
   provider-neutral `ResearchClient` interface is enough and avoids speculative generality.

## Consequences

Positive:

- Safe to expose in Cursor today: the missing-key path is strictly no-network.
- Guardrails, parser robustness, schema, and failure modes are covered by offline tests.
- Provider specifics are isolated behind `ResearchClient` / `ResearchResponse` /
  `ResearchClientException`.

Negative / costs:

- Live research **quality is unverified** until the key-based smoke runs.
- When a key is present, the provider receives the prompt; therefore provider approval is a
  prerequisite for any sensitive use.
- In-memory budget/rate/audit state resets on restart (no persistence).

## Security boundary

- stdout carries JSON-RPC only; all logging is on stderr; audit is metadata-only.
- Untrusted input is scanned (SecretScanner + DenyList) before any egress and is never treated as
  instructions.
- The only external effect is a single provider HTTPS call **when a key is present**.
- The raw provider body and the API key never appear in tool output or audit logs.
- Full analysis: [RESEARCH-WITH-PERPLEXITY-THREAT-MODEL.md](../RESEARCH-WITH-PERPLEXITY-THREAT-MODEL.md).

## Live gate dependency

This decision is **conditionally accepted**. Live use is gated on:

1. Provider approval checklist signed off.
2. Live smoke acceptance checklist passed with a real `PERPLEXITY_API_KEY`.

Until both complete, the tool is offline-only and must be used with public/non-sensitive questions.

## Rollback strategy

The tool can be neutralized without removing the MCP server (Opus tools keep running):

1. **Unset `PERPLEXITY_API_KEY`** → forces the no-network `MODEL_ERROR` path.
2. Disable the tool in the Cursor MCP UI if supported.
3. Revert to a previous fat-jar that predates the tool.
4. Remove the tool from a dedicated MCP config only if a separate server config exists.

Details: [PERPLEXITY-OPERATOR-RUNBOOK.md](../PERPLEXITY-OPERATOR-RUNBOOK.md) → "Rollback / disable".
