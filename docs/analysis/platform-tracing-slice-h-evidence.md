# Platform Tracing Slice H Scope Ledger and Evidence

> Branch: `feature/slice-h-bounded-remote-service-mirror`
> Base: `master` at `a8e10cf46588b45e536ad601467a85fc69eeda70`
> Date: 2026-07-21
> Status: `SLICE H IMPLEMENTATION COMPLETE`; `SLICE H VERIFICATION GREEN`;
> `SLICE H COMMITTED/PUSHED`; `SLICE H PR CREATION BLOCKED (GitHub integration 403)`;
> `SLICE H NOT YET MERGED`
> `CP-1 REQUIRED`; `SLICE M BLOCKED`; `RG-CONTROLLED-AGENT OPEN`;
> `PRODUCTION ROLLOUT FORBIDDEN`

## Authoritative scope ledger

| Item | Requirement | Repository evidence before Slice H | Result |
|---|---|---|---|
| ALIGN-10 / RISK-06 | Bound mirror by cardinality and TTL | Process-global unbounded `ConcurrentHashMap`; green characterization retained 4096 entries | Strict maximum of 4096 entries and five-minute expire-after-write TTL |
| Ownership | One lifecycle owner | Producer and resolver called static mirror methods directly | Within each class identity only `RemoteServiceMdc` owns and accesses the package-private mirror; ArchUnit enforces the dependency boundary |
| Cancellation / exception | Cleanup at web execution boundary | Production filters already used `finally` / Reactor `doFinally`, without direct cancellation and exceptional-path proof | Servlet exception and WebFlux cancellation tests prove mirror and MDC cleanup |
| Shutdown | No retained state or lifecycle leak | Mirror had no close contract and no bound | `close()` clears state and rejects later writes; implementation creates no thread, scheduler or external resource |
| Known defect | Flip characterization and remove tag | `RemoteServiceTraceMirrorCharacterizationTest` and `UNBOUNDED_REMOTE_SERVICE_MIRROR` described the open defect | Characterization deleted, bounded behavior tests added, defect ID removed |

## Design result

- `RemoteServiceTraceMirror` remains package-private and has no public ABI or configuration delta.
- Ownership is unique per class identity. Slice H does not share mutable state between the isolated
  application and Agent extension classloaders and does not alter their established boundary.
- The store uses insertion order, synchronized compound operations and deterministic oldest-entry
  eviction. Updating an existing trace ID moves it to the newest TTL position.
- Expiry is lazy on read/write/size and stops at the first non-expired entry. No background cleanup
  thread is introduced, so application shutdown does not acquire a new scheduler lifecycle.
- Internal defaults are `4096` entries and five minutes. They are implementation safety limits,
  not published configuration contracts. A later configuration surface requires separate evidence
  and architecture approval.
- Existing Servlet `finally` and Reactor `doFinally` cleanup remain the execution-boundary owners.
  Slice H adds proof for exceptional and cancellation paths without changing filter production code.
- No dependency, BOM, starter metadata, OpenTelemetry composition or classloader ownership changed.

## Tests

`RemoteServiceTraceMirrorTest` proves:

- exact cardinality bound and oldest-entry eviction;
- expire-after-write TTL and updated-entry ordering;
- strict bound under 2048 concurrent writes;
- close/clear behavior and rejection of writes after close;
- rejection of invalid internal bounds.

Web integration proves Servlet exception cleanup and WebFlux cancellation cleanup. Existing
`RemoteServiceMdcTest`, `RemoteServiceNameResolverTest` and all module tests remain green.

## Verification

```powershell
.\gradlew.bat :platform-tracing-core:test `
  :platform-tracing-autoconfigure-webmvc:test `
  :platform-tracing-autoconfigure-webflux:test --no-daemon

.\gradlew.bat pr4ArchitectureFitnessVerify pr1ModuleTaxonomyVerify build --no-daemon

git diff --check
```

Results:

- affected-module run: 92 suites, 527 tests, 0 failures, 0 errors, 0 skipped;
- architecture fitness and module taxonomy: GREEN;
- full build: `BUILD SUCCESSFUL`, 128 tasks;
- `knownDefectTest`: GREEN after removal of ALIGN-10 defect tag;
- `git diff --check`: GREEN;
- no new wildcard imports or dependencies.

The aggregate `:platform-tracing-e2e-tests:test` task remained `SKIPPED` behind its existing opt-in
gate. Slice H changes an internal bounded store and its already-existing web cleanup paths; no Agent,
Collector, SDK composition or cross-classloader behavior changed. The Windows collector-config
validation also printed its known non-fatal Docker bind-mount warning; the full build remained green.

Javadoc emitted three pre-existing warnings: two invalid `@param` tags on the enum
`KnownDefectId` and one unresolved test-architecture reference. They are not introduced by Slice H
and are not hidden by this evidence.

## Gate result

`SLICE H IMPLEMENTATION COMPLETE`; `SLICE H VERIFICATION GREEN`;
`SLICE H COMMITTED/PUSHED`; `SLICE H PR CREATION BLOCKED (GitHub integration 403)`;
`SLICE H NOT YET MERGED`. The publication URL is
`https://github.com/andrew3008/EnglishDictionary/compare/master...feature/slice-h-bounded-remote-service-mirror?expand=1`.

Slice H does not approve any identity decision. `CP-1(a-d,f)` remains mandatory and Slice M remains
NO-GO until Slice H is merged and CP-1 is explicitly approved. Slice G remains independently blocked
by CP-2 clarification.

`RG-CONTROLLED-AGENT OPEN`; `PRODUCTION ROLLOUT FORBIDDEN`.
