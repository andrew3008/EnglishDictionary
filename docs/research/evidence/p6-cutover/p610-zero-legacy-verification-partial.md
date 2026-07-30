# P6.10 — Zero-legacy verification (PARTIAL)

**Date:** 2026-07-29  
**Task:** `p6-zero-legacy-verification`  
**Verdict:** PARTIAL — production scans green; formal P6 PASS still blocked

## Production scans (this session)

Scanned `src/main` of api/otel/javaagent-extension/spring autoconfigure/servlet/webflux/starter + perf deployables for:

| Check | Result |
|---|---|
| `platform.tracing.scrubbing.enabled` / `.built-in-rules` | 0 in production main |
| `platform.tracing.sampling.drop-paths` / force-record legacy | 0 |
| `platform.tracing.actuator.mutation-enabled` | 0 |
| `RuntimeConfigApplier` | 0 |
| `updateScrubbingPolicy` | 0 |
| `PlatformControlProtocolMBean` | 1 javadoc mention only (registrar migration note) |
| `P6LegacyNameFailFastTest` | PASS (14 banned names) |
| Public `api.control.protocol` / `otel.control.protocol` | deleted (P6.5 residual) |
| Platform domain JMX mutating ops | 0 (P6.6) |
| Actuator `@WriteOperation` | 0 + HTTP 404/405 proof (repo-scoped) |

## Completed cutover slices feeding this gate

- P6.2 private writer + platform read projection
- P6.3 RefreshScope / RuntimeConfigApplier deletion
- P6.4 Actuator write deletion + zero-write proof (repo-scoped)
- P6.5 partial protocol deletion (Agent + public packages)
- P6.6 domain JMX write deletion
- P6.7 ScrubbingPolicyHolder + Spring scrubbing binding
- P6.8 PerfAdmin residual + test/benchmark mutation helpers
- P6.9 deployable name sync

## Still open (blocks formal P6 PASS)

1. **`p5-verification-package` (P5.9)** — still pending; plan precondition for coherent P6 PASS
2. Full Docker / multi-node Actuator HTTP matrix (repo-scoped proof only)
3. Full module suites + published-consumer + E2E Docker gates for `p6-coherent-cutover`
4. Cutover-manifest JSON row status refresh (many historical rows still say deferred)
5. Optional delete of unused `VersionedStateHolder` type (zero consumers)

## Verdict language

Use **PARTIAL** for P6.10 until P5.9 + coherent train gates execute. Do not report P6 PASS from this document alone.
