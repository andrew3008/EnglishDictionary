# Spring, Build, and Dependency Review

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

