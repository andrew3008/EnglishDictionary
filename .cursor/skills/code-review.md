# Code Review Standards for the Platform Tracing Solution

## Context

This repository contains an enterprise platform tracing solution for Spring Boot servlet and reactive microservices.

The solution includes:

- `platform-tracing-api`
- `platform-tracing-core`
- Spring Boot auto-configuration
- servlet and WebFlux adapters
- starters
- OpenTelemetry agent/SDK integration
- sampling and scrubbing policies
- runtime control
- JMX/OpenMBean integration
- collector configuration
- architecture fitness rules
- benchmarks and Docker-backed E2E tests

The solution is currently **pre-production**.

Breaking source, binary, package, bean, configuration, wire, SPI, and module changes are allowed when they materially improve:

- correctness
- architecture
- runtime safety
- public API quality
- dependency governance
- privacy
- operability
- testability
- production readiness

Backward compatibility with the current pre-production implementation is **not** a primary review goal.

Do not preserve accidental APIs, aliases, deprecated bridges, dual execution paths, obsolete package structures, unsafe defaults, or stale tests merely because they already exist.

Architects will not accept cosmetic refactoring. A review must distinguish:

- material architectural improvement
- production hardening
- necessary cleanup
- cosmetic churn
- compatibility-only preservation

## Review Mission

The purpose of code review is to verify that a change:

- solves the stated problem
- preserves or improves architecture
- does not create hidden production risk
- has a clear owner and boundary
- provides sufficient executable evidence
- keeps public surface intentional
- remains operable by platform and service teams
- does not hide uncertainty

A review is not complete when the code merely compiles.

A reviewer must be able to answer:

```text
What problem is solved?
Why is this change needed?
Why does this layer own it?
What failure mode is introduced?
What proves the behavior?
What remains unverified?
What is the merge recommendation?
```

## Review Priority

When concerns conflict, review in this order:

1. correctness
2. security and privacy
3. runtime-state safety
4. module and classloader boundaries
5. public API integrity
6. control-plane safety
7. dependency and publication correctness
8. test and E2E evidence
9. operator diagnostics
10. reactive/concurrency correctness
11. performance and allocation behavior
12. maintainability
13. developer ergonomics
14. compatibility with pre-production behavior
15. formatting and cosmetic style

Do not block an architecturally justified breaking change merely because migration requires updating repository callers.

Do block a compatibility shim that preserves an accidental or unsafe API without an approved migration requirement.

## Review Modes

Choose the review mode explicitly.

### Architecture review

Use when a change affects:

- module ownership
- public API/SPI
- package taxonomy
- runtime control
- JMX/classloader boundaries
- dependency contracts
- wire protocols
- Spring starter responsibilities
- production defaults

Output must include:

- decision
- alternatives
- trade-offs
- module boundary
- public surface impact
- ADR requirements
- fitness rules

### Implementation review

Use when architecture is already approved.

Do not reopen accepted decisions unless implementation evidence reveals a contradiction or serious production risk.

Focus on:

- plan compliance
- correctness
- missing cases
- tests
- diagnostics
- regression risk
- code quality

### Post-implementation audit

Use after generated or large-scale implementation.

Treat implementation reports as claims, not evidence.

Verify:

- actual files
- actual call sites
- actual public surface
- actual Gradle results
- actual E2E execution
- actual Git state
- actual docs

### Post-fix audit

Review only closure of known findings unless a fix created an architectural regression.

Do not repeat a full architecture review unnecessarily.

### Security review

Use when trust boundaries, PII, mutation, exporter endpoints, propagation, or credentials are affected.

### Performance review

Use when hot paths, startup, allocation, exporter queues, sampling, or scrubbing performance changes.

Do not use one noisy benchmark as the only acceptance evidence.

## Evidence Standard

Every material claim must be classified as one of:

- `VERIFIED`
- `PARTIALLY_VERIFIED`
- `INSUFFICIENT_EVIDENCE`
- `FALSE_POSITIVE`
- `HISTORICAL_ONLY`

Examples:

```text
VERIFIED:
The test executed with skipped=0 and passed.

INSUFFICIENT_EVIDENCE:
The test source compiled, but the opt-in E2E task was skipped.

FALSE_POSITIVE:
The regex matched `network.protocol.version`, not the removed
`api.control.protocol.version` package.
```

Do not claim:

- tests passed when they were skipped
- PR metadata was verified when GitHub access was unavailable
- runtime behavior was verified from unit tests only
- no callers exist without a repository-wide search
- compatibility is preserved without consumer evidence
- a threat is mitigated solely because documentation says so

Use exact evidence where practical:

- file path
- class/method
- source line
- command
- result
- test count
- commit
- branch
- artifact metadata

## Finding Severity

Use the following severity model.

### P0 — Release / merge blocker

Examples:

- production code does not compile
- test source sets do not compile
- required build or architecture gate fails
- public API exposes an unsafe implementation detail
- invalid control payload can reach apply
- rejected mutation changes state
- PII or secret leak
- required E2E fails or is silently skipped
- incompatible classloader type crosses the boundary
- publication metadata is wrong
- data corruption or partial apply is possible

### P1 — Must fix before merge

Examples:

- missing domain invariant
- unsafe default
- missing negative test for a privileged path
- stale consumer still uses removed API
- duplicate live implementation path
- false SPI / ServiceLoader holder
- missing rollback
- warning register contradicts implementation
- public helper lacks governance
- dependency scope creates runtime trap

### P2 — Should fix before release

Examples:

- documentation drift
- missing golden test
- weak diagnostics
- javadoc warning
- known non-blocking operational gap
- test naming causing scan false positives
- missing bounded metric/documentation detail

### False positive

A scan or review finding that does not represent the intended architecture or code behavior.

Narrow the rule or document the allowed context.

Do not rename unrelated domain concepts solely to satisfy a bad regex unless the rename also improves clarity.

### Insufficient evidence

Use when environment/tooling/runtime execution is unavailable.

Do not convert insufficient evidence into PASS.

## Scope Discipline

A review must protect scope without defending architectural debt.

Reject unrelated changes when they:

- enlarge risk
- make review harder
- mix independent decisions
- change unrelated public contracts
- hide generated files
- add broad formatting/import churn
- modify unrelated baselines
- combine architecture and rollout docs without reason

Allow adjacent fixes when they are necessary to:

- compile the affected module
- remove stale consumers of the changed API
- close a discovered production blocker
- make required verification executable
- repair a dependency/Javadoc classpath contract
- remove a false-positive scan that blocks architecture gates

Any adjacent change must be called out explicitly.

## Git and Change-Scope Review

Before reviewing implementation, inspect:

```powershell
git status --short --branch
git log --oneline -10
git diff --stat
git diff --check
```

For a branch/PR review, inspect:

```powershell
git diff <base>...HEAD --stat
git diff <base>...HEAD
```

Verify:

- correct branch
- clean or intentionally dirty working tree
- untracked artifacts
- unrelated user changes
- generated baseline files
- commit cohesion
- whether remote tip contains the audited fixes

Do not declare a PR merge-ready if fixes exist only in an uncommitted local working tree.

## Architecture Review

Review dependency direction.

Expected principles:

```text
platform-tracing-core -> platform-tracing-api

spring-boot-autoconfigure
    -> api
    -> core

otel-extension
    -> approved api/core boundaries

webmvc and webflux
    -> independently isolated adapters

starters
    -> thin aggregation of intended modules
```

Reject:

- `api -> core`
- `core -> Spring`
- API exposure of JMX/OpenMBean
- API exposure of OTel SDK implementation types
- webmvc -> webflux
- webflux -> servlet
- production -> test/sample/e2e
- cycles solved by changing `implementation` to `api`
- domain validation moved into wire decoding
- Spring auto-configuration owning runtime algorithms

Architecture ownership must be expressed through code, Gradle, and fitness rules.

## Public API Review

For every public type or method, ask:

1. Who is the consumer?
2. Is it application-facing API, external SPI, wire contract, or internal bridge?
3. Is public visibility intentional?
4. Does the name express architectural role?
5. Are invariants enforced?
6. Does it expose third-party types?
7. What is the dependency contract?
8. Is JavaDoc complete?
9. Is thread safety/lifecycle clear?
10. Is a negative architecture guard required?

