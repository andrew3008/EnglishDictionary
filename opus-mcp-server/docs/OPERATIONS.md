# Operations Guide (Windows)

Operational setup, environment variables, smoke checklist, troubleshooting, and readiness checklist
for the `opus-mcp-server` stdio MCP server. See also [USAGE-POLICY.md](USAGE-POLICY.md) for the
Cursor usage policy and provider security warning.

The server is **stdio-only**: stdout carries MCP JSON-RPC, all logs go to stderr/file. It never reads
repository files, writes files, or runs commands.

---

## 1. Windows setup

```powershell
cd E:\Platform_Traces\opus-mcp-server
.\gradlew.bat clean test
.\gradlew.bat shadowJar
# -> build\libs\opus-mcp-server-0.1.0-SNAPSHOT-all.jar
```

Set environment variables in your **local shell / OS environment / secret store** — never commit them:

```powershell
$env:OPUS_API_KEY="<set-in-os-environment>"      # never committed, never pasted into mcp.json
$env:OPUS_BASE_URL="https://api.cheat-ai.shop"
$env:OPUS_MODEL="claude-opus-4-8"
```

Notes:

- PowerShell `$env:` variables are **process-scoped** unless made permanent
  (e.g. `setx OPUS_BASE_URL "https://api.cheat-ai.shop"`, which affects new shells only; avoid
  `setx OPUS_API_KEY` on shared machines).
- **Do not commit secrets.**
- **Do not paste API keys into the Cursor MCP config** (`mcp.json`). The key is read from the OS
  environment of the process Cursor launches.

---

## 2. Connect to Cursor

Use [`cursor-mcp.example.json`](cursor-mcp.example.json) as a template. Each developer must adjust the
**absolute jar path** for their machine.

```json
{
  "mcpServers": {
    "java-opus-mcp": {
      "command": "java",
      "args": [
        "-jar",
        "<absolute-path-to-jar>/opus-mcp-server-0.1.0-SNAPSHOT-all.jar"
      ],
      "env": {
        "OPUS_BASE_URL": "https://api.cheat-ai.shop",
        "OPUS_MODEL": "claude-opus-4-8"
      }
    }
  }
}
```

- `OPUS_API_KEY` is intentionally **absent** — set it via OS environment / secret store.
- The example path `E:/Platform_Traces/opus-mcp-server/build/libs/...` must be changed per developer.

---

## 3. Environment variable reference

All values are validated and clamped to safe caps at startup; invalid/negative values fall back to
defaults. Limits set to `0` are **disabled**.

