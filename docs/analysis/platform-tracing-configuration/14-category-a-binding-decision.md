# Binding Decision: Category A Runtime Policy

## 1. Decision status

| Field | Decision |
|---|---|
| Architecture direction | APPROVED |
| Gate E5 | CLOSED |
| Decision type | Binding, pre-production |
| Final Category A count | 8 |
| Runtime schema | Allowlist-only |
| Unknown fields | REJECTED before state mutation |
| Expansion without ADR | FORBIDDEN |
| Implementation GO | NOT_GRANTED |
| Legacy deletion GO | BLOCKED |

This decision freezes the runtime-mutable tracing policy. It authorizes planning
and schema design only. It does not authorize production implementation,
migration, legacy deletion, or rollout.

## 2. Evidence considered

The decision follows this evidence order:

1. Current repository source and executable tests.
2. Committed S-02, S-03, and S-04 generated evidence.
3. Post-council documents `13a-council-consensus-and-dissent-register.md` and
   `13b-post-council-repository-spike-plan.md`.
4. Corrected analyses.
5. Historical plan
   `immutable_config_namespace_split_ba8da442.plan.md`.

Binding inputs:

- S-04 inventories 78 `platform.tracing.*` properties, 29 directly read
  environment variables, 17 duplicate declarations, two type-drift groups,
  and two default/ownership-drift groups.
- S-03 records eight JMX ObjectNames, 63 live Standard MBean members, and 16
  semantic mutations. Fifteen mutations bypass the aggregate mutation gate.
- S-02 proves per-key Config Server provenance, local-override detection,
  full-snapshot rebuilding, and `EnvironmentChangeEvent` observation.
- Current validators prove ratio range validation and route-map semantic
  validation, but do not prove every collection or string-size bound required
  by the target codec.

## 3. Decision principles

A leaf is runtime-mutable only when a concrete runtime consumer exists,
mutation has operational value, a complete candidate can be validated and
committed atomically, pipeline composition is unchanged, no secret is carried,
caller authority is not broadened, and the accepted-state hot path remains
safe.

Ambiguity resolves to startup-only Category B. This is a least-authority
decision, not a compatibility concession.

Runtime policy SHALL be a closed typed schema. Generic property maps, wildcard
keys, hidden extensions, structural values, secrets, and unknown fields are
forbidden.

## 4. Final Category A

<!-- CATEGORY_A_BEGIN -->
1. `platform.tracing.policy.sampling.enabled`
2. `platform.tracing.policy.sampling.ratio`
3. `platform.tracing.policy.sampling.route-ratios`
4. `platform.tracing.policy.validation.enabled`
5. `platform.tracing.policy.validation.strict`
6. `platform.tracing.policy.exporter.enabled`
7. `platform.tracing.policy.propagation.trace-control-headers.enabled`
8. `platform.tracing.policy.diagnostics.log-level`
<!-- CATEGORY_A_END -->

The policy is transferred and validated as one complete snapshot. A command
that conceptually changes one leaf MUST copy the current complete policy,
replace one leaf, validate the full candidate, and submit the full snapshot.

## 5. Final excluded runtime properties

The following leaves are Category B and startup-only:

| Current concept | Canonical target | Decision |
|---|---|---|
| `sampling.drop-paths` | `platform.tracing.javaagent.sampling.drop-paths` | EXCLUDE FROM CATEGORY A |
| `sampling.force-record-values` | `platform.tracing.javaagent.sampling.force-record-values` | EXCLUDE FROM CATEGORY A |
| `scrubbing.built-in-rules` | `platform.tracing.javaagent.scrubbing.built-in-rules` | EXCLUDE FROM CATEGORY A |
| `scrubbing.enabled` | `platform.tracing.javaagent.scrubbing.enabled` | EXCLUDE FROM CATEGORY A |

S-04 proposed `policy.*` targets for the first two leaves while their security
and operational disposition was pending, and emitted `N/A` for the two
scrubbing targets. E5 resolves that pending ownership: all four are
Agent-owned startup posture, so the binding names are under `javaagent.*`.
This is an intentional refinement of the S-04 handoff, not an override of a
previously approved canonical startup name.

## 6. Per-property rationale

### 6.1 Sampling enabled

Runtime incident response needs an atomic kill/enable control without changing
sampler construction. `CompositeSampler` already consumes a runtime snapshot.
The leaf is non-secret and has a boolean domain.

### 6.2 Sampling ratio

The default ratio is a bounded numeric policy used on the sampling hot path.
The existing validator enforces `[0.0, 1.0]`; atomic snapshot replacement
avoids mixed ratios.

### 6.3 Sampling route ratios

Route overrides are operational policy and already have runtime consumers.
The canonical semantic representation remains `Map<String, Double>`; it MUST
NOT be flattened to strings or represented as parallel arrays in accepted
state. Wire/OpenType adapters MAY use transport-specific structures but MUST
round-trip to the same canonical map.

### 6.4 Validation enabled

The validating processor already supports a runtime snapshot. The switch does
not construct or remove the processor and can therefore be applied atomically.

### 6.5 Validation strict

Strictness remains mutable only inside the immutable startup envelope
`platform.tracing.javaagent.validation.strict-runtime-allowed`. A candidate
requesting `strict=true` while the envelope is false is rejected before commit.

### 6.6 Exporter enabled

This leaf gates an already constructed `SafeSpanExporter`; it does not alter
exporter type, endpoint, credentials, queue, or processor composition.

### 6.7 Trace-control propagation enabled

This leaf gates platform trace-control headers in existing adapters. It does
not alter W3C propagator composition, trusted destinations, header vocabulary,
or trust policy.

### 6.8 Diagnostics log level

Incident diagnostics require bounded runtime verbosity control. The closed
enum is non-secret and is consumed by `PlatformLogControl`; arbitrary logger
names or logging configuration are outside the schema.

### 6.9 Sampling drop paths

**EXCLUDE FROM CATEGORY A.** The property controls which paths disappear from
observability. An incident-time mistake can hide the incident under
investigation; no mandatory runtime-only consumer has been proven; a
restart/rollout is acceptable; and exclusion reduces validation and rollout
risk.

### 6.10 Sampling force-record values

**EXCLUDE FROM CATEGORY A.** The property determines which incoming
`X-Trace-On` values may bypass ordinary sampling. Runtime expansion broadens
an external caller's amplification authority and creates denial-of-service
risk. It belongs to immutable Agent trust/bootstrap posture.

### 6.11 Scrubbing leaves

Built-in rule selection and scrubbing activation affect privacy posture and
processor behavior. They remain immutable Agent startup configuration. HMAC
keys and other secret material are never represented as desired state,
runtime policy, digest input, audit values, or metrics.

## 7. Types, bounds and normalization requirements

| Target leaf | Java semantic type | Normalized representation | Default owner | Validation |
|---|---|---|---|---|
| sampling enabled | `boolean` | canonical boolean | Agent policy bootstrap, current default `true` | strict boolean, no coercion |
| sampling ratio | `double` | finite IEEE-754 double | Agent policy bootstrap, current default `0.1` | existing range `[0.0, 1.0]`; reject NaN/infinity |
| sampling route ratios | `Map<String, Double>` | immutable map, deterministic key order, exact double values | Agent policy bootstrap, current default empty map | nonblank keys; finite values in `[0.0, 1.0]`; size/key bounds `BOUND_TO_BE_DERIVED_FROM_EXISTING_VALIDATOR` |
| validation enabled | `boolean` | canonical boolean | Agent policy bootstrap, current default `true` | strict boolean, no coercion |
| validation strict | `boolean` | canonical boolean | Agent policy bootstrap, current default `false` | strict boolean plus startup envelope |
| exporter enabled | `boolean` | canonical boolean | Agent policy bootstrap, current default `true` | strict boolean, no coercion |
| trace-control propagation enabled | `boolean` | canonical boolean | Agent policy bootstrap, current default `true` | strict boolean, no coercion |
| diagnostics log level | `OtelAgentLogLevel` semantic enum | uppercase token `OFF|ERROR|WARN|INFO|DEBUG|TRACE` | Agent policy bootstrap, current default `INFO` | closed enum; trim/case normalization at ingress only |

