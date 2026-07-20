# Analyze Repository

Analyze the current repository in read-only mode.

Do not modify files.

Before drawing conclusions:

1. inspect the current branch and working tree;
2. inspect `settings.gradle`, root build, and module build files;
3. identify production, test, custom source-set, sample, benchmark, and E2E modules;
4. identify public API, core runtime, Spring, WebMVC, WebFlux, OTel, and JMX boundaries;
5. locate architecture fitness tasks;
6. distinguish current code from historical documentation.

Report:

- verified repository facts;
- module graph;
- public API surface;
- runtime and classloader boundaries;
- test structure;
- verification commands;
- architectural risks;
- assumptions and insufficient evidence.

Do not propose implementation until the current design is understood.
