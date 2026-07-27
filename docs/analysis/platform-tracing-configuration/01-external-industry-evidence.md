External Industry Evidence for Hybrid Tracing Configuration

Review status

Field

Value

Report status

PASS_WITH_CORRECTIONS

Intended use

Evidence input for Prompt 2 — Plan-to-Code Contradiction Audit

Repository authority

hybrid-tracing-configuration-codebase-context.md

Plan authority

Proposed target state only

Implementation GO

Not granted by this report

Legacy mutation deletion

Still blocked

This report separates:

FACT — confirmed by repository source or executable tests;

INFERRED — derived from call sites or framework behavior but not yet proven in the target production path;

PLAN — proposed target architecture;

UNKNOWN — unresolved.

External evidence is used to evaluate applicability. It is not used to invent repository state.

1. Repository baseline used for applicability screening

The following baseline is treated as authoritative for this report:

The platform is Agent-first in its supported production topology.

SDK-only production is explicitly unsupported.

Competing application OpenTelemetry beans are rejected at startup.

The current repository contains six materially distinct mutation paths:

RefreshScope reconcile;

gated Actuator POST;

partial unified control protocol;

ungated direct domain JMX;

perf-only HTTP administration;

benchmark/test mutation.

VersionedStateHolder<T> is a generic CAS primitive in platform-tracing-otel.

It is instantiated by sampling, scrubbing and validation holders in the Agent extension.

Current updates are atomic per domain, not across domains.

Failed updates retain the previous accepted state as logical LKG.

Eight production JMX ObjectNames exist.

The current CompositeData control protocol is partial: its validator and applier cover different domain sets.

Production Config Server integration is absent.

Step 0B provenance characterization passed on the isolated Boot 3.5.6 / Cloud 2025.0.0 / Config Client 4.3.0 test stack.

The target authority model and the policy.*, javaagent.*, sdk.*, configuration.* and dev-control.* namespaces are not implemented.

General implementation remains NOT GO.

Repository evidence: context dossier.

2. Accepted conclusions

2.1 JMX Open Types should remain the cross-classloader management boundary

Decision: ACCEPT.

CompositeData, TabularData, primitive values, strings and arrays of Open Types are the preferred portable boundary between the Spring application classloader and the Agent extension classloader.

The correct formulation is:

Open Types are the preferred portable and classloader-neutral representation for this repository's JMX boundary.

It is too strong to claim that Open Types are the only technically possible JMX representation. Serializable custom classes can cross JMX boundaries when compatible classes are available to both sides. That approach would, however, introduce classloading and version coupling that is unnecessary and unsafe for the supported Agent-first topology.

Consequences:

Do not pass application or Agent Java DTOs across JMX.

Keep internal application-side and Agent-side models separate.

Use symmetric codecs around the Open-Type wire contract.

Do not create a shared public control DTO module.

Primary supporting source: Oracle Java Management Extensions Open MBean APIs.

2.2 Existing JMX ObjectNames should remain stable in this scope

Decision: ACCEPT.

The reason is repository-specific operational stability, not a universal external rule.

Existing ObjectNames can be referenced by:

runbooks;

JMX clients;

dashboards;

smoke tests;

operational tooling.

This refactoring may remove domain write operations and add one aggregate snapshot operation, but it should not mechanically rename ObjectNames.

A future naming cleanup requires a separate ADR and consumer inventory.

2.3 A JMX management-contract baseline is required before operation removal

Decision: ACCEPT.

Before removing domain setters, capture:

ObjectNames;

attribute names;

attribute Open Types;

operation names;

operation signatures;

notification metadata, if any.

The baseline must be captured before the refactoring, not generated from the new implementation.

The future contract test should permit only explicitly approved changes:

removal of domain-specific mutation operations;

addition of exactly one supported aggregate snapshot writer;

preservation of approved read contracts and ObjectNames.

2.4 Config Server is a desired-state source, not an atomicity mechanism

Decision: ACCEPT.

Config Server changes where configuration originates. It does not make downstream application to sampler, scrubbing, validation, export and propagation state atomic.

Atomicity must be implemented in the target state/applier layer.

Therefore:

Config Server
    -> Spring Environment
    -> full snapshot builder
    -> single aggregate apply operation
    -> atomic state commit

The following is invalid:

Config Server
    -> sequential domain setter calls

The current RuntimeConfigApplier pattern must not be retained as the target Config Server application mechanism.

2.5 Effective value and remote desired value must be resolved independently

Decision: ACCEPT.

The target bridge and guard require two separate views:

effectiveValue + effectiveSource
remoteDesiredValue + remoteDesiredSource

This distinction is required for:

local or system overrides;

structural drift;

restart-required diagnostics;

authoritative Config Server validation.

The canonical Config Server revision must be read directly from the concrete configClient marker PropertySource. It must not be read through a generic Environment.getProperty(...) lookup that can be shadowed.

sourceRevision remains optional when the Config Server backend does not provide a version.

2.6 scrubbing.rules-config should be Category B

Decision: ACCEPT as a strong repository-grounded recommendation.

Repository evidence confirms that scrubbing.rules-config is:

Agent-only;

startup-only;

a path to a Java Properties resource;

loaded through classpath:, file: or a bare filesystem path;

not inline rules;

not YAML.

The target classification should therefore be:

scrubbing.rules-config
    = structural Category B
    = runtime apply forbidden
    = remote desired-state comparison allowed
    = mismatch produces restartRequired

Runtime reload should not be introduced without a separate rule-compilation, file-integrity and concurrency contract.

2.7 No first-party OTel Java Agent control plane replaces the repository's state design

Decision: ACCEPT, with evidence limitations.

No documented first-party OpenTelemetry Java Agent facility was identified that provides a complete production runtime policy mechanism for atomically changing the repository's sampler, scrubbing, validation, export gate and propagation gate.

This supports retaining or evolving the repository's CAS/LKG primitive rather than searching for a nonexistent drop-in framework.

It does not prove that the current per-domain state design is sufficient. Cross-domain atomicity remains unresolved.

3. Conditionally supported conclusions

3.1 Config Server as production runtime authority

Status: CONDITIONALLY SUPPORTED.

The confirmed SRE requirement justifies designing Config Server as the target production authority for runtime policy.

The following are already proven:

standard Config Data import;

per-key provenance;

effective versus remote desired resolution;

revision-marker handling;

refresh event ordering;

key deletion semantics.

The following are not yet proven for the production tracing path:

production dependency/BOM adoption;

bridge-to-Agent aggregate apply;

cross-domain atomic commit;

initial readiness behavior;

runtime structural drift behavior;

refresh-storm handling;

M10/M10c/M10d performance.

The absence of an external example of Config Server directly controlling a Java Agent extension does not make the architecture invalid. It means the repository must prove the custom bridge and apply path.

3.2 acceptedSequence and attemptId

Status: CONDITIONALLY SUPPORTED.

The split is semantically coherent:

acceptedSequence versions accepted runtime truth;

attemptId identifies every apply attempt;

rejected, failed and stale attempts do not advance accepted state;

unchanged payload does not advance accepted state.

This is a repository-specific concurrency design, not an OTel standard.

It requires:

state-transition tests;

concurrent apply tests;

stale-base tests;

idempotency tests;

restart-instance tests;

audit/diagnostic correlation tests.

3.3 CONFIG_SNAPSHOT_V1

Status: DEFERRED, NOT REJECTED.

The capability is not implemented and must not be added speculatively.

A concrete consumer is already identifiable in the target architecture:

Config Server bridge before first apply;

DEV_ACTUATOR adapter before first apply;

readiness/version compatibility gate.

Add the capability only together with:

a concrete Open-Type snapshot codec;

an aggregate writer;

a defined protocol-version policy;

application-side and Agent-side consumers;

process-level compatibility tests.

Do not reject the capability solely because it is absent from current code.

3.4 Default authority HELM

Status: REASONABLE PLAN DECISION, NOT EXTERNAL FACT.

HELM matches the current operational bootstrap path and provides a safe default while Config Server remains opt-in.

Required implementation evidence:

authority binding;

source precedence;

mode interaction with AGENT | DISABLED;

tests proving Config Server and DEV_ACTUATOR are not activated implicitly.

4. Rejected or superseded conclusions from the original Deep Research output

4.1 Do not extend the current partial control protocol as the strategic foundation

Original recommendation: REJECTED.

The current protocol is incomplete and should not be completed merely because it exists.

The target may reuse:

JMX;

Open Types;

an existing stable ObjectName when appropriate.

It should not preserve the current generic protocol semantics.

Target direction:

Config Server bridge / DEV_ACTUATOR
    -> internal full snapshot
    -> one aggregate Open-Type operation
    -> one validated commit path
    -> runtime consumers

The new writer must not orchestrate old domain MBean setters.

Domain MBeans are legacy external adapters, not the internal composition API of the target writer.

After the aggregate path is proven:

remove old protocol operations;

remove domain setters;

remove old application-side mutators;

remove obsolete protocol types.

4.2 Do not reduce the target architecture to DEV_ACTUATOR only

Original recommendation: REJECTED as target architecture.

The committee has confirmed:

Helm as the initial production mechanism;