All normalized collections are immutable. Duplicate route keys after any
approved normalization are rejected. The wire codec MUST reject nulls,
unknown fields, type coercion outside the explicitly supported Open Types, and
oversized payloads before domain validation.

### 7.1 Per-leaf operational contract

| Exact target | Java semantic type / normalized representation | Default owner | Runtime consumer | Validation | Cross-field invariant | Digest representation | Audit representation | Metric representation | Failure outcome |
|---|---|---|---|---|---|---|---|---|---|
| `platform.tracing.policy.sampling.enabled` | `boolean` / canonical boolean | Agent policy bootstrap (`true`) | accepted sampling snapshot consumed by `CompositeSampler` | strict boolean | disabling sampling MUST NOT remove/rebuild the sampler | field id + `true|false` in canonical field order | leaf id + outcome; no value | fixed apply outcome only; no value label | `REJECTED`; accepted state unchanged |
| `platform.tracing.policy.sampling.ratio` | `double` / finite canonical double | Agent policy bootstrap (`0.1`) | accepted sampling snapshot consumed by `CompositeSampler` | `[0.0, 1.0]`, no NaN/infinity | evaluated only when higher-precedence sampling rules do not decide | field id + canonical finite decimal/IEEE value | leaf id + outcome; no numeric value | fixed apply outcome only; no ratio label | `REJECTED`; accepted state unchanged |
| `platform.tracing.policy.sampling.route-ratios` | `Map<String, Double>` / immutable deterministic map, numeric values retained | Agent policy bootstrap (empty) | route-ratio rule in the accepted sampling snapshot | nonblank keys; values `[0.0, 1.0]`; bounds `BOUND_TO_BE_DERIVED_FROM_EXISTING_VALIDATOR` | duplicate normalized keys rejected; route rules precede default ratio under the sealed sampling contract | field id + key-sorted length-prefixed entries and canonical finite values | leaf id + outcome + entry count only; no routes/ratios | fixed outcome and bounded size bucket only if P5 approves; no route label | `REJECTED`; accepted state unchanged |
| `platform.tracing.policy.validation.enabled` | `boolean` / canonical boolean | Agent policy bootstrap (`true`) | accepted validation snapshot consumed by `ValidatingSpanProcessor` | strict boolean | disabling validation MUST NOT remove/rebuild the processor | field id + `true|false` | leaf id + outcome; no value | fixed apply outcome only | `REJECTED`; accepted state unchanged |
| `platform.tracing.policy.validation.strict` | `boolean` / canonical boolean | Agent policy bootstrap (`false`) | accepted validation snapshot consumed by `ValidatingSpanProcessor` | strict boolean | `true` requires immutable `platform.tracing.javaagent.validation.strict-runtime-allowed=true` | field id + `true|false` | leaf id + outcome; no value | fixed apply outcome only | `REJECTED`; accepted state unchanged |
| `platform.tracing.policy.exporter.enabled` | `boolean` / canonical boolean | Agent policy bootstrap (`true`) | existing `SafeSpanExporter` gate | strict boolean | MUST NOT change exporter, endpoint, credentials, queue, or processor composition | field id + `true|false` | leaf id + outcome; no value | fixed apply outcome only | `REJECTED`; accepted state unchanged |
| `platform.tracing.policy.propagation.trace-control-headers.enabled` | `boolean` / canonical boolean | Agent policy bootstrap (`true`) | existing platform trace-control propagation adapters | strict boolean | MUST NOT alter W3C composition, trust rules, destination allowlists, or header vocabulary | field id + `true|false` | leaf id + outcome; no value | fixed apply outcome only | `REJECTED`; accepted state unchanged |
| `platform.tracing.policy.diagnostics.log-level` | `OtelAgentLogLevel` / uppercase closed token | Agent policy bootstrap (`INFO`) | `PlatformLogControl` | `OFF|ERROR|WARN|INFO|DEBUG|TRACE` only | changes platform internal verbosity only; MUST NOT reconfigure application logging | field id + uppercase token | leaf id + outcome; no value | fixed apply outcome only; no level label | `REJECTED`; accepted state unchanged |

The complete policy digest is computed over protocol version and all eight
normalized fields in canonical field order. The hash algorithm and binary/text
framing SHALL be fixed by the wire-contract implementation review; no
implementation may use unordered `Map.toString()`, locale-sensitive
formatting, or secret material.

## 8. Validation and cross-field invariants

- A candidate is decoded, normalized, validated, compared, and committed as a
  complete object.
- `sampling.ratio` and every route ratio remain in `[0.0, 1.0]`.
- Route prefixes are nonblank; the exact collection/key bounds are
  `BOUND_TO_BE_DERIVED_FROM_EXISTING_VALIDATOR`.
- `validation.strict=true` is valid only when
  `platform.tracing.javaagent.validation.strict-runtime-allowed=true`.
- Disabling exporter or propagation changes only behavior of an existing
  component; it MUST NOT reconstruct the SDK pipeline.
- Log-level values outside the closed enum are rejected.
- Secrets, file paths, class names, rules, endpoints, credentials, and
  structural configuration are rejected as runtime policy.
- Failure before the accepted-state commit leaves the last-known-good policy
  and accepted sequence unchanged.

## 9. Security consequences

The eight-leaf allowlist minimizes writer authority. Excluding force-record
values prevents runtime expansion of an external caller's ability to force
recording. Excluding drop paths prevents a control-plane mistake from hiding
incident traffic. Excluding scrubbing controls prevents runtime weakening of
PII protection.

The writer belongs on a private process-local MBeanServer. The platform
MBeanServer becomes read-only after cutover. No HTTP writer, generic raw
snapshot command, remote fallback writer, or secret-bearing field is allowed.

## 10. Performance consequences

Accepted policy is read through one immutable snapshot/reference. Apply-time
normalization and hashing MUST NOT run on span hot paths. Route lookup remains
bounded by implementation limits established before implementation GO.
Metrics use fixed outcome/leaf dimensions and MUST NOT label route keys,
values, digests, revisions, or failure text.

## 11. Wire-schema consequences

The next aggregate snapshot schema contains exactly the eight leaves above
plus protocol metadata required for optimistic concurrency. It is
allowlist-only; unknown and missing required fields are rejected. The route
map round-trips as `Map<String, Double>` semantics.

The current partial protocol recognizes more fields than the final schema and
applies fewer fields than it recognizes. It SHALL be replaced during the
coherent cutover; it MUST NOT be extended with compatibility aliases.

## 12. Configuration-authority consequences

Authority controls only `policy.*`:

- `HELM`: startup policy from Agent env/system properties; no runtime writer.
- `CONFIG_SERVER`: Spring proves provenance and submits complete desired
  snapshots through the private writer.
- `DEV_ACTUATOR`: local typed commands submit complete snapshots through JMX.
- `DISABLED`: no writer discovery or mutation.

Category B remains effective from startup env/system properties. Config Server
may carry non-secret Category B desired values only for drift comparison.

## 13. Reversal conditions

An excluded leaf may enter Category A only through a new ADR containing:

- a named incident or SRE requirement;
- security review;
- runtime-safety and atomicity proof;
- exact type and bounds;
- state and wire-schema changes;
- hot-path and apply-path performance evidence;
- failure, audit, metric, rollout, and rollback contracts.

No generic extension map or unknown-field tolerance may be used as a shortcut.

## 14. Gate E5 status

```text
Gate E5: CLOSED
Final Category A count: 8
Runtime schema: allowlist-only
Unknown fields: REJECTED
Category A expansion without ADR: FORBIDDEN
```

## 15. Effect on the refactoring plan

The normative plan SHALL embed this exact allowlist, retain the immutable
strict-validation envelope, move the four excluded leaves to `javaagent.*`,
and prevent any implementation train from reopening E5. Remaining gates are
E3, E6, E7, and E8. Closing E5 does not grant implementation GO.