The default decision for a new type is not public.

Reject:

- speculative public query methods
- public `Impl` types
- public internal schema/validator
- vague helpers/managers
- accidental constructors
- implementation registries
- public types added only to cross subpackages
- API aliases in pre-production
- deprecated bridges without production consumers

Before production, direct removal and repository-wide migration are preferred.

## API Surface Diff

For API-changing PRs, require:

```text
Old public types:
New public types:
Types removed:
Types added:
Methods removed:
Methods added:
Packages changed:
External types exposed:
Configuration keys changed:
Bean names changed:
SPI descriptors changed:
```

Use reflection, ArchUnit, `javap`, API inventory, or artifact inspection where appropriate.

Do not rely only on source diff.

## Naming Review

Names must describe role.

Prefer:

- `TraceOperations`
- `SpanFactory`
- `RuntimePolicyControlHandler`
- `SpanAttributeScrubbingRule`
- `TraceControlHeaderInjector`
- `ActiveTraceContextView`

Challenge:

- `Manager`
- `Helper`
- `Utils`
- `Impl`
- `Base`
- `Common`
- repeated namespace terms
- names based on history rather than responsibility
- names implying broader scope than implementation provides

A rename is not cosmetic when it fixes a misleading contract or domain taxonomy.

A rename is cosmetic when behavior, ownership, and mental model remain unchanged.

## Compatibility Review

Current phase:

- source compatibility is not required
- binary compatibility is not required
- configuration aliases are not required
- bean aliases are not required
- deprecated bridges are not required

Reject automatic compatibility preservation.

Accept breaking changes only when:

- the final contract is materially better
- all repository consumers are migrated
- obsolete paths are deleted
- docs/tests/samples are updated
- architecture guards prevent regression

Do not approve random churn under the label “pre-production”.

## SPI and ServiceLoader Review

A real SPI requires:

- genuine external provider use case
- documented lifecycle
- ordering
- classloader behavior
- failure semantics
- missing/duplicate provider behavior
- descriptor tests
- governance

Reject ServiceLoader for:

- one built-in implementation
- deterministic utilities
- package access workaround
- mandatory safety component
- static holder lookup
- test substitution only

Check:

- old descriptor removed
- new descriptor path exact
- implementation FQNs correct
- source and generated descriptors
- custom test JARs
- classloader visibility
- no bridge/alias remains

## Control Protocol Review

Protect the approved pipeline:

```text
wire payload
    -> structural decode
    -> domain validation
    -> mutation policy
    -> apply/read
```

Verify:

- `schema()` absent
- `validator()` absent
- `READ_SCHEMA` absent
- legacy protocol subpackages absent
- exact public surface
- package-private decoder/schema helpers
- strict unknown-key rejection
- non-string key rejection
- enum wire-value rejection
- immutable decode result
- invalid result has no usable apply payload
- domain rules live in core
- empty mutation rejected
- apply only after all gates
- READ does not mutate
- VALIDATE does not apply
- mutation fail-closed by default
- rejected mutation preserves state/version/source/LKG

A review must distinguish:

- structural violation
- domain violation
- mutation-policy rejection
- apply failure

## Runtime Control Review

Runtime control is privileged.

Review:

- default mutation policy
- configuration property
- property metadata
- startup diagnostics
- result status
- audit metadata
- state preservation
- rollback
- read/validate behavior
- JMX/Actuator boundary
- historical unguarded MBeans
- warning-register status

Do not claim all JMX risk is closed when only the unified control MBean is gated.

External JVM/network/RBAC controls must be documented separately.

## Tracing Correctness Review

Review:

- span name cardinality
- parent/root/detached relationship
- links
- context scope lifecycle
- no duplicate spans
- no duplicate exception events
- no context leakage
- no-op behavior
- resource identity
- propagation
- sampling
- scrubbing
- exporter behavior

Do not approve a tracing change that only asserts “some span exists”.

Where relevant, require:

- expected count
- expected name
- relationship
- attributes
- status
- resource
- sampling result
- scrubbing result
- absence of duplicate spans