Config Server as the target centralized production authority;

Actuator/JMX for local development.

A DEV_ACTUATOR implementation may be delivered as an isolated slice only when it is built on the same foundational:

snapshot model;

result model;

state owner;

aggregate writer;

capability contract.

It must not establish a temporary architecture that later requires redesign for Config Server.

General implementation remains NOT GO until the existing gates are passed.

4.3 Do not build the aggregate writer over legacy domain MBeans

Original recommendation: REJECTED.

The aggregate apply handler must commit directly through the selected state owner and runtime-policy components.

Invalid target:

aggregate writer
    -> sampling MBean setter
    -> scrubbing MBean setter
    -> validation MBean setter
    -> export MBean setter

That design remains sequential and non-atomic.

4.4 Do not treat category-by-category namespace migration as a supported intermediate product state

Original recommendation: REJECTED as supported migration strategy.

Inventory and implementation can be split by domain, but the final supported namespace cutover should be coherent.

Avoid a supported state where:

some runtime policy is under platform.tracing.policy.*;

other policy remains under old platform.tracing.*;

both planes keep duplicate readers and defaults.

Recommended distinction:

design and code review by category;

final breaking namespace cutover as one coherent supported contract;

no compatibility aliases;

no long-lived dual readers.

4.5 Do not reject CONFIG_SNAPSHOT_V1 before the codec exists

Original recommendation: SUPERSEDED.

The correct position is defer-until-concrete, not reject-as-overengineering.

4.6 Do not describe Config Server refresh as requiring @RefreshScope

Original formulation: CORRECTED.

@RefreshScope is one Spring bean refresh mechanism. It is not required for the target Agent bridge.

The target bridge can:

listen for EnvironmentChangeEvent;

rebuild the full current snapshot;

validate provenance and defaults;

call the aggregate applier.

The current TracingRefreshScopeAutoConfiguration may be replaced rather than retained.

5. Applicable external patterns

Pattern

Applicability

Repository consequence

Overhead

JMX Open Types across isolated classloaders

Strong

Retain JMX/Open-Type transport; no DTO crossing

Low

Config Data spring.config.import=configserver:

Strong after dependency decision

Use modern path; reject legacy bootstrap

Medium

Per-key effective versus remote desired resolution

Proven by Step 0B

Required for guard and structural drift

Low–medium

Immutable accepted state plus CAS

Strong general concurrency pattern

Retain/evolve state primitive

Medium

Bounded in-memory operational audit

Conditional on SRE/compliance

Merge current audit into bounded metadata-only buffer

Low

Capability/version handshake

Conditional on concrete codec

Add with first supported snapshot protocol

Low

Structural desired state with rollout

Strong

Compare remote desired with bootstrap effective; do not hot-apply

Medium

6. Patterns that must not be copied

6.1 Bespoke polling control plane

Do not introduce:

custom polling clients;

separate REST control servers;

a second remote-sampler protocol.

Spring Cloud Config already provides the confirmed centralized desired-state requirement.

6.2 Collector hot reload as a substitute for Agent policy

Collector reconfiguration does not mutate the in-process Agent sampler, scrubbing processor, validation processor or export gate.

It solves a different problem.

6.3 JMX metrics tooling as write-path architecture

JMX scraper and metrics receiver designs are read-oriented. They support the choice of JMX/Open Types but do not provide:

validation;

atomic state;

optimistic concurrency;

LKG;

restart-required semantics.

6.4 Generic configuration platform

Do not add:

schema compilers;

generated configuration modules;

generic policy DSLs;

universal control-plane frameworks;

new Gradle modules.

6.5 SDK-only parity without a confirmed requirement

SDK-only production is currently unsupported.

Do not introduce platform.tracing.sdk.*, LocalSdkTracingPolicyApplier or feature parity as mandatory target elements unless the architecture committee explicitly changes the supported topology.

7. Required plan amendments

7.1 Decide the fate of the current partial control protocol

The binding choice should be:

replace its semantics with the new aggregate snapshot operation;

optionally reuse its transport technology or ObjectName;

delete old protocol operations and types after cutover.

Do not choose:

finish the partial generic protocol;

retain it beside the new writer;

use it to call legacy domain setters.

7.2 Add the production dependency decision as an implementation-GO gate

The plan must explicitly resolve:

Boot 3.5.5 versus 3.5.6;

Spring Cloud Context 4.1.5 versus BOM 2025.0.0;

optional Config Client dependency;

starter consumer impact;

dependency alignment tests.

This is a platform dependency-governance decision, not merely an implementation detail.

7.3 Classify scrubbing.rules-config as Category B

Record the decision in the plan and property migration table.