| Variable | Required | Default | Purpose / safe recommendation | Example |
|----------|----------|---------|-------------------------------|---------|
| `OPUS_API_KEY` | Yes (for generation) | — | Provider API key. **OS env / secret store only; never logged.** | `<set-in-os-environment>` |
| `OPUS_BASE_URL` | Yes (for generation) | — | Provider base URL (no `/v1/messages` suffix). Must be valid http(s). | `https://api.cheat-ai.shop` |
| `OPUS_MODEL` | Optional | `claude-opus-4-8` | Model id; must be allowlisted (`claude-opus-4-8`, `custom-opus-4-8`). | `claude-opus-4-8` |
| `OPUS_MAX_TOKENS` | Optional | `4096` (cap `200000`) | Max output tokens requested. Keep modest to control cost. | `4096` |
| `OPUS_REQUEST_TIMEOUT_SECONDS` | Optional | `60` (cap `600`) | Per-request timeout. | `60` |
| `OPUS_MAX_CONTEXT_CHARS` | Optional | `32000` (cap `2000000`) | Max `context` size; larger → `NEEDS_MORE_CONTEXT`. Keep small. | `32000` |
| `OPUS_MAX_CONSTRAINTS_CHARS` | Optional | `8000` (cap `1000000`) | Max `constraints` size. | `8000` |
| `OPUS_MAX_OUTPUT_CHARS` | Optional | `64000` (cap `4000000`) | Output truncation ceiling. | `64000` |
| `OPUS_DAILY_REQUEST_LIMIT` | Optional | `0` (disabled) | Max requests/day; exceed → `BUDGET_EXCEEDED`. Recommended to set. | `200` |
| `OPUS_DAILY_INPUT_CHAR_LIMIT` | Optional | `0` (disabled) | Max input chars/day. | `2000000` |
| `OPUS_DAILY_ESTIMATED_TOKEN_LIMIT` | Optional | `0` (disabled) | Max estimated input tokens/day. | `1000000` |
| `OPUS_DAILY_COST_LIMIT` | Optional | `0` (disabled) | Daily cost cap; needs price vars below. | `5.0` |
| `OPUS_PRICE_PER_1K_INPUT_TOKENS` | Optional | `0` | For local cost accounting only. | `0.0` |
| `OPUS_PRICE_PER_1K_OUTPUT_TOKENS` | Optional | `0` | For local cost accounting only. | `0.0` |
| `OPUS_REQUESTS_PER_MINUTE` | Optional | `0` (disabled) | Sliding-window rate limit; exceed → `BUDGET_EXCEEDED`. Recommended to set. | `20` |
| `OPUS_RETRY_MAX_ATTEMPTS` | Optional | `3` (cap `10`) | Total attempts incl. first; transient failures only. | `3` |
| `OPUS_RETRY_BASE_DELAY_MS` | Optional | `200` | Exponential backoff base. | `200` |
| `OPUS_RETRY_MAX_DELAY_MS` | Optional | `2000` (cap `120000`) | Backoff ceiling. | `2000` |
| `OPUS_AUDIT_INCLUDE_CONTENT` | Optional | `false` | **Keep `false`.** Content logging is not supported; audit stays metadata-only. | `false` |

Audit (stderr/file) records **metadata only**: requestId, timestamp, tool, model, language,
outputFormat, riskLevel, status, latency, input char count, estimated tokens/cost, budget/rate
decisions, HTTP status category. It never records `task`, `context`, `constraints`, model output, the
API key, or the raw provider response.

---

## 4. Smoke checklist

### Build + positive smoke

```powershell
cd E:\Platform_Traces\opus-mcp-server
.\gradlew.bat clean test
.\gradlew.bat shadowJar

$env:OPUS_API_KEY="<set-in-os-environment>"
$env:OPUS_BASE_URL="https://api.cheat-ai.shop"
$env:OPUS_MODEL="claude-opus-4-8"

powershell -ExecutionPolicy Bypass -File scripts/smoke-generate-code.ps1
```

Expected:

- [ ] `initialize` returns OK
- [ ] `tools/list` includes `echo_mcp_connection` and `generate_code_with_opus`
- [ ] `generate_code_with_opus` returns `status=OK`
- [ ] `model=claude-opus-4-8`
- [ ] `summary` is human-readable (not a code fence, not empty)
- [ ] `result` contains only the RESULT body / code block
- [ ] no files modified
- [ ] API key not printed

### Negative smoke 1 — private key

```powershell
powershell -ExecutionPolicy Bypass -File scripts/smoke-generate-code.ps1 -Context "-----BEGIN PRIVATE KEY-----"
```

Expected:

- [ ] `status=REFUSED_UNSAFE`
- [ ] reason mentions a private key block
- [ ] external model call blocked
- [ ] secret not echoed

### Negative smoke 2 — `.env` reference

```powershell
powershell -ExecutionPolicy Bypass -File scripts/smoke-generate-code.ps1 -Context "please read .env"
```

Expected:

- [ ] `status=REFUSED_UNSAFE`
- [ ] reason mentions `.env` file reference
- [ ] external model call blocked

### Connectivity-only smoke (no provider API)

```powershell
powershell -ExecutionPolicy Bypass -File scripts/smoke-stdio.ps1
```

---

## 5. Troubleshooting

| Symptom | Likely cause | Diagnostic | Fix |
|---------|--------------|------------|-----|
| Cursor does not see the MCP server | Bad `mcp.json`, wrong command, or jar path | Check Cursor MCP logs; `Test-Path <jar>` | Fix `command`/`args` path; rebuild jar; restart Cursor |
| `tools/list` missing `generate_code_with_opus` | Old jar / stale build | Run `scripts/smoke-stdio.ps1` and inspect `tools/list` | `.\gradlew.bat shadowJar`; repoint `mcp.json` to fresh jar |
| stdout has logs and JSON-RPC breaks | Something writing to stdout instead of stderr | Inspect raw stdout from a smoke run | Ensure no extra `-D` logging to stdout; logs are stderr by design — don't redirect them to stdout |
| `java not found` | Java not installed / not on PATH | `java -version` | Install Java 21; add to PATH (or use absolute `java` path in `command`) |
| Wrong jar path | Path differs per machine | `Test-Path "<absolute-path-to-jar>"` | Update `args` path in `mcp.json` |
| Fat-jar missing | `shadowJar` not run | `Test-Path build\libs\opus-mcp-server-0.1.0-SNAPSHOT-all.jar` | `.\gradlew.bat shadowJar` |
| `MODEL_ERROR: Missing OPUS_API_KEY` | Key not in process env | `echo $env:OPUS_API_KEY` (presence only) | Set `OPUS_API_KEY` in the shell/OS env that launches the server |
| `MODEL_ERROR` re: invalid base URL | `OPUS_BASE_URL` empty or not http(s) | `echo $env:OPUS_BASE_URL` | Set to `https://api.cheat-ai.shop` (no `/v1/messages` suffix) |
| `MODEL_ERROR` (401/403) | Bad/expired key or no access | `Invoke-WebRequest https://api.cheat-ai.shop/v1/whoami -Headers @{ "x-api-key" = $env:OPUS_API_KEY }` | Rotate/fix the key; confirm provider access |
| `MODEL_ERROR` (404) | Wrong base URL (path duplicated) | Confirm URL has **no** `/v1/messages` suffix | Set `OPUS_BASE_URL` to host root only |
| `BUDGET_EXCEEDED` (429 from provider) | Provider rate/quota limit | Check audit `httpStatusCategory=4xx` | Wait/retry later; the client already bounded-retries 429 |
| `BUDGET_EXCEEDED` (local) | Local rate/budget limit hit | Check audit `rateLimitDecision`/`budgetDecision` | Raise `OPUS_REQUESTS_PER_MINUTE` / `OPUS_DAILY_*` or wait for daily reset |
| `REFUSED_UNSAFE` | SecretScanner/DenyList matched the input | Check the returned `summary` reason | Remove secrets / sensitive file references from `context` |
| `MODEL_ERROR` (timeout/5xx after retries) | Transient provider/network failure | Re-run smoke; check network | Retry; increase `OPUS_REQUEST_TIMEOUT_SECONDS` if needed |
| PowerShell "running scripts is disabled" | Execution policy | — | Run with `-ExecutionPolicy Bypass` as shown, or set policy for the session |
| Mojibake (e.g. `ΓÇö`) in output | Console not UTF-8 | — | The smoke script already forces UTF-8; ensure terminal supports it |

---

## 5.1 Provider returns HTTP 502 / Cloudflare Bad Gateway

**Symptom.** Positive live calls fail with `MODEL_ERROR` and the underlying HTTP status is `502`
(often with `Server: cloudflare` and a `CF-RAY` header, body `error code: 502`). The same `502` is
seen by `generate_code_with_opus`, `review_code_with_opus`, and the isolated
`OpusEndpointSmokeCheck`, and by a direct PowerShell/curl call to `<OPUS_BASE_URL>/v1/messages`.

**Meaning.** `502 Bad Gateway` is returned by the edge/gateway (Cloudflare), not by this MCP server.
Classified as `PROVIDER_DOWN`. The same family applies to `500/503/504`.

**What it proves.**

- The request reached the provider's edge and the **upstream/gateway/origin** failed to respond
  successfully. This is a **provider/gateway/upstream** problem, not MCP server logic.
- It is reproducible with a synthetic prompt and no repository context, so it is not input-specific.

