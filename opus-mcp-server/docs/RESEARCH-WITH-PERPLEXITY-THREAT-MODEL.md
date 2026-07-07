# Threat Model — `research_with_perplexity`

Scope: the read-only MCP tool `research_with_perplexity` in `opus-mcp-server`. Offline-first; live
provider use is gated (see [PERPLEXITY-LIVE-GATE.md](PERPLEXITY-LIVE-GATE.md)). This document supports
architecture/security review and does **not** assert production/security/enterprise approval.

Related: [RESEARCH-WITH-PERPLEXITY-CONTRACT.md](RESEARCH-WITH-PERPLEXITY-CONTRACT.md),
[decisions/ADR-research-with-perplexity-offline-first.md](decisions/ADR-research-with-perplexity-offline-first.md),
[diagrams/research-with-perplexity-flow.puml](diagrams/research-with-perplexity-flow.puml).

## Assets

| Asset | Sensitivity | Notes |
|-------|-------------|-------|
| `PERPLEXITY_API_KEY` | High | Provider credential; env-only; never logged. |
| Explicit research question/context | Medium | Supplied by Cursor/user; treated as untrusted data. |
| Proprietary text accidentally pasted by the user | High | Must not reach the provider; user responsibility + guardrails reduce risk. |
| Provider responses | Medium | May contain inaccurate/hallucinated content or citations. |
| Audit logs | Medium | Metadata-only; must not contain content or secrets. |
| Cursor workspace integrity | High | Tool must not read/write the repo or run commands. |

## Trust boundaries

1. **Cursor/User → local MCP (stdio)** — JSON-RPC over stdio on the local machine.
2. **MCP server → external Perplexity provider** — HTTPS egress (only when a key is present).
3. **Local environment variables** — hold the API key and provider config.
4. **Audit/log files** — local stderr/log sink.

## Data-flow (summary)

See the diagram in
[diagrams/research-with-perplexity-flow.puml](diagrams/research-with-perplexity-flow.puml). Textual
flow:

```
Cursor/User
  -> MCP stdio JSON-RPC (tools/call: research_with_perplexity)
  -> input validation
  -> DenyList scan -> SecretScanner scan -> size limits   [refuse before egress]
  -> [PERPLEXITY_API_KEY missing? -> MODEL_ERROR, NO network call] 
  -> RateLimiter -> BudgetTracker pre-check
  -> build prompt (explicit input only)
  -> Perplexity HTTP client (Bearer auth) -> external provider (HTTPS)
  -> parse response (best-effort) -> truncate -> structured result
  -> metadata-only audit (no content, no key, no raw body)
  -> JSON-RPC result back to Cursor
```

## Threats and mitigations

| # | Threat | Vector | Mitigation | Residual risk |
|---|--------|--------|------------|---------------|
| T1 | Secret exfiltration | Secret pasted into question/context | SecretScanner refuses (`REFUSED_UNSAFE`) before egress; key is env-only and never sent as content | User can still send non-pattern secrets → operator policy |
| T2 | Proprietary context leakage | User pastes proprietary code | Explicit-input-only; no repo reads; usage policy = public/non-sensitive until provider approved | Human discipline required |
| T3 | Prompt injection in user context | Malicious instructions in context | Context treated as DATA, never instructions; system prompt states this | Provider may still be influenced → outputs are advisory only |
| T4 | Malicious/provider response content | Provider returns harmful/misleading text | Output is read-only proposal for human review; tool never executes or applies anything | Human review required |
| T5 | Raw provider error-body leakage | Error path surfaces body | Errors mapped to safe category messages; raw body never returned | Low |
| T6 | API key leakage | Logs/diagnostics echo key | Key env-only; masked diagnostics; metadata-only audit; never in output | Low |
| T7 | Excessive token/cost usage | Large/looping requests | Size limits, RateLimiter, BudgetTracker pre-check; single call per invocation; capped max tokens | Provider-side cost still applies → billing limits |
| T8 | Provider outage / 5xx | Provider down | Classified as `PROVIDER_DOWN` → `MODEL_ERROR`; no retry storm | Transient unavailability |
| T9 | Misleading sources / hallucinated citations | Model fabricates sources | Sources are best-effort and surfaced for human verification; prompt requests uncertainty | Human must verify sources |
| T10 | Accidental live use before approval | Key set prematurely | Live gate + approval checklist; docs state pending; rollback = unset key | Process/governance |
| T11 | False sense of security from offline tests | Offline pass mistaken for live assurance | Docs explicitly state live is unverified; status banners across docs | Reviewer awareness |
| T12 | URL/citation spoofing in answer | Embedded links | URLs not auto-followed; tool never navigates; operator reviews | Human review |

## Mitigation inventory (controls in place)

- Explicit input only; **no** repo reads, file writes, shell execution, or patch/test execution.
- **SecretScanner** + **DenyList** scan all input fields before any egress.
- **Missing-key path is strictly no-network** (`MODEL_ERROR`).
- **Masked** provider diagnostics; raw body never surfaced.
- **Metadata-only audit** (`OPUS_AUDIT_INCLUDE_CONTENT=false`).
- **RateLimiter** + **BudgetTracker** + capped output tokens.
- stdout JSON-RPC only; logs on stderr.
- Live gate + provider approval checklist; public/non-sensitive usage until provider approval.

## Out of scope

Network-layer controls (TLS interception, egress proxy/allowlist), OS-level secret storage, and
corporate DLP are environment responsibilities, not implemented by this tool.
