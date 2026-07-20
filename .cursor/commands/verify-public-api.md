# Verify Public API

Verify the intentional public API surface.

Do not modify files unless explicitly requested.

Inspect:

- public types;
- public constructors;
- public methods;
- public annotations;
- public SPIs;
- third-party types in signatures;
- public-for-compilation internal bridges;
- removed legacy FQNs;
- deprecated bridges and aliases;
- Javadoc;
- Gradle dependency exposure.

Compare the result with architecture rules and the authoritative plan.

Report:

- types and methods added;
- types and methods removed;
- accidental public types;
- dependency-contract changes;
- missing JavaDoc;
- architecture-test gaps;
- compatibility artifacts that should be deleted.