**What it does NOT prove.**

- It does **not** prove which upstream component failed (do not overclaim exact root cause).
- It does **not** indicate a bad API key (that would be `401/403` → `AUTH_ERROR`).
- It does **not** indicate a rate/quota issue (that would be `429` → `RATE_LIMIT_OR_QUOTA`).
- It does **not** indicate a request-shape problem (that would be `400` → `REQUEST_SHAPE_ERROR`).

**Commands to run** (diagnostics only — no config or runtime change):

```powershell
cd E:\Platform_Traces\opus-mcp-server
.\gradlew.bat shadowJar

# Isolated endpoint smoke (single model from OPUS_MODEL):
java -cp build\libs\opus-mcp-server-0.1.0-SNAPSHOT-all.jar `
  space.br1440.platform.devtools.opusmcp.smoke.OpusEndpointSmokeCheck

# Provider health smoke across one or more models:
powershell -ExecutionPolicy Bypass -File scripts/smoke-provider-health.ps1 `
  -Models "claude-opus-4-8,custom-opus-4-8"
```

Both print `statusCode`, `statusDescription`, a safe `errorBodyPreview`, selected headers
(`server`, `cfRay`/`CF-RAY`, `Date`, `Content-Type`, `Content-Length`, `Retry-After`), and a
`diagnosticCategory`.

**Diagnostic categories** (from `ProviderDiagnostics`): `OK`, `RESPONSE_PARSE_ERROR`, `AUTH_ERROR`,
`REQUEST_SHAPE_ERROR`, `MODEL_ROUTE_DOWN`, `RATE_LIMIT_OR_QUOTA`, `NETWORK_ERROR`, `PROVIDER_DOWN`,
`UNKNOWN_PROVIDER_ERROR`.

**Evidence safe to send the provider:**

- status code (`502`) and status description (`Bad Gateway`)
- `CF-RAY` value and `Date` header
- model name (e.g. `claude-opus-4-8`)
- base URL **hostname** (e.g. `api.cheat-ai.shop`)
- response body preview (e.g. `error code: 502`)
- the synthetic prompt used (`Reply with exactly: OK`)

**Never send the provider** (or paste into tickets/chats):

- `OPUS_API_KEY`, `Authorization` / `x-api-key` header values
- proprietary code or repository context
- full request headers or secrets of any kind

**Resolution.** This is an external incident. Wait for provider recovery, escalate to the provider
with the safe evidence above, then re-run the positive live smokes. Do **not** add automatic failover
or provider switching here (out of scope for this diagnostics slice).

---

## 6. Operational readiness checklist

- [ ] fat-jar built (`build\libs\opus-mcp-server-0.1.0-SNAPSHOT-all.jar`)
- [ ] tests pass (`.\gradlew.bat clean test`)
- [ ] positive smoke passes
- [ ] negative private-key smoke passes (`REFUSED_UNSAFE`)
- [ ] negative `.env` smoke passes (`REFUSED_UNSAFE`)
- [ ] Cursor `mcp.json` configured with correct jar path
- [ ] `OPUS_API_KEY` stored outside the repository (OS env / secret store)
- [ ] `OPUS_BASE_URL` set
- [ ] `OPUS_MODEL` set to `claude-opus-4-8`
- [ ] daily budget configured (`OPUS_DAILY_*`)
- [ ] requests per minute configured (`OPUS_REQUESTS_PER_MINUTE`)
- [ ] audit content disabled (`OPUS_AUDIT_INCLUDE_CONTENT=false`)
- [ ] external provider risk acknowledged (see [USAGE-POLICY.md](USAGE-POLICY.md))

---

## 7. Docs secret hygiene

Documentation and examples use safe placeholders only and must never contain real secrets:

- `<set-in-os-environment>` for the API key
- `<absolute-path-to-jar>` for machine-specific paths
- `https://api.cheat-ai.shop` (public endpoint) and `claude-opus-4-8` (model id)

Never commit real API keys, tokens, bearer values, credentials, private keys, or proprietary source.
