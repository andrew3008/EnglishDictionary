# Perplexity Operator Runbook — `research_with_perplexity`

Day-to-day operator guide for the read-only research tool. Live research is **gated**
(see [PERPLEXITY-LIVE-GATE.md](PERPLEXITY-LIVE-GATE.md)); without a key the tool is safe and inert.
This runbook does not imply production/security/enterprise approval.

Related: [OPERATOR-ADOPTION.md](OPERATOR-ADOPTION.md),
[RESEARCH-WITH-PERPLEXITY-CONTRACT.md](RESEARCH-WITH-PERPLEXITY-CONTRACT.md),
[PERPLEXITY-PROVIDER-APPROVAL-CHECKLIST.md](PERPLEXITY-PROVIDER-APPROVAL-CHECKLIST.md).

## 1. Configure environment (only when approved for live use)

Set these **outside the repository** (OS environment / secret store), never in files committed to git:

```powershell
# Required for live research (omit to keep the safe no-network MODEL_ERROR path):
$env:PERPLEXITY_API_KEY = "<your-key-from-the-official-console>"
# Optional (defaults shown):
$env:PERPLEXITY_BASE_URL = "https://api.perplexity.ai"
$env:PERPLEXITY_MODEL    = "sonar-deep-research"
```

Never echo the key (`Write-Output $env:PERPLEXITY_API_KEY` is forbidden in shared logs). The tool reads
the key from the environment only and never prints it.

## 2. Run the missing-key smoke (no key, no network)

```powershell
Remove-Item Env:\PERPLEXITY_API_KEY -ErrorAction SilentlyContinue
powershell -ExecutionPolicy Bypass -File scripts/smoke-research-perplexity.ps1 -ExpectMissingKey
```

Expected: `status=MODEL_ERROR`, summary mentions `PERPLEXITY_API_KEY is not set`, and
`ASSERT OK: missing-key contract satisfied (MODEL_ERROR, no network call)`.

## 3. Run the live smoke (only with an approved key)

```powershell
# Provider connectivity:
powershell -ExecutionPolicy Bypass -File scripts/smoke-perplexity-provider-health.ps1
# End-to-end research (synthetic public question only):
powershell -ExecutionPolicy Bypass -File scripts/smoke-research-perplexity.ps1
```

Follow [PERPLEXITY-LIVE-GATE.md](PERPLEXITY-LIVE-GATE.md) for the full acceptance set.

## 4. Interpret statuses

| Status | Meaning | Operator action |
|--------|---------|-----------------|
| `OK` | Provider answered; result parsed | Review answer + verify sources before acting |
| `NEEDS_MORE_CONTEXT` | Invalid input or size limits | Fix the request input |
| `REFUSED_UNSAFE` | Secret/deny-list match (no provider call) | Remove secrets/sensitive file refs from input |
| `MODEL_ERROR` | Missing key, config, or provider error (auth/model/shape/down/network/parse) | Check key/config; capture `requestId`; retry later |
| `BUDGET_EXCEEDED` | Local rate/budget or provider rate-limit/quota | Wait / adjust budget; do not loop |

## 5. What NOT to send

- No proprietary code, secrets, credentials, or private keys.
- No internal-only data unless the provider is approved for it.
- Only public / non-sensitive questions until provider approval is complete.

## 6. Collect a requestId for diagnostics

Every result includes a `requestId` (and the smoke script prints it). When reporting an issue, share
the `requestId`, `status`, `model`, and the provider diagnostic category — **never** the API key, the
prompt content, or the raw provider body.

## 7. Rollback / disable (keep Opus tools running)

In order of preference:

1. **Unset `PERPLEXITY_API_KEY`** (`Remove-Item Env:\PERPLEXITY_API_KEY`) → the tool returns the safe
   no-network `MODEL_ERROR` path; all six Opus tools keep working.
2. Disable `research_with_perplexity` in the Cursor MCP UI if supported.
3. Revert Cursor `mcp.json` to a previous fat-jar that predates the tool.
4. Remove the tool from a dedicated MCP config only if a separate server config exists.

Disabling the research tool never affects the Opus tools; the server still exposes the other six.
