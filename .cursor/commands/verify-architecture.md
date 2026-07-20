# Verify Architecture

Verify repository architecture without modifying files.

Inspect:

- Gradle project graph;
- API -> core prohibition;
- core -> Spring prohibition;
- servlet/WebFlux isolation;
- OTel/JMX isolation;
- starter thinness;
- production -> test dependency prohibition;
- public implementation leakage;
- legacy packages and symbols;
- raw wire-payload apply paths;
- domain validation ownership;
- ServiceLoader descriptors.

Run the existing architecture and module-taxonomy gates where available.

Do not weaken an architecture rule.

Return exact violations with files and dependency edges.
