# Perplexity Live Gate — Acceptance Checklist

The live gate that must pass **after** a real `PERPLEXITY_API_KEY` is provisioned and the
[provider approval checklist](PERPLEXITY-PROVIDER-APPROVAL-CHECKLIST.md) is signed off. Until then,
live Perplexity research is **not verified** and must not be claimed. Offline (missing-key) behavior is
already verified and requires no key.

Prerequisites:

- Provider approval checklist complete.
- `PERPLEXITY_API_KEY` set **outside the repository** (OS env / secret store).
- Fresh fat-jar built (`.\gradlew.bat shadowJar`).
- Only **synthetic public** questions are used during the gate (no proprietary content).

## Required live tests (run with key present)

| # | Test | How | Expected status |
|---|------|-----|-----------------|
| 1 | Provider health smoke | `scripts/smoke-perplexity-provider-health.ps1` | OK or safe provider classification |
| 2 | Positive public question | `scripts/smoke-research-perplexity.ps1` | `OK` with grounded answer + sources |
| 3 | `sourcePreference=official_docs` | research call with `official_docs` | `OK`; official-doc-leaning sources |
| 4 | `freshness=latest` | research call with `latest` | `OK`; recent sources / dates surfaced |
| 5 | `outputFormat=source_table` | research call with `source_table` | `OK`; source-metadata emphasis |
| 6 | Missing-key regression | unset key, `scripts/smoke-research-perplexity.ps1 -ExpectMissingKey` | `MODEL_ERROR`, no network call |
| 7 | `.env` negative guardrail | research call referencing `.env` | `REFUSED_UNSAFE` (no provider call) |
| 8 | Private-key negative guardrail | research call with a PEM private-key block in context | `REFUSED_UNSAFE` (secret not echoed) |
| 9 | Rate/budget guardrail | exercise rate/budget (offline/mocked acceptable) | `BUDGET_EXCEEDED` before provider |
| 10 | Audit no-leak check | inspect stderr/audit after the runs | metadata only; no content, key, or raw body |

## Status legend

- **OK** — positive tests succeeded.
- **MODEL_ERROR** / safe provider classification — provider/config issues (auth, model-not-found,
  request-shape, provider-down, network, parse).
- **REFUSED_UNSAFE** — secret/deny-list tests refused before any provider call.
- **BUDGET_EXCEEDED** — local rate/budget or provider rate-limit/quota.

## Exit criteria

- Tests 1–5 pass with `OK` (or a justified, safe provider classification with a clear reason).
- Tests 6–10 demonstrate the guardrail/audit contract holds with a key present.
- The API key never appears in any console/log output.

## After a passing gate

Only **after** the gate passes:

- Update [RC-1-STATUS.md](RC-1-STATUS.md), [RELEASE-CANDIDATE.md](RELEASE-CANDIDATE.md), and
  [RESEARCH-WITH-PERPLEXITY-CONTRACT.md](RESEARCH-WITH-PERPLEXITY-CONTRACT.md) to record live
  verification (with date and the model used).
- Do **not** claim production/security/enterprise approval from this gate alone.

Until this gate passes, keep using only public/non-sensitive questions.
