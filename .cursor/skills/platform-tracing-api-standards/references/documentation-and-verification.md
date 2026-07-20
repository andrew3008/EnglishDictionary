# Documentation and Verification

## Javadoc

All application-facing public types and methods require useful JavaDoc.

JavaDoc must explain:

- purpose
- consumer
- behavior
- lifecycle
- thread-safety when relevant
- failure behavior
- nullability
- sensitive-data/cardinality constraints
- whether a type is internal despite public visibility

Avoid:

- links from API JavaDoc to core implementation classes
- links to Lombok-generated methods that Javadoc cannot resolve
- implementation history
- vague statements such as “platform helper”
- stale names
- promises of compatibility not yet approved

Javadoc must compile without warnings in supported modules.

## Documentation

For a breaking API change, update:

- ADR
- changelog
- public API inventory
- architecture docs
- usage guides
- samples
- configuration metadata
- tests
- rollout docs where relevant

Historical documents may retain old names only when clearly marked historical.

Do not leave current-looking analysis documents with stale API names.

## API Testing

Public API tests must protect intentional behavior and absence of accidental behavior.

Required tools may include:

- unit tests
- reflection tests
- ArchUnit
- `javap`
- Javadoc
- dependency reports
- golden wire tests
- integration/e2e tests

Test:

- exact public type surface where appropriate
- exact facade method set
- visibility of implementation helpers
- immutable result/value objects
- builder invariants
- removed old FQNs
- no compatibility aliases
- no deprecated bridges
- no forbidden external dependencies
- no legacy packages
- disabled/no-op behavior
- Javadoc links

When deleting a public type before production, add a negative guard if the symbol is likely to return accidentally.

## Architecture Fitness Rules

Protect at least:

- API does not depend on core
- API does not depend on Spring/JMX/OpenMBean/OTel SDK implementation
- core does not depend on Spring
- exact public API surface for sensitive packages
- implementation helpers are not public
- deleted legacy packages/symbols do not return
- no accidental ServiceLoader holders
- no public internal schema/validator
- no domain validation in wire API
- no runtime apply from raw wire payload
- webmvc/webflux package isolation
- no wildcard imports

Rules should encode the architecture decision, not the temporary implementation.

Do not weaken a rule merely to make generated code compile.