## Auto vs Manual Instrumentation Review

Before accepting a manual span:

- verify auto-instrumentation does not already provide it
- explain the semantic gap
- prove no duplicate span
- review name/cardinality
- review attributes/privacy
- review sampling/export behavior
- measure hot-path impact if high volume

Reject manual instrumentation added only because a helper API exists.

## Propagation Review

Incoming propagation is untrusted.

Verify:

- approved W3C implementation
- malformed input behavior
- bounded input
- zero IDs
- flags
- no full raw header in logs/errors
- no trace metadata used for authorization
- no custom parser when mature OTel implementation is approved
- servlet/WebFlux/Kafka/async propagation tests
- classloader behavior

## Sampling Review

Verify:

- ratio bounds
- route precedence
- deterministic tests
- route templates, not raw URLs
- empty mutation rejection
- force-sampling behavior
- force sampling does not bypass scrubbing/export controls
- applied-state consistency
- rejected update preserves LKG
- no conflict between OTel environment sampler and platform sampler defaults

Probabilistic test outcomes are unacceptable when deterministic configuration is possible.

## Scrubbing and PII Review

Review the actual protected scope.

Do not accept a claim that “PII is scrubbed” when only span attributes are processed.

Check separately:

- attributes
- events
- links
- baggage
- resources
- logs
- metrics

Verify:

- active rule diagnostics
- skipped unknown rules
- safe fingerprint
- critical failure behavior
- raw values absent from diagnostics
- force-sampled spans still scrubbed
- service-loaded custom providers cannot bypass mandatory rules

## Metrics Review

Every new metric needs:

- operator question
- owner
- name
- type
- tags
- cardinality budget
- lifecycle
- failure/disabled behavior
- duplication analysis

Reject tags containing:

- trace ID
- span ID
- request ID
- user/account ID
- raw route/path
- endpoint URL
- exception message
- untrusted dynamic value

Check for duplicate Micrometer/OTel/agent/client-library metrics.

## Logging Review

Review:

- level
- structured fields
- sensitive-data handling
- deduplication
- bounded state
- root cause
- actionability
- flood risk

Reject:

- raw control payloads
- authorization headers
- tokens
- cookies
- raw baggage
- full malformed headers
- arbitrary exception messages as structured attributes
- unbounded static warning sets

## Security Review

Identify the trust boundary.

Check:

- untrusted input validation
- secure default
- fail-closed mutation
- no secret/PII exposure
- no trust-all TLS
- no hostname-verification bypass
- no Java native serialization
- no exporter endpoint SSRF path without review
- no tracing metadata as authorization
- no arbitrary runtime mutation
- no provider bypass of mandatory safety

Require negative tests.

Do not claim external authorization is implemented by tracing code.

## Spring Review

Review:

- module ownership
- `@AutoConfiguration`
- registration mechanism
- conditions
- typed properties
- defaults
- missing optional classes
- bean replacement policy
- startup side effects
- disabled behavior
- diagnostics
- Actuator
- servlet/reactive isolation

Reject mechanical use of every condition annotation.

`@ConditionalOnMissingBean` is appropriate only when replacement is a supported contract and cannot bypass safety invariants.

Use `ApplicationContextRunner` for most auto-configuration review evidence.

## Gradle Review

Review:

- module dependency direction
- dependency scope
- runtime provider for `compileOnly`
- version owner/BOM
- transitive impact
- publication metadata
- custom source sets
- task execution/skip semantics
- Javadoc classpath
- architecture tasks
- remote Docker assumptions

Do not approve a scope change solely because it makes compilation pass.

Check the real classpaths with dependency reports.

A successful local compile does not prove correct POM/module metadata.

## Dependency Review

For every new dependency:

```text
Artifact:
Version owner:
Scope:
Public API exposure:
Runtime provider:
Transitive dependencies:
License/governance:
Security status:
Why existing code/dependency is insufficient:
```

Prefer narrow artifacts.

Example:

```text
jackson-annotations
```

instead of:

```text
jackson-databind
```

when only annotation metadata is required.

## Javadoc Review

Public Javadoc must:

- compile
- link only to available public types
- avoid core implementation links from API
- avoid Lombok-generated method links that Javadoc cannot resolve
- describe lifecycle, failure, nullability, and safety
- use current names
- avoid unsupported compatibility promises

Warnings must be classified and fixed at the correct source/classpath boundary.

Do not globally suppress doclint.

## Concurrency Review

Review:

- immutability
- publication
- atomic state update
- race conditions
- lock scope
- idempotency
- rejection behavior
- retry behavior
- concurrent reads during mutation
- partial failure
- rollback
- static mutable state

Runtime state must not expose partially applied policy.

Do not assume singleton beans are automatically thread-safe.

## Reactive Review

Reject:

- blocking calls on Reactor event-loop threads
- `ThreadLocal` assumptions
- context leakage
- MDC leakage
- hidden subscriptions
- duplicate instrumentation
- unbounded retry/repeat
- synchronous exporter/network call
- blocking bridge to JMX/Redis/HTTP without isolation

Require tests for:

- thread hops
- errors
- cancellation
- retries
- context restore
- duplicate spans
- outbound propagation

## Performance Review

Review performance only in the relevant path.

### Hot path

Look for:

- allocations per span
- temporary collections
- regex compilation
- string formatting when disabled
- synchronization
- reflection
- map copies
- context conversions
- logging argument construction
- expensive policy evaluation

### Startup

Look for:

- eager beans
- network calls
- classpath scanning
- JMX side effects
- exporter creation
- optional integration loading
- file/Docker access
- background threads

### Build

Look for:

- eager task realization
- broad filesystem scan
- configuration-time external access
- non-deterministic generation
- cache-unsafe custom tasks

Performance findings must include expected impact and evidence.

Do not block correctness/privacy improvements for speculative micro-optimization.

## Test Review

Tests must prove behavior.

Review:

- happy path
- boundary values
- invalid input
- disabled/no-op behavior
- failure behavior
- state preservation
- optional classpath
- concurrency where relevant
- architecture boundary
- docs/golden synchronization
- E2E execution

Reject tests that:

- copy production logic
- assert private implementation
- use `Thread.sleep`
- depend on execution order
- hide missing behavior behind defaults
- add shims to make old tests compile
- use broad `@SpringBootTest` unnecessarily
- claim PASS after skip
- use probability for deterministic behavior

## Testcontainers and E2E Review

Verify:

- pinned images
- dynamic ports
- no fixed localhost
- correct host/container addressing
- remote Docker compatibility
- explicit readiness
- bounded Awaitility
- no Windows bind mount to remote Gentoo Docker
- unique test identifiers
- actual test execution

For required E2E, verify:

```text
tests > 0
skipped = 0
failures = 0
errors = 0
```

A compiled harness is not runtime evidence.

## Documentation and ADR Review

Require docs when a change affects:

- public API
- wire contract
- runtime-control behavior
- properties/defaults
- JMX/Actuator
- sampling/scrubbing
- security assumptions
- rollout
- known warning/risk

Review that docs distinguish:

- current behavior
- historical behavior
- proposed future work
- startup desired state
- live applied state
- code-level protection
- external JVM/network/RBAC protection

Historical documents may keep old names only when clearly marked.

## Warning Register Review

When a warning/risk register exists:

- update only with evidence
- do not close a warning because one path is fixed if other paths remain
- identify owner
- mitigation
- residual risk
- trigger for closure
- test evidence

Do not turn warning registers into permanent excuses for unbounded technical debt.

## Generated Code Review

Cursor, Codex, and Perplexity output must meet the same standard as handwritten code.

Review generated changes for:

- invented call sites
- false claims
- broad unrelated edits
- stale imports
- wildcard imports
- import-only churn
- compatibility aliases
- fake extension points
- duplicated logic
- missing negative tests
- skipped verification
- confident but unsupported conclusions

Require agents to distinguish verified facts from assumptions.

## Imports and Formatting Review

Follow repository `.editorconfig`.

Reject:

- wildcard imports
- static wildcard imports
- import-only churn in unrelated files
- locally invented import grouping
- broad formatting-only diffs
- ambiguous import guesses

Static imports may be used explicitly in tests.

Do not let import/style noise hide semantic changes.

## Static Scan Review

Scans should target architecture, not arbitrary text.

Review:

- scope
- regex precision
- allowed exceptions
- false positives
- false negatives
- whether the scan runs in CI
- whether the scan checks active source only or historical docs too

Examples:

- `network.protocol.version` is not a legacy Java package
- a test method name may match a removed method without calling it

Narrow bad scans instead of changing correct domain vocabulary.

## Review of Error Handling

Reject:

- swallowed exceptions
- empty catches
- success after failed apply
- generic `RuntimeException` without contract
- raw input in exceptions
- missing root cause
- silent fallback to permissive behavior
- partial state after failure

Prefer:

- explicit result model
- machine-readable code
- bounded sanitized reason
- clear rollback
- exact failure owner

## Review of Reflection

Reflection is not automatically forbidden.

Accept only when:

- required by framework/integration
- isolated
- bounded
- tested
- failure is explicit
- public API does not depend on reflected implementation details

Reject reflection used to:

- bypass module boundaries
- access private implementation
- implement ordinary mapping
- hide optional dependency ownership
- avoid designing a contract
- deserialize arbitrary untrusted classes

## Review of Abstractions

An abstraction is justified when it:

- represents a stable domain concept
- isolates a real dependency
- supports multiple meaningful implementations
- enforces invariants
- reduces duplication across owners
- enables testing without hiding production behavior

Reject:

- one-interface/one-impl ceremony
- `Manager`/`Helper` wrappers
- SPI for one implementation
- factories that only call constructors
- holders/service locators
- duplicate platform wrappers around mature library types without added policy

Do not remove a useful boundary merely to reduce class count.

## Review of Side Effects

Hidden side effects are blockers.

Review:

- bean construction
- static initialization
- class loading
- JMX registration
- global OTel mutation
- system properties
- background threads
- exporter creation
- filesystem writes
- Docker access
- network calls

Side effects must be:

- owned
- explicit
- lifecycle-managed
- idempotent where needed
- reversible where practical
- tested

## Review of Defaults

Defaults are product behavior.

Review all changes to:

- sampling
- scrubbing
- export
- runtime mutation
- diagnostics
- instrumentation enablement
- retries
- queues
- timeouts
- failure policy

Unsafe defaults are P0/P1 findings.

Do not accept a default merely because Spring or OTel provides it.

## Review of No-Op / Disabled Behavior

Verify:

- API remains safe
- no network calls
- no state mutation
- lifecycle valid
- diagnostics accurate
- no false export claims
- no unexpected bean creation
- no optional dependency loading
- no exception solely because feature disabled

No-op behavior must not bypass builder or domain invariants where callers rely on them.

## Review of Migration Completeness

After a breaking refactor, scan:

- main sources
- tests
- custom source sets
- E2E fixtures
- generated provider JARs
- ServiceLoader descriptors
- samples
- benchmarks
- docs
- changelogs
- architecture tests
- configuration metadata
- CI scripts

A refactor is incomplete if only main source compiles.

## Verification Expectations

Use the narrowest gates first, then broader gates.

Typical sequence:

```powershell
.\gradlew.bat :<affected-module>:compileJava --no-daemon
.\gradlew.bat :<affected-module>:compileTestJava --no-daemon
.\gradlew.bat :<affected-module>:test --no-daemon
.\gradlew.bat pr4ArchitectureFitnessVerify pr1ModuleTaxonomyVerify --no-daemon
.\gradlew.bat build --warning-mode all --no-daemon
```

For runtime boundaries:

```powershell
$env:DOCKER_HOST = "tcp://192.168.100.70:2375"
.\gradlew.bat :platform-tracing-e2e-tests:test `
  -PrunE2e `
  --rerun-tasks `
  --no-build-cache `
  --no-daemon
```

Do not require expensive full E2E when the change cannot affect runtime integration, but do require it when behavior crosses:

- agent/application
- JMX
- collector/exporter
- servlet/reactive
- Kafka/database
- runtime control
- sampling/scrubbing export result

## Merge Decision

Use one of:

### PASS

- no P0/P1 findings
- required gates executed and passed
- E2E executed where required
- public/architecture boundaries intact
- docs current
- branch contains the audited fixes

### PASS_WITH_WARNINGS

- no code-level P0/P1
- required compile/test/architecture gates pass
- remaining warning is documented, bounded, non-blocking
- merge recommendation is explicit

### FAIL

- P0/P1 remains
- required gate fails
- required test skipped
- fixes only exist locally but PR branch is stale
- architecture regressed
- state safety/privacy not proven

### INSUFFICIENT_EVIDENCE

- necessary environment unavailable
- runtime test did not execute
- referenced PR/branch/file unavailable
- claim cannot be verified

Do not use PASS to mean “probably fine”.

## Required Review Output

Use this structure:

```markdown
# Review Report

## 1. Executive Verdict

PASS / PASS_WITH_WARNINGS / FAIL / INSUFFICIENT_EVIDENCE

## 2. Scope

- branch
- commit
- base
- files/modules
- review mode

## 3. Problem and Intended Decision

## 4. Verified Repository Facts

| Fact | Evidence | Result |

## 5. Architecture Review

## 6. Public API / SPI Review

## 7. Runtime / State Safety Review

## 8. Security / Privacy Review

## 9. Spring / Gradle / Dependency Review

## 10. Tests and E2E

| Command | Result | Notes |

## 11. Findings

### P0
### P1
### P2
### False Positives
### Insufficient Evidence

## 12. Required Fixes

| Finding | File | Required Change | Verification |

## 13. Residual Risks

## 14. Merge Recommendation

## 15. Codex Fix Prompt

Only when fixes remain.
```

## Architect-Facing Summary

A review summary for architects should answer:

```text
What changed:
Why it is non-cosmetic:
What architecture decision is preserved:
What risks were closed:
What tests actually executed:
What remains external/operational:
Whether the PR is merge-ready:
```

Avoid long implementation narration when a concise evidence-based summary is sufficient.

## Imports and Generated Agent Prompts

When generating an implementation or remediation prompt, include:

```text
Java import policy:
- follow `.editorconfig`
- explicit imports only
- no wildcard imports
- static imports separate
- no import-only churn
- compile affected source sets
- run wildcard import scan
```

Also include:

- exact branch/base
- exact plan/audit
- do-not-touch list
- required commands
- E2E execution requirement
- no commit/push unless explicitly requested
- final report format

## Anti-Patterns

Forbidden review behavior:

- approving because code looks clean
- blocking justified breaking changes for pre-production compatibility
- demanding aliases by default
- treating all public types as supported API
- relying on implementation report without verification
- calling skipped tests passed
- broad architectural re-review after every minor fix
- findings without severity or evidence
- only reviewing main source
- ignoring custom source sets
- ignoring publication metadata
- ignoring classloader boundaries
- accepting public schema/validator implementation leakage
- accepting runtime mutation without fail-closed default
- accepting high-cardinality telemetry without budget
- approving raw PII diagnostics
- weakening architecture gates
- dismissing warnings without root-cause classification
- demanding cosmetic changes as architecture
- allowing import/format churn to hide behavior changes
- overconfident claims without evidence

## Required Final Checklist

Before final approval:

```text
[ ] Correct branch/commit reviewed
[ ] Working tree state understood
[ ] Problem and architecture decision clear
[ ] Public surface reviewed
[ ] Module dependency direction reviewed
[ ] External dependencies/scopes reviewed
[ ] Runtime state and failure paths reviewed
[ ] Security/PII/cardinality reviewed
[ ] Spring conditions/defaults reviewed
[ ] Custom source sets and descriptors reviewed
[ ] Tests compiled
[ ] Unit/integration tests passed
[ ] Required E2E executed, not skipped
[ ] Architecture fitness passed
[ ] Javadoc/build warnings classified
[ ] Docs/ADR current
[ ] No unrelated generated files
[ ] No stale compatibility bridges
[ ] No wildcard imports/import-only churn
[ ] Residual risks documented
[ ] Merge recommendation explicit
```