7.4 Capture the JMX baseline before removing operations

Create a committed baseline fixture and contract test specification.

7.5 Design Config Server and DEV_ACTUATOR on one foundation

Both adapters must converge on:

the same internal snapshot schema;

the same aggregate writer;

the same state owner;

the same apply result model;

the same capability negotiation.

Only source authority and activation differ.

7.6 Keep namespace cutover coherent

Produce the complete 64-key disposition map before coding the cutover.

No aliases or long-lived mixed namespace support.

8. Architecture overhead controls

The following proposals require explicit rejection unless justified by a confirmed requirement:

Proposal

Default decision

New Gradle module

Reject

Public control DTOs in platform-tracing-api

Reject

Generic policy framework

Reject

Schema compiler

Reject

Second HTTP/REST mutation transport

Reject

Long-lived old and new writers

Reject

SDK-only production parity

Reject unless requirement changes

Durable audit store inside tracing library

Reject unless compliance requires

Runtime reload of file-based rules-config

Reject

Signed snapshots

Reject unless Config Server trust model is insufficient

Automatic mass unready on structural drift

Reject

9. Evidence required before implementation GO

Evidence / decision

Blocks implementation GO?

JMX contract baseline fixture

Yes

RETAIN_GENERIC vs REFACTOR_DOMAIN_SPECIFIC

Yes

State owner/module decision

Yes

Full diagnostics KEEP/REMOVE/MERGE/REWRITE matrix

Yes

scrubbing.rules-config = Category B decision

Yes

Production Boot/Cloud/Config Client dependency decision

Yes

Final authority and namespace design

Yes

Complete 64-key disposition table

Yes

Exact aggregate Open-Type contract

Yes

CONFIG_SNAPSHOT_V1 consumer and compatibility semantics

Yes

Structural drift readiness/health policy

Yes

Security gates for DEV_ACTUATOR

Yes

Config Server aggregate apply tests

Yes before production bridge

M10/M10c/M10d on target Config Server path

Yes before production rollout

Compliance decision for durable versus in-memory audit

Blocks final audit contract, not necessarily core implementation

10. Corrected verdict

Architecture verdict

PASS_WITH_CORRECTIONS as external evidence.

The report supports:

retaining JMX/Open Types;

preserving ObjectNames;

capturing a baseline;

retaining/evolving CAS/LKG state;

treating Config Server as desired-state authority;

solving atomicity in the applier/state layer;

classifying scrubbing.rules-config as structural;

aggressively removing legacy mutation paths after cutover.

The report does not grant:

a DEV_ACTUATOR-only target;

extension of the current partial control protocol;

construction of the new writer over domain MBeans;

implementation GO;

legacy deletion GO.

Current plan status

Step 0A / 0B evidence: PASS
General implementation: NOT GO
Legacy mutation deletion: BLOCKED

11. Handoff contract for Prompt 2

The next model must treat the following as accepted evidence:

1. Config Server changes configuration origin; it does not provide atomic application.
2. Open Types are the preferred portable JMX classloader boundary.
3. Existing JMX ObjectNames remain stable in this scope.
4. A JMX contract baseline is mandatory before removing operations.
5. Effective value and remote desired value are independent views.
6. Config Server revision is read directly from the configClient marker source.
7. scrubbing.rules-config is Category B / restart-required.
8. The current partial control protocol is not a sound strategic foundation.
9. No first-party OTel Java Agent framework replaces the repository's CAS/LKG policy design.
10. Production Config Client/BOM alignment is an implementation-GO gate.

The next model must not inherit these rejected recommendations:

1. Extend the current partial generic control protocol.
2. Build the aggregate writer over domain MBeans.
3. Reduce the target architecture to DEV_ACTUATOR only.
4. Treat category-by-category namespace states as supported intermediate contracts.
5. Reject CONFIG_SNAPSHOT_V1 before a concrete codec and consumer exist.
6. Assume @RefreshScope is required for the Config Server bridge.
7. Add SDK-only parity without a confirmed topology requirement.

The next model must independently verify all remaining Prompt 1 claims against:

the repository dossier;

the current plan;

the provenance spike;

concrete repository symbols.

References

Repository evidence

Hybrid tracing configuration repository dossier

Config Server provenance spike

Current Cursor implementation plan

Primary framework documentation

Spring Cloud Config Client reference

Spring Cloud Config refresh and context reference

OpenTelemetry Java Agent configuration

OpenTelemetry Java Agent extensions

Java Open MBean API

Supporting evidence only

Community dynamically configurable OTel Java Agent sampler

Supporting sources are not used as authoritative proof of repository or first-party framework contracts.