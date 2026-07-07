# Release Candidate Readiness

Concise gate for declaring `opus-mcp-server` a **release candidate** for local operator adoption.
This is *release candidate readiness*, **not** a production certification. RC is ready only when every
item below is actually checked. For the broader operator release flow see [RELEASE.md](RELEASE.md);
for adoption steps see [OPERATOR-ADOPTION.md](OPERATOR-ADOPTION.md); for the `research_with_perplexity`
offline contract see
[RESEARCH-WITH-PERPLEXITY-CONTRACT.md](RESEARCH-WITH-PERPLEXITY-CONTRACT.md). The Phase 8E architect
review pack covers the path to live use:
[ADR](decisions/ADR-research-with-perplexity-offline-first.md),
[threat model](RESEARCH-WITH-PERPLEXITY-THREAT-MODEL.md),
[provider approval checklist](PERPLEXITY-PROVIDER-APPROVAL-CHECKLIST.md),
[live gate](PERPLEXITY-LIVE-GATE.md), [operator runbook](PERPLEXITY-OPERATOR-RUNBOOK.md). For
`research_with_perplexity`: **offline contract ready; live gate pending; provider approval pending;
not production/security/enterprise approved; safe to expose without a key because the missing-key path
is no-network.**

## Offline gate (no network, no `OPUS_API_KEY`)

`releasePackageCheck` is the single offline gate that aggregates these checks:

```powershell
cd E:\Platform_Traces\opus-mcp-server
.\gradlew.bat clean test
.\gradlew.bat verifyLocal
.\gradlew.bat releaseCheck
.\gradlew.bat releasePackageCheck
```

- [ ] `clean test` passed (no real API, no network)
- [ ] `verifyLocal` passed (test + fat-jar)
- [ ] `releaseCheck` passed
- [ ] `securityHandoff` passed (dependency inventory generated; inventory ≠ CVE verdict)
- [ ] `releasePackageCheck` passed (the final offline gate)
- [ ] release manifest generated (`build/distributions/release-manifest.json`)
- [ ] SHA-256 checksum generated and matches the fat-jar
- [ ] docs secret hygiene passed (`DocsSecretHygieneTest`)
- [ ] `docs/cursor-mcp.example.json` is valid JSON with no API key
- [ ] all fifteen tools present in `tools/list` and in the release manifest:
      `echo_mcp_connection`, `generate_code_with_opus`, `review_code_with_opus`,
      `generate_tests_with_opus`, `refactor_plan_with_opus`, `explain_diff_with_opus`,
      `research_with_perplexity`, `analyze_build_failure_with_opus`,
      `design_class_hierarchy_with_opus`, `review_architecture_with_opus`,
      `write_mdx_doc_with_opus`, `review_mdx_doc_with_opus`,
      `generate_migration_plan_with_opus`, `review_tests_with_opus`,
      `review_gradle_build_with_opus`

## Live gate (operator-only, requires credentials)

Run before adoption when `OPUS_API_KEY` / `OPUS_BASE_URL` / `OPUS_MODEL` are available
(see [OPERATOR-ADOPTION.md](OPERATOR-ADOPTION.md) → "Smoke pack"):

- [ ] positive smoke passed for all thirteen Opus-backed tools (`status=OK`), including
      `scripts/smoke-analyze-build-failure.ps1`, `scripts/smoke-design-class-hierarchy.ps1`,
      `scripts/smoke-review-architecture.ps1`, `scripts/smoke-write-mdx-doc.ps1`,
      `scripts/smoke-review-mdx-doc.ps1`, `scripts/smoke-generate-migration-plan.ps1`,
      `scripts/smoke-review-tests.ps1`, and `scripts/smoke-review-gradle-build.ps1`
- [ ] negative `.env` smoke passed (`REFUSED_UNSAFE`, model not called)
- [ ] negative private-key smoke passed at least once (`REFUSED_UNSAFE`, secret not echoed)
- [ ] `research_with_perplexity` missing-key smoke passed (`MODEL_ERROR`, no network call) — works
      without any key; assert offline with
      `scripts/smoke-research-perplexity.ps1 -ExpectMissingKey`
- [ ] `research_with_perplexity` offline hardening (Phase 8C) — parser robustness, prompt
      public-source/no-secret/no-auto-patch constraints, guardrail-before-provider, and audit
      metadata-only contract covered by tests (no key/network required)
- [ ] `research_with_perplexity` live smoke (Phase 8B.1 / 8D) — PENDING until `PERPLEXITY_API_KEY` is
      available; run with `scripts/smoke-research-perplexity.ps1`. Live research is **not verified**
      until this passes.

## Security & operational posture

- [ ] `OPUS_API_KEY` stored outside the repository (OS env / secret store), never in `mcp.json`
- [ ] audit content disabled (`OPUS_AUDIT_INCLUDE_CONTENT=false`; metadata-only audit)
- [ ] external provider risk acknowledged (provider receives prompts; see [USAGE-POLICY.md](USAGE-POLICY.md))
- [ ] no real secrets in docs/scripts/build files (negative-smoke markers are fake/documented only)
- [ ] rollback procedure documented and previous release retained
      (see [RELEASE.md](RELEASE.md) → "Rolling back")

## Verdict

Declare **Release candidate readiness** only when the offline gate is fully checked and, before actual
adoption, the live gate is run by the operator. Do not mark RC if any item is unchecked, and do not
describe the build as "production certified".
