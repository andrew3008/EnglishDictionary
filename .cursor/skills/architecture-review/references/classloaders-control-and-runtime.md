# Classloaders, Control Protocols, and Runtime Architecture

## Classloader Architecture

Application and Java agent code may run in different classloaders.

Across classloader boundaries:

- use JDK-only wire types
- use explicit schemas
- avoid implementation class casting
- reject Java enum instances where String wire values are required
- avoid static holders assuming a single classloader
- verify ServiceLoader visibility deliberately
- isolate JMX/OpenMBean types in the adapter
- keep agent classes out of application-facing API

Required boundary examples:

```text
application classloader
    <-> Map<String,Object> / JDK-safe values
agent classloader
```

Do not cross the boundary with Spring, OTel SDK implementation, custom DTOs loaded independently, or Java native serialization unless explicitly designed and tested.

## Control Protocol Architecture

The approved pipeline is:

```text
wire payload
    -> structural decode
    -> domain validation
    -> mutation policy
    -> apply/read
```

### API owns

- contract version
- operation vocabulary
- key vocabulary
- operation-specific request schemas
- required/allowed keys
- strict unknown-key rejection
- JDK-safe type normalization
- immutable decode result
- structural violation codes

### Core owns

- sampling bounds
- route-ratio bounds
- validation modes
- cross-field rules
- empty-mutation rejection
- runtime mutation policy
- state transition and LKG behavior

### Adapter owns

- JMX/OpenMBean conversion
- agent/runtime wiring
- external transport concerns

Required invariants:

- invalid decode has no usable apply payload
- domain-invalid request cannot apply
- mutation-rejected request cannot apply
- READ does not mutate
- VALIDATE does not apply
- rejected requests preserve snapshot/version/source/LKG
- public schema introspection stays removed
- internal programmatic schema validation remains

Do not reintroduce:

- public `schema()`
- public `validator()`
- `READ_SCHEMA`
- legacy validation packages
- dual decoder paths
- domain rules in API
- raw wire map apply

## Runtime Control Architecture

Runtime control is privileged operational behavior.

Architecture rules:

- mutation disabled by default
- explicit startup enablement
- read-only state available when safe
- validation-only operation does not apply
- machine-readable rejection status
- bounded audit metadata
- atomic state update
- last-known-good preservation
- historical unguarded domain MBeans tracked separately
- external JVM/network/RBAC assumptions documented

Do not claim that a code-level mutation gate replaces JMX authentication, network isolation, or RBAC.

Do not expose runtime mutation through the application-facing tracing API.

