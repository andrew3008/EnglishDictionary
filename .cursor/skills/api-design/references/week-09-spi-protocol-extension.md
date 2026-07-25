# Неделя 09 — SPI protocol и extension points

## Semantic implementability

Syntactic implementability **≠** semantic. Provider **must** понимать contract без internal source.

**Per SPI method must document:**
- preconditions, null policy
- thread/call context
- ordering vs other callbacks
- idempotency, reentrancy
- ownership args/results
- timeout/cancellation
- exception policy
- lifecycle/release
- compatibility expectations

## Minimal complete SPI

**Minimal:** no framework housekeeping caller can do.
**Complete:** provider **must-not** need internal singletons or undocumented downcasts.

Provider burden = **число independent decisions**, не count of methods.

## Формы extension points

| Form | Role | Key contract |
|---|---|---|
| Strategy | local decision | purity, frequency, parallel calls |
| Provider/factory | creates instances | scope, caching, ownership, failure timing |
| Callback/listener | in-operation / observe | ordering, isolation, multicast policy |
| Customizer | modify builder pre-build | timing, mutability window, ordering |
| Decorator/interceptor | wrap next | exactly-once, short-circuit, recursion |

Customizer on vendor builder = **deliberate integration SPI**, not neutral platform SPI.

## Lifecycle SPI

Construction **≠** activation. **Must-not** I/O/threads in constructor without ready context.

Typical phases: discovery → construction → validation → start → steady-state → quiesce → close.

**Must define:** idempotent start/stop/close; calls after failure; restart policy; Spring callback interaction.

## Threading и reentrancy

«Thread-safe» **must** specify: which methods concurrent, config mutation, linearizability.

**Must** document callback thread (request, scheduler, startup, arbitrary executor).

Reentrancy: allowed → avoid deadlock; forbidden → detect and fail actionably.

Blocking budget **must** be explicit on latency-sensitive SPI paths.

## Failure contract SPI

| Outcome | Handling |
|---|---|
| normal rejection | result variant / domain negative |
| provider defect | null, undocumented exception, inconsistent result |

Caller **must** define: propagate / wrap / log / isolate.

**Must-not** `catch (Throwable)` → generic fallback (loses `Error`, cancellation).

Fail-open vs fail-closed = **operational contract** (enrichment vs security).

Diagnostics **must** identify extension by stable id + preserve cause.

## Ownership SPI

Framework creates provider → framework usually closes.

Consumer registers → **must** explicit owned vs borrowed (`registerOwned` / `registerBorrowed`).

**Must-not** retain borrowed mutable buffer/context after callback return.

Context object **must** expose stable capabilities, **not** `ApplicationContext` / service locator.

## Interface vs abstract class vs composition

| Choice | When |
|---|---|
| Interface | small stable protocol; lambda-friendly |
| Abstract class | template method is supported extension model |
| Composition object | provider needs services without inheritance coupling |

Protected members = **subclass SPI contract** — evolves harder than public caller methods.

## Default methods

Binary relief **≠** behavioral compatibility.

**Conservative default** (e.g. `supportsRefresh() → false`) safer than surprising policy.

Default throwing `UnsupportedOperationException` → runtime breakage on old providers; **only** if caller checks capability first.

Default conflicts across interfaces — check source **and** binary compatibility separately.

## Open vs closed extension

| Model | Use |
|---|---|
| Open SPI | arbitrary third-party implementations |
| Sealed hierarchy | controlled variants; **not** open third-party SPI |
| Enum capabilities | closed list; new constant may break exhaustive switch |

Capability negotiation **must** have rules, not bare `version()` field.

```java
public record ProviderCapabilities(boolean refresh, Set<CapabilityId> extensions) {}
```

## Annotations и metadata as SPI

Annotation + processing rules = SPI contract: targets, inheritance, repeatability, conflicts, timing.

Registration files (`META-INF/services`, `AutoConfiguration.imports`, metadata) = machine-facing contracts — version syntax, duplicates, ordering, merge.

## Must / Must-not

**Must:** full protocol docs per SPI; separate normal rejection from defect; lifecycle phases explicit; fail-open/closed declared; attribution in diagnostics.

**Must-not:** `ApplicationContext` in domain SPI; open-ended `Map<String,Object>` context; default unsupported without capability check; SPI requiring internal downcast.

## Multicast listener policies

| Policy | When |
|---|---|
| fail-fast | first failure aborts; critical path |
| invoke all + aggregate | all must run; collect failures |
| isolate/log each | non-critical observers |
| route to error handler | platform decides severity |

**Must-not** обещать delivery всем **и** fail-fast без уточнения.

## Hostile provider expectations (caller side)

Library **must** test provider that:
- blocks unbounded
- throws on every call
- returns invalid/null results
- reenters library
- fails close

Isolation policy **must** survive hostile provider без corrupting core state.

## Bridge adapters (deprecation)

Temporary old→new SPI adapter **must** declare lost capabilities and removal deadline.

Permanent dual protocol **must-not** — doubles test matrix.

## Ключевые тезисы

1. SPI must be semantically implementable without internal source.
2. Lifecycle, threading, ownership, failures — mandatory SPI parts.
3. Default method helps binary, not behavior.
4. Protected = contract for subclasses.
5. Annotation/metadata = implementor contract.
6. Multicast policy — explicit choice, not accidental.
7. Hostile provider tests validate caller isolation.
