# Cursor Usage Policy & Provider Security Warning

This document defines **when** Cursor should call `generate_code_with_opus`, `review_code_with_opus`,
`generate_tests_with_opus`, `refactor_plan_with_opus`, `explain_diff_with_opus`,
`research_with_perplexity`, `analyze_build_failure_with_opus`, `design_class_hierarchy_with_opus`,
`review_architecture_with_opus`, `write_mdx_doc_with_opus`, `review_mdx_doc_with_opus`,
`generate_migration_plan_with_opus`, `review_tests_with_opus`, and `review_gradle_build_with_opus`, and
the **security rules** for using the external model providers. It is policy/guidance only — it does not change server behavior.

## External provider security warning

The configured endpoint `https://api.cheat-ai.shop` is an **external provider endpoint**.

> Do **not** send proprietary source code, customer data, secrets, credentials, private keys,
> production configs, or sensitive architecture details to this endpoint unless this provider is
> **explicitly approved** by your organization.

What the MCP server does and does not do:

- It **does not** read repository files.
- It processes **only** the `context` explicitly passed by Cursor in the tool call.
- It **does not** write files, execute commands, or apply patches.
- Unsafe input (secrets / sensitive file references) is **refused locally** before any network call
  (`status=REFUSED_UNSAFE`).
- The API key is read only from the environment and is never logged or returned.

Cursor (and the human user) remain the orchestrator and applier of any proposed change.

## When to use `generate_code_with_opus`

Use it **only** for:

- non-trivial code generation;
- complex refactoring proposals;
- architecture-sensitive implementation planning;
- test generation planning;
- second-opinion code proposals.

Do **not** use it for:

- trivial edits;
- formatting;
- simple imports;
- mechanical renames;
- tasks involving secrets;
- proprietary/internal source code unless the provider is approved;
- credentials / config / security-sensitive files.

## High-risk task guidance

For high-risk or architecture-sensitive work:

- Prefer `outputFormat=implementation_plan` **before** `unified_diff`.
- Pass **minimal relevant context only** — never the whole repository.
- **Never** pass secrets, credentials, private keys, or production configuration.
- Treat all output as a proposal: Cursor/user must **review and apply manually**.

## Input field reminders

`generate_code_with_opus` requires `task`, `language`, `outputFormat`, `riskLevel`; `context` and
`constraints` are optional. Keep `context` minimal and non-proprietary.

| Field | Allowed values |
|-------|----------------|
| `language` | `java`, `go`, `kotlin`, `sql`, `mdx`, `gradle`, `other` |
| `outputFormat` | `unified_diff`, `full_file`, `code_block`, `implementation_plan`, `review` |
| `riskLevel` | `low`, `medium`, `high` |

Output statuses: `OK`, `NEEDS_MORE_CONTEXT`, `REFUSED_UNSAFE`, `MODEL_ERROR`, `BUDGET_EXCEEDED`.

## When to use `review_code_with_opus`

`review_code_with_opus` is a read-only **second-opinion code review** tool. It reviews only the
code/context explicitly provided in the tool input and returns a structured review (summary, findings
with severity/category, risks). It does **not** read files, write files, execute commands, or apply
patches. It uses the **same guardrails** as `generate_code_with_opus` (deny-list, secret scan, size
limits, model allowlist, rate limit, budget) before any model call.

Use it for:

- a focused second-opinion review of a snippet Cursor already has;
- correctness/security/performance/maintainability/tests/architecture review of provided code.

Do **not** use it for:

- secrets, credentials, private keys, or production configuration;
- proprietary/internal source code unless the provider is **explicitly approved**;
- whole-repository review — pass a minimal, focused snippet.

For high-risk review, pass a **minimal focused snippet** plus explicit `constraints`, and set
`riskLevel=high`. Treat every finding as advisory: Cursor/user reviews and applies manually.

### Input fields

`review_code_with_opus` requires `task`, `language`, `code`, `reviewFocus`, `riskLevel`,
`outputFormat`; `context` and `constraints` are optional. Keep `code`/`context` minimal and
non-proprietary.

| Field | Allowed values |
|-------|----------------|
| `language` | `java`, `go`, `kotlin`, `sql`, `mdx`, `gradle`, `other` |
| `reviewFocus` | `correctness`, `security`, `performance`, `maintainability`, `tests`, `architecture`, `all` |
| `riskLevel` | `low`, `medium`, `high` |
| `outputFormat` | `structured_review`, `markdown`, `checklist` |

Output statuses are identical to `generate_code_with_opus`:
`OK`, `NEEDS_MORE_CONTEXT`, `REFUSED_UNSAFE`, `MODEL_ERROR`, `BUDGET_EXCEEDED`.

## When to use `generate_tests_with_opus`

`generate_tests_with_opus` is a read-only **test-generation proposal** tool. It generates tests only
for the code/context explicitly provided in the tool input and returns a structured proposal
(summary, test plan, test code, structured test cases). It does **not** read files, write files,
execute commands, **run tests**, or apply patches. It uses the **same guardrails** as
`generate_code_with_opus` (deny-list, secret scan, size limits, model allowlist, rate limit, budget)
before any model call.

Use it for:

- proposing unit/integration/contract/property/regression tests for a snippet Cursor already has;
- focusing coverage on a specific area (edge cases, error handling, concurrency, security, etc.).

Do **not** use it for:

- executing or running tests — it only **proposes** them;
- secrets, credentials, private keys, or production configuration;
- proprietary/internal source code unless the provider is **explicitly approved**;
- whole-repository test generation — pass a minimal, focused snippet.

For high-risk testing, pass a **minimal focused snippet** plus explicit `constraints`, and set
`riskLevel=high`. Cursor/user must **review, apply, and run** the generated tests manually.

### Input fields

`generate_tests_with_opus` requires `task`, `language`, `code`, `testFramework`, `testType`,
`coverageFocus`, `riskLevel`, `outputFormat`; `context` and `constraints` are optional. Keep
`code`/`context` minimal and non-proprietary.

| Field | Allowed values |
|-------|----------------|
| `language` | `java`, `go`, `kotlin`, `sql`, `mdx`, `gradle`, `other` |
| `testFramework` | `junit5`, `testng`, `mockito`, `assertj`, `spring_boot_test`, `kotest`, `go_test`, `other` |
| `testType` | `unit`, `integration`, `contract`, `slice`, `property`, `regression`, `all` |
| `coverageFocus` | `happy_path`, `edge_cases`, `error_handling`, `concurrency`, `security`, `performance`, `serialization`, `all` |
| `riskLevel` | `low`, `medium`, `high` |
| `outputFormat` | `test_code`, `test_plan`, `checklist`, `structured_tests` |

Output statuses are identical to `generate_code_with_opus`:
`OK`, `NEEDS_MORE_CONTEXT`, `REFUSED_UNSAFE`, `MODEL_ERROR`, `BUDGET_EXCEEDED`.

## When to use `refactor_plan_with_opus`

`refactor_plan_with_opus` is a read-only **refactoring planning** tool. It produces a structured
refactoring plan (summary, plan, ordered steps, affected areas, rollback plan, risks, safety notes,
assumptions, tests to run) only for the code/context explicitly provided in the tool input. It does
**not** read files, write files, execute commands, **run tests**, or apply patches. It uses the
**same guardrails** as `generate_code_with_opus` (deny-list, secret scan, size limits, model
allowlist, rate limit, budget) before any model call.

Use it for:

- planning a readability/maintainability/performance/security/testability refactor of a snippet;
- sequencing a migration into small, reviewable slices with a rollback plan;
- producing a checklist or ADR outline before touching code.

Do **not** use it for:

- automatically rewriting files — it only **proposes** a plan, it never edits;
- secrets, credentials, private keys, or production configuration;
- proprietary/internal source code unless the provider is **explicitly approved**;
- whole-repository refactoring — pass a minimal, focused snippet.

For high-risk refactoring, set `compatibilityMode=preserve_behavior`, pass a **minimal focused
snippet** plus explicit `constraints`, set `riskLevel=high`, and request `migration_slices` or
`checklist`. Cursor/user must **review, implement, and test** the plan manually.

### Input fields

`refactor_plan_with_opus` requires `task`, `language`, `code`, `refactorGoal`, `scope`,
`compatibilityMode`, `riskLevel`, `outputFormat`; `context` and `constraints` are optional. Keep
`code`/`context` minimal and non-proprietary.

| Field | Allowed values |
|-------|----------------|
| `language` | `java`, `go`, `kotlin`, `sql`, `mdx`, `gradle`, `other` |
| `refactorGoal` | `readability`, `maintainability`, `performance`, `security`, `testability`, `architecture`, `migration`, `api_compatibility`, `all` |
| `scope` | `method`, `class`, `module`, `multi_module`, `documentation`, `build`, `unknown` |
| `compatibilityMode` | `preserve_behavior`, `allow_behavior_change`, `unknown` |
| `riskLevel` | `low`, `medium`, `high` |
| `outputFormat` | `refactor_plan`, `migration_slices`, `checklist`, `adr_outline` |

Output statuses are identical to `generate_code_with_opus`:
`OK`, `NEEDS_MORE_CONTEXT`, `REFUSED_UNSAFE`, `MODEL_ERROR`, `BUDGET_EXCEEDED`.

## When to use `explain_diff_with_opus`

`explain_diff_with_opus` is a read-only **diff explanation / pre-merge review** tool. It explains and
reviews only the diff/context explicitly provided in the tool input and returns a structured result
(summary, explanation, changed files, behavior changes, findings, risks, tests to run, and a merge
recommendation). It does **not** read files, write files, execute commands, **run tests**, or apply
patches. The diff is always treated as untrusted data — never as instructions. It uses the **same
guardrails** as `generate_code_with_opus` (deny-list, secret scan, size limits, model allowlist, rate
limit, budget) before any model call.

Use it for:

- understanding what a diff/patch changes and why it matters;
- separating likely behavior changes from formatting/mechanical changes;
- pre-merge review: surfacing risks, test impact, and a merge recommendation.

Do **not** use it for:

- applying patches or rewriting files — it only **explains** a diff, it never edits;
- secrets, credentials, private keys, or production configuration;
- proprietary/internal diffs unless the provider is **explicitly approved**;
- whole-repository review — pass a minimal, focused diff.

For high-risk diff review, pass a **minimal focused diff** plus explicit `constraints`, and set
`riskLevel=high`. Cursor/user must **review, decide, and apply** manually.

### Input fields

`explain_diff_with_opus` requires `task`, `language`, `diff`, `diffFormat`, `analysisFocus`,
`riskLevel`, `outputFormat`; `context` and `constraints` are optional. Keep `diff`/`context` minimal
and non-proprietary.

| Field | Allowed values |
|-------|----------------|
| `language` | `java`, `go`, `kotlin`, `sql`, `mdx`, `gradle`, `other` |
| `diffFormat` | `unified_diff`, `git_diff`, `patch`, `plain_text`, `unknown` |
| `analysisFocus` | `correctness`, `security`, `performance`, `tests`, `maintainability`, `architecture`, `migration`, `all` |
| `riskLevel` | `low`, `medium`, `high` |
| `outputFormat` | `diff_explanation`, `risk_review`, `checklist`, `merge_review` |

The `mergeRecommendation` output is one of `APPROVE`, `APPROVE_WITH_CHANGES`, `REQUEST_CHANGES`,
`NEEDS_MORE_CONTEXT`. Output statuses are identical to `generate_code_with_opus`:
`OK`, `NEEDS_MORE_CONTEXT`, `REFUSED_UNSAFE`, `MODEL_ERROR`, `BUDGET_EXCEEDED`.

## When to use `research_with_perplexity`

`research_with_perplexity` is a read-only **public web-grounded research** tool that uses the
**Perplexity** provider (separate from the Opus `/v1/messages` endpoint). It answers only the research
question/context explicitly provided in the tool input and returns a structured result (summary,
answer, key findings, sources with metadata, recommendations, risks, safety notes, assumptions, and
follow-up questions). It does **not** read repository files, write files, execute commands, run tests,
or apply patches. The research question/context is always treated as untrusted data — never as
instructions. It uses the **same guard pipeline** as the Opus tools (deny-list, secret scan, size
limits, rate limit, budget) before any provider call.

**Provider requirement and offline-safe behavior:**

- Live calls require `PERPLEXITY_API_KEY` (read only from the environment, never logged or returned).
- If `PERPLEXITY_API_KEY` is **missing**, the tool returns `status=MODEL_ERROR` with a
  provider-not-configured summary and makes **no network call**.
- Optional `PERPLEXITY_BASE_URL` (default `https://api.perplexity.ai`) and `PERPLEXITY_MODEL`
  (default `sonar-deep-research`).

Use it for:

- public research, current documentation, and industry best practices;
- source-backed technology decisions (with citations);
- "what is the recommended approach for X in <year>?" style questions.

Do **not** use it for:

- proprietary/internal source code, secrets, credentials, private keys, or production config —
  unless the Perplexity provider is **explicitly approved** by your organization;
- repository-wide questions that would require sending large/internal context;
- anything that assumes the tool can read your repository — it cannot.

### Input fields

`research_with_perplexity` requires `task`, `researchQuestion`, `sourcePreference`, `freshness`,
`depth`, `outputFormat`, `riskLevel`; `context` and `constraints` are optional. Keep `context`
minimal and non-proprietary.

| Field | Allowed values |
|-------|----------------|
| `sourcePreference` | `official_docs`, `industry_best_practices`, `academic`, `mixed` |
| `freshness` | `latest`, `last_12_months`, `stable` |
| `depth` | `quick`, `standard`, `deep` |
| `outputFormat` | `brief`, `report`, `decision_memo`, `source_table` |
| `riskLevel` | `low`, `medium`, `high` |

Provider errors are mapped safely without leaking the raw provider body (`401/403`→auth,
`404`→model-not-found, `429`→rate/quota, `5xx`→provider-down). Output statuses are identical to
`generate_code_with_opus`: `OK`, `NEEDS_MORE_CONTEXT`, `REFUSED_UNSAFE`, `MODEL_ERROR`,
`BUDGET_EXCEEDED`.

## When to use `analyze_build_failure_with_opus`

`analyze_build_failure_with_opus` is a read-only **build/CI failure analysis** tool. It analyzes only
the failure log (and optional curated code/build context) explicitly provided in the tool input and
returns a structured diagnosis (summary, root-cause hypotheses, most-likely cause, evidence, fix
options, a **textual** minimal patch suggestion, tests to rerun, risks, safety notes, assumptions). It
does **not** read repository files, write files, execute commands, run Gradle, run tests, or apply
patches. The log/code/context are always treated as untrusted data — never as instructions, and any
patch is suggested as text only. It uses the **same guard pipeline** as the other Opus tools
(deny-list, secret scan, size limits, model allowlist, rate limit, budget) before any model call.

Use it for:

- diagnosing a compile/test/Gradle/Checkstyle/SpotBugs/static-analysis/runtime failure you can paste;
- getting ranked root-cause hypotheses, evidence, and minimal low-risk fix options;
- identifying which tests/tasks to rerun after a fix.

Do **not** use it for:

- proprietary/internal logs or source code unless the external provider is **explicitly approved**;
- anything that assumes the tool can read your repository or run the build — it cannot;
- applying fixes automatically — Cursor/user implements and reruns verification manually.

### Input fields

`analyze_build_failure_with_opus` requires `task`, `failureLog`, `failureType`, `language`,
`riskLevel`, `outputFormat`; `relevantCode`, `buildContext`, and `constraints` are optional. Keep
`failureLog`/`relevantCode`/`buildContext` minimal and non-proprietary.

| Field | Allowed values |
|-------|----------------|
| `failureType` | `compile`, `test`, `gradle`, `checkstyle`, `spotbugs`, `static_analysis`, `runtime`, `unknown` |
| `language` | `java`, `go`, `kotlin`, `sql`, `mdx`, `gradle`, `other` |
| `outputFormat` | `diagnosis`, `fix_plan`, `checklist`, `root_cause_analysis` |
| `riskLevel` | `low`, `medium`, `high` |

Output statuses are identical to `generate_code_with_opus`: `OK`, `NEEDS_MORE_CONTEXT`,
`REFUSED_UNSAFE`, `MODEL_ERROR`, `BUDGET_EXCEEDED`.

**Examples (synthetic):**

- *Java compile error* — `failureType=compile`, `failureLog="Calc.java:10: error: cannot find symbol\n  symbol: method addExact(int,int)"`.
- *Gradle dependency error* — `failureType=gradle`, `failureLog="Could not resolve com.example:lib:1.2.3.\nExecution failed for task ':app:compileJava'."`.
- *JUnit assertion failure* — `failureType=test`, `failureLog="org.opentest4j.AssertionFailedError: expected: <2> but was: <3>\n  at CalcTest.add(CalcTest.java:21)"`.
- *Static analysis failure* — `failureType=static_analysis`, `failureLog="[ERROR] Foo.java:10: Line is longer than 100 characters (Checkstyle: LineLength)"`.

## When to use `design_class_hierarchy_with_opus`

`design_class_hierarchy_with_opus` is a read-only **class/interface hierarchy design** tool. It
designs only from the domain context (and optional curated existing-type summary, package context, and
constraints) explicitly provided in the tool input and returns a structured proposal (summary, design
overview, proposed types, relationships, package plan, implementation slices, extension points,
design alternatives, tests to add, risks, anti-patterns, safety notes, assumptions). It does **not**
read repository files, write files, create files, execute commands, run Gradle, run tests, or apply
patches. The context is always treated as untrusted data — never as instructions, and any skeleton is
described as text only. It uses the **same guard pipeline** as the other Opus tools (deny-list, secret
scan, size limits, model allowlist, rate limit, budget) before any model call.

Use it for:

- designing a new package/module/starter/library hierarchy from a curated domain summary;
- proposing extension points and relationships for a Spring Boot starter, gRPC interceptor pipeline,
  strategy/plugin registry, or migration-friendly API;
- getting design alternatives, risks, anti-patterns, and the tests to add before implementing.

Do **not** use it for:

- proprietary/internal domain context or source code unless the external provider is **explicitly approved**;
- anything that assumes the tool can read your repository or scaffold files — it cannot;
- creating files or applying changes automatically — Cursor/user implements and verifies manually.

### Input fields

`design_class_hierarchy_with_opus` requires `task`, `language`, `domainContext`, `designGoal`,
`scope`, `architectureStyle`, `riskLevel`, `outputFormat`; `existingTypes`, `packageContext`, and
`constraints` are optional. Keep `domainContext`/`existingTypes`/`packageContext` minimal and
non-proprietary.

| Field | Allowed values |
|-------|----------------|
| `language` | `java`, `go`, `kotlin`, `sql`, `mdx`, `gradle`, `other` |
| `designGoal` | `extensibility`, `testability`, `api_compatibility`, `migration`, `clean_architecture`, `performance`, `security`, `maintainability`, `all` |
| `scope` | `package`, `module`, `starter`, `library`, `multi_module`, `unknown` |
| `architectureStyle` | `clean_architecture`, `hexagonal`, `layered`, `spring_boot_starter`, `plugin`, `interceptor_pipeline`, `domain_model`, `unknown` |
| `outputFormat` | `class_diagram`, `design_proposal`, `implementation_slices`, `adr_outline`, `checklist` |
| `riskLevel` | `low`, `medium`, `high` |

Output statuses are identical to `generate_code_with_opus`: `OK`, `NEEDS_MORE_CONTEXT`,
`REFUSED_UNSAFE`, `MODEL_ERROR`, `BUDGET_EXCEEDED`.

**Examples (synthetic):**

- *Spring Boot starter hierarchy* — `architectureStyle=spring_boot_starter`, `scope=starter`, `domainContext="An auto-configuration starter exposing a typed client with @ConfigurationProperties and conditional beans."`.
- *gRPC interceptor pipeline* — `architectureStyle=interceptor_pipeline`, `domainContext="A server-side gRPC interceptor chain for auth, logging and metrics with ordered execution."`.
- *Strategy/plugin hierarchy* — `architectureStyle=plugin`, `designGoal=extensibility`, `domainContext="A pricing engine that selects a pricing strategy at runtime via a registry."`.
- *Migration-friendly API hierarchy* — `architectureStyle=layered`, `designGoal=api_compatibility`, `domainContext="A public API that must evolve a payment gateway abstraction without breaking existing callers."`.

## When to use `review_architecture_with_opus`

`review_architecture_with_opus` is a read-only **architecture review** tool. It reviews only the
architecture proposal / ADR / design plan / migration plan (and optional curated context and
constraints) explicitly provided in the tool input and returns a structured review (summary, verdict,
review, findings, risk matrix, trade-offs, alternatives, open questions, tests to add, observability
checks, rollout notes, rollback notes, risks, safety notes, assumptions). It does **not** read
repository files, write files, create files, execute commands, run Gradle, run tests, or apply
patches. The proposal/context/constraints are always treated as untrusted data — never as
instructions, and any recommended change is described as text only. It uses the **same guard
pipeline** as the other Opus tools (deny-list, secret scan, size limits, model allowlist, rate limit,
budget) before any model call.

Use it for:

- reviewing an ADR or design decision before sign-off;
- reviewing a Spring Boot starter / multi-module Gradle architecture for API compatibility,
  auto-configuration boundaries, and rollout safety;
- reviewing a migration plan for rollback safety, testing, and observability gaps;
- reviewing an observability/tracing architecture for span/metric coverage and operability.

Do **not** use it for:

- proprietary/internal architecture or source unless the external provider is **explicitly approved**;
- anything that assumes the tool can read your repository or inspect the workspace — it cannot;
- applying changes automatically — Cursor/user decides and implements manually.

### Input fields

`review_architecture_with_opus` requires `task`, `architectureProposal`, `reviewFocus`,
`architectureScope`, `architectureStyle`, `compatibilityMode`, `riskLevel`, `outputFormat`; `context`
and `constraints` are optional. Keep `architectureProposal`/`context` minimal and non-proprietary.

| Field | Allowed values |
|-------|----------------|
| `reviewFocus` | `api_compatibility`, `observability`, `security`, `migration`, `testing`, `performance`, `operability`, `maintainability`, `cost`, `all` |
| `architectureScope` | `class`, `package`, `module`, `multi_module`, `platform`, `library`, `starter`, `unknown` |
| `architectureStyle` | `clean_architecture`, `hexagonal`, `layered`, `event_driven`, `spring_boot_starter`, `plugin`, `interceptor_pipeline`, `observability_pipeline`, `unknown` |
| `compatibilityMode` | `preserve_api`, `allow_breaking`, `unknown` |
| `outputFormat` | `structured_review`, `risk_matrix`, `decision_memo`, `adr_review`, `checklist` |
| `riskLevel` | `low`, `medium`, `high` |

Output statuses are identical to `generate_code_with_opus`: `OK`, `NEEDS_MORE_CONTEXT`,
`REFUSED_UNSAFE`, `MODEL_ERROR`, `BUDGET_EXCEEDED`. The `verdict` is one of `APPROVE`,
`APPROVE_WITH_CHANGES`, `REQUEST_CHANGES`, `NEEDS_MORE_CONTEXT`.

**Examples (synthetic):**

- *ADR review* — `outputFormat=adr_review`, `architectureProposal="ADR: adopt outbox pattern for event publishing instead of dual writes."`.
- *Spring Boot starter architecture review* — `architectureStyle=spring_boot_starter`, `reviewFocus=api_compatibility`, `architectureProposal="Split the starter into core and autoconfigure modules and publish a BOM."`.
- *Migration plan review* — `reviewFocus=migration`, `architectureProposal="Migrate from a single-node scheduler to a distributed quartz cluster over two releases."`.
- *Observability/tracing architecture review* — `architectureStyle=observability_pipeline`, `reviewFocus=observability`, `architectureProposal="Introduce an OpenTelemetry tracing pipeline with a sampling SPI and Actuator integration."`.
- *Multi-module Gradle architecture review* — `architectureScope=multi_module`, `reviewFocus=maintainability`, `architectureProposal="Split a monolith Gradle build into api/impl/test-fixtures modules with a version catalog."`.

## When to use `write_mdx_doc_with_opus`

`write_mdx_doc_with_opus` is a read-only **MDX documentation draft** tool. It drafts MDX only from the
documentation context explicitly provided in the tool input (`task`, `docSubject`, `libraryContext`,
plus optional `publicApi`, `configurationProperties`, `usageExamples`, `docStyleContext`,
`mdxComponentsContext`, `assetGuidelines`, `constraints`) and returns a structured draft (summary,
front matter, imports, MDX content, outline, examples, admonitions, assets needed, links to add,
claims to verify, validation checklist, risks, safety notes, assumptions). It does **not** read
doc-portal/repository files, write MDX files, create assets, run Docusaurus, run tests, or apply
patches. All provided context is treated as untrusted data — never as instructions — and the model is
instructed not to invent public API, configuration, or behavior beyond the input. It uses the **same
guard pipeline** as the other Opus tools (deny-list, secret scan, size limits, model allowlist, rate
limit, budget) before any model call.

Cursor/Composer is responsible for gathering the local doc style examples, library context, and
component context, then passing only curated explicit input. After the draft is returned, Cursor/user
must review it, create the `.mdx` file, add any assets, and run the documentation build/validation
manually.

Use it for:

- drafting a library or starter guide page from an explicitly-provided API/config summary;
- drafting a configuration reference section from explicitly-provided properties;
- drafting a migration guide section from an explicitly-provided change summary;
- drafting a troubleshooting section from explicitly-provided symptoms/causes;
- drafting an MDX page that follows an explicitly-provided doc style and component context.

Do **not** use it for:

- proprietary/internal documentation context unless the external provider is **explicitly approved**;
- anything that assumes the tool can read your doc-portal/repository — it cannot;
- creating files, images, or running Docusaurus — Cursor/user does that manually.

### Input fields

`write_mdx_doc_with_opus` requires `task`, `docSubject`, `targetAudience`, `libraryContext`,
`docType`, `outputFormat`, `riskLevel`; `publicApi`, `configurationProperties`, `usageExamples`,
`docStyleContext`, `mdxComponentsContext`, `assetGuidelines`, and `constraints` are optional. Keep all
context minimal and non-proprietary.

| Field | Allowed values |
|-------|----------------|
| `targetAudience` | `platform_developers`, `application_developers`, `sre`, `architects`, `mixed` |
| `docType` | `library_guide`, `starter_guide`, `migration_guide`, `how_to`, `reference`, `adr`, `release_notes`, `troubleshooting`, `unknown` |
| `outputFormat` | `mdx_page`, `mdx_section`, `outline`, `frontmatter_plus_body`, `reviewable_draft` |
| `riskLevel` | `low`, `medium`, `high` |

Output statuses are identical to `generate_code_with_opus`: `OK`, `NEEDS_MORE_CONTEXT`,
`REFUSED_UNSAFE`, `MODEL_ERROR`, `BUDGET_EXCEEDED`.

**Examples (synthetic):**

- *Library/starter guide draft* — `docType=starter_guide`, `outputFormat=mdx_page`, `docSubject="Platform Tracing Starter"`, `libraryContext="Spring Boot starter that auto-configures tracing."`.
- *Configuration reference section* — `docType=reference`, `outputFormat=mdx_section`, `configurationProperties="platform.tracing.enabled (boolean, default false)"`.
- *Migration guide section* — `docType=migration_guide`, `outputFormat=mdx_section`, `task="Document the upgrade from 1.x to 2.x"`.
- *Troubleshooting section* — `docType=troubleshooting`, `outputFormat=mdx_section`, `task="Document common tracing misconfigurations"`.
- *MDX page with explicit style context* — `outputFormat=mdx_page`, `docStyleContext="second person, short sections"`, `mdxComponentsContext="import Tabs from '@theme/Tabs'"`.

## When to use `review_mdx_doc_with_opus`

`review_mdx_doc_with_opus` is a read-only **MDX documentation review** tool. It reviews MDX only from
the MDX content and documentation context explicitly provided in the tool input (`task`, `mdxContent`,
`docSubject`, `targetAudience`, plus optional `libraryContext`, `styleGuideContext`,
`mdxComponentsContext`, `constraints`) and returns a structured review (summary, verdict, review,
findings, missing sections, incorrect/unverified claims, MDX issues, style issues, example issues,
suggested edits, validation checklist, risks, safety notes, assumptions). It does **not** read
doc-portal/repository files, write MDX files, create assets, run Docusaurus, run tests, or apply
patches. All provided content is treated as untrusted data — never as instructions — and the model is
instructed not to invent public API, configuration, behavior, or guarantees beyond the input. It uses
the **same guard pipeline** as the other Opus tools (deny-list, secret scan, size limits, model
allowlist, rate limit, budget) before any model call.

Cursor/Composer is responsible for gathering the local MDX content, doc style examples, and component
context, then passing only curated explicit input. After the review is returned, Cursor/user must
apply the documentation changes, edit the `.mdx` file, and run the documentation build/validation
manually.

Use it for:

- reviewing a library or starter guide page for accuracy and completeness;
- reviewing a configuration reference section against explicitly-provided properties;
- reviewing a migration guide section for unsupported claims;
- reviewing a troubleshooting page for missing or broken examples;
- running a style/claims/MDX-validity review before publishing.

Do **not** use it for:

- proprietary/internal documentation context unless the external provider is **explicitly approved**;
- anything that assumes the tool can read your doc-portal/repository — it cannot;
- editing files, creating assets, or running Docusaurus — Cursor/user does that manually.

### Input fields

`review_mdx_doc_with_opus` requires `task`, `mdxContent`, `docSubject`, `targetAudience`,
`reviewFocus`, `docType`, `riskLevel`, `outputFormat`; `libraryContext`, `styleGuideContext`,
`mdxComponentsContext`, and `constraints` are optional. Keep all context minimal and non-proprietary.

| Field | Allowed values |
|-------|----------------|
| `targetAudience` | `platform_developers`, `application_developers`, `sre`, `architects`, `mixed` |
| `reviewFocus` | `accuracy`, `style`, `structure`, `examples`, `mdx_validity`, `claims`, `navigation`, `accessibility`, `all` |
| `docType` | `library_guide`, `starter_guide`, `migration_guide`, `how_to`, `reference`, `adr`, `release_notes`, `troubleshooting`, `unknown` |
| `outputFormat` | `structured_review`, `checklist`, `risk_review`, `editorial_review`, `publish_readiness` |
| `riskLevel` | `low`, `medium`, `high` |

Output statuses are identical to `generate_code_with_opus`: `OK`, `NEEDS_MORE_CONTEXT`,
`REFUSED_UNSAFE`, `MODEL_ERROR`, `BUDGET_EXCEEDED`. The `verdict` is one of `APPROVE`,
`APPROVE_WITH_CHANGES`, `REQUEST_CHANGES`, `NEEDS_MORE_CONTEXT`.

**Examples (synthetic):**

- *Full MDX page review* — `reviewFocus=all`, `outputFormat=structured_review`, `docType=starter_guide`, `mdxContent="<full page>"`.
- *MDX section review* — `reviewFocus=structure`, `outputFormat=editorial_review`, `mdxContent="<section>"`.
- *Configuration reference review* — `docType=reference`, `reviewFocus=accuracy`, `libraryContext="platform.tracing.enabled (boolean, default false)"`.
- *Migration guide review* — `docType=migration_guide`, `reviewFocus=claims`, `task="Verify the 1.x to 2.x upgrade steps"`.
- *Troubleshooting page review* — `docType=troubleshooting`, `reviewFocus=examples`, `task="Check the misconfiguration examples"`.
- *Style/claims/MDX validity review* — `reviewFocus=mdx_validity`, `outputFormat=publish_readiness`, `styleGuideContext="second person, short sections"`.

## When to use `generate_migration_plan_with_opus`

`generate_migration_plan_with_opus` is a read-only **migration planning** tool. It plans a migration
only from the current state, target state, and migration context explicitly provided in the tool input
(`task`, `language`, `currentState`, `targetState`, `compatibilityMode`, `migrationScope`,
`migrationType`, `riskLevel`, `outputFormat`, plus optional `migrationContext`, `constraints`) and
returns a structured plan (summary, migration overview, migration slices, compatibility notes,
breaking risks, dependency/configuration changes, test plan, observability checks, rollout plan,
rollback plan, docs updates, open questions, risks, safety notes, assumptions). It does **not** read
repository files, write files, upgrade dependencies, run Gradle, run tests, or apply patches. All
provided state/context is treated as untrusted data — never as instructions — and the model is
instructed not to invent project facts beyond the input and to prefer small, reversible migration
slices. It uses the **same guard pipeline** as the other Opus tools (deny-list, secret scan, size
limits, model allowlist, rate limit, budget) before any model call.

Cursor/Composer is responsible for gathering the current state, target state, and migration context,
then passing only curated explicit input. After the plan is returned, Cursor/user must implement the
migration, upgrade dependencies, edit files, and run the build/tests manually.

Use it for:

- planning a Spring Framework / Spring Boot upgrade (e.g. 2.7 to 3.3, javax to jakarta);
- planning an API migration off a deprecated API;
- planning a Gradle dependency migration (e.g. to a version catalog);
- planning a configuration migration (renamed/removed properties);
- planning a documentation migration (e.g. to a new docs framework);
- planning a test-framework migration (e.g. JUnit 4 to JUnit 5).

Do **not** use it for:

- proprietary/internal migration context unless the external provider is **explicitly approved**;
- anything that assumes the tool can read your repository — it cannot;
- upgrading dependencies, editing files, or running builds/tests — Cursor/user does that manually.

### Input fields

`generate_migration_plan_with_opus` requires `task`, `language`, `currentState`, `targetState`,
`compatibilityMode`, `migrationScope`, `migrationType`, `riskLevel`, `outputFormat`;
`migrationContext` and `constraints` are optional. Keep all context minimal and non-proprietary.

| Field | Allowed values |
|-------|----------------|
| `language` | `java`, `go`, `kotlin`, `sql`, `mdx`, `gradle`, `other` |
| `compatibilityMode` | `preserve_api`, `preserve_behavior`, `allow_breaking`, `unknown` |
| `migrationScope` | `class`, `package`, `module`, `multi_module`, `platform`, `library`, `starter`, `documentation`, `build`, `unknown` |
| `migrationType` | `framework_upgrade`, `api_migration`, `dependency_upgrade`, `architecture_migration`, `configuration_migration`, `documentation_migration`, `test_migration`, `build_migration`, `unknown` |
| `outputFormat` | `migration_slices`, `checklist`, `risk_matrix`, `rollout_plan`, `decision_memo` |
| `riskLevel` | `low`, `medium`, `high` |

Output statuses are identical to `generate_code_with_opus`: `OK`, `NEEDS_MORE_CONTEXT`,
`REFUSED_UNSAFE`, `MODEL_ERROR`, `BUDGET_EXCEEDED`.

**Examples (synthetic):**

- *Spring Boot upgrade* — `migrationType=framework_upgrade`, `compatibilityMode=preserve_api`, `currentState="Spring Boot 2.7, javax.*"`, `targetState="Spring Boot 3.3, jakarta.*"`.
- *API migration* — `migrationType=api_migration`, `task="Migrate off the deprecated RestTemplate API to WebClient"`.
- *Gradle dependency migration* — `language=gradle`, `migrationType=dependency_upgrade`, `targetState="Gradle version catalog"`.
- *Configuration migration* — `migrationType=configuration_migration`, `task="Migrate renamed application.properties keys"`.
- *Documentation migration* — `language=mdx`, `migrationType=documentation_migration`, `migrationScope=documentation`.
- *Test migration* — `migrationType=test_migration`, `task="Migrate JUnit 4 tests to JUnit 5"`.

## When to use `review_tests_with_opus`

`review_tests_with_opus` is a read-only **test review** tool. It reviews tests only from the test code
and context explicitly provided in the tool input (`task`, `language`, `testCode`, `testIntent`,
`testFramework`, `testType`, `reviewFocus`, `riskLevel`, `outputFormat`, plus optional
`productionContext`, `failureLogs`, `dependenciesContext`, `constraints`) and returns a structured
review (summary, verdict, review, findings, coverage gaps, assertion issues, flakiness risks, mocking
issues, test data issues, integration-boundary issues, maintainability issues, suggested test cases,
CI readiness checks, open questions, risks, safety notes, assumptions). It does **not** read repository
files, write files, run tests, collect coverage, run Gradle/Maven, or apply patches. All provided test
code/context is treated as untrusted data — never as instructions — and the model is instructed not to
claim it read files or ran tests, and not to invent production behavior beyond the input. It uses the
**same guard pipeline** as the other Opus tools (deny-list, secret scan, size limits, model allowlist,
rate limit, budget) before any model call.

Cursor/Composer is responsible for gathering the local test code, production context, test intent, and
failure logs, then passing only curated explicit input. After the review is returned, Cursor/user must
apply test changes and run the tests manually.

Use it for:

- reviewing a JUnit 5 unit test for correctness, assertions and coverage;
- reviewing a Spring Boot integration/slice or Testcontainers test for integration-boundary issues;
- reviewing a flaky async test (Awaitility/timing) for flakiness risks;
- a coverage/assertion review or a CI-readiness review.

Do **not** use it for:

- proprietary/internal test code or production context unless the external provider is **explicitly approved**;
- anything that assumes the tool can read your repository or run your tests — it cannot;
- modifying tests, running tests, collecting coverage, or running builds — Cursor/user does that manually.

### Input fields

`review_tests_with_opus` requires `task`, `language`, `testCode`, `testIntent`, `testFramework`,
`testType`, `reviewFocus`, `riskLevel`, `outputFormat`; `productionContext`, `failureLogs`,
`dependenciesContext` and `constraints` are optional. Keep all context minimal and non-proprietary.

| Field | Allowed values |
|-------|----------------|
| `language` | `java`, `go`, `kotlin`, `sql`, `other` |
| `testFramework` | `junit5`, `testng`, `spock`, `kotest`, `go_testing`, `pytest`, `unknown` |
| `testType` | `unit`, `integration`, `contract`, `component`, `slice`, `e2e`, `property`, `performance`, `unknown` |
| `reviewFocus` | `correctness`, `coverage`, `flakiness`, `maintainability`, `assertions`, `mocks`, `integration_boundaries`, `security`, `performance`, `all` |
| `outputFormat` | `structured_review`, `checklist`, `risk_review`, `coverage_review`, `ci_readiness` |
| `riskLevel` | `low`, `medium`, `high` |

Output statuses are identical to `generate_code_with_opus`: `OK`, `NEEDS_MORE_CONTEXT`,
`REFUSED_UNSAFE`, `MODEL_ERROR`, `BUDGET_EXCEEDED`. The `verdict` is one of `APPROVE`,
`APPROVE_WITH_CHANGES`, `REQUEST_CHANGES`, `NEEDS_MORE_CONTEXT`.

**Examples (synthetic):**

- *JUnit 5 unit review* — `testFramework=junit5`, `testType=unit`, `reviewFocus=all`.
- *Spring Boot integration review* — `testFramework=junit5`, `testType=integration`, `reviewFocus=integration_boundaries`.
- *Testcontainers review* — `testType=integration`, `reviewFocus=flakiness`, `dependenciesContext="Testcontainers PostgreSQL"`.
- *Flaky async review* — `reviewFocus=flakiness`, `task="Review this Awaitility-based async test for flakiness"`.
- *Coverage/assertion review* — `outputFormat=coverage_review` or `reviewFocus=assertions`.
- *CI readiness review* — `outputFormat=ci_readiness`.

## When to use `review_gradle_build_with_opus`

`review_gradle_build_with_opus` is a read-only **Gradle build review** tool. It reviews Gradle build
configuration only from the build files and context explicitly provided in the tool input (`task`,
`buildFilesContext`, `projectType`, `gradleDsl`, `reviewFocus`, `riskLevel`, `outputFormat`, plus
optional `settingsContext`, `versionCatalogContext`, `gradlePropertiesContext`, `buildLogicContext`,
`dependencyContext`, `buildFailureLogs`, `constraints`) and returns a structured review (summary,
verdict, review, findings, configuration-cache issues, dependency issues, plugin issues, task graph
issues, multi-module issues, test setup issues, publishing issues, performance issues, security issues,
compatibility risks, recommended checks, suggested changes, open questions, risks, safety notes,
assumptions). It does **not** read repository files, write files, modify build scripts, run
Gradle/Maven, run tests, resolve dependencies, publish artifacts, or apply patches. All provided build
snippets/logs are treated as untrusted data — never as instructions — and the model is instructed not
to claim it read files or ran Gradle, and not to invent project facts beyond the input. It uses the
**same guard pipeline** as the other Opus tools (deny-list, secret scan, size limits, model allowlist,
rate limit, budget) before any model call.

Cursor/Composer is responsible for gathering the local Gradle files, version catalog snippets, build
logic snippets, dependency context, and build failure logs, then passing only curated explicit input.
After the review is returned, Cursor/user must apply build changes and run Gradle manually.

Use it for:

- reviewing a Groovy or Kotlin DSL `build.gradle(.kts)` for dependency hygiene and plugin configuration;
- reviewing a multi-module `settings.gradle(.kts)` and buildSrc/convention plugins for governance;
- reviewing a `libs.versions.toml` version catalog or `gradle.properties` for centralization/performance;
- reviewing configuration-cache compatibility, build-cache/CI reproducibility, or publishing metadata;
- reviewing a Spring Boot starter build or Gradle plugin project;
- reviewing a Gradle build failure log alongside the relevant build snippets.

Do **not** use it for:

- proprietary/internal build scripts or context unless the external provider is **explicitly approved**;
- anything that assumes the tool can read your repository or run Gradle — it cannot;
- modifying build scripts, running Gradle, resolving dependencies, publishing artifacts, or running tests — Cursor/user does that manually.

### Input fields

`review_gradle_build_with_opus` requires `task`, `buildFilesContext`, `projectType`, `gradleDsl`,
`reviewFocus`, `riskLevel`, `outputFormat`; `settingsContext`, `versionCatalogContext`,
`gradlePropertiesContext`, `buildLogicContext`, `dependencyContext`, `buildFailureLogs` and
`constraints` are optional. Keep all context minimal and non-proprietary.

| Field | Allowed values |
|-------|----------------|
| `projectType` | `java_library`, `spring_boot_service`, `spring_boot_starter`, `gradle_plugin`, `multi_module_platform`, `documentation`, `unknown` |
| `gradleDsl` | `groovy`, `kotlin`, `mixed`, `unknown` |
| `reviewFocus` | `dependency_management`, `plugin_configuration`, `configuration_cache`, `task_graph`, `multi_module_governance`, `test_setup`, `publishing`, `performance`, `security`, `all` |
| `outputFormat` | `structured_review`, `checklist`, `risk_review`, `build_health`, `migration_review` |
| `riskLevel` | `low`, `medium`, `high` |

Output statuses are identical to `generate_code_with_opus`: `OK`, `NEEDS_MORE_CONTEXT`,
`REFUSED_UNSAFE`, `MODEL_ERROR`, `BUDGET_EXCEEDED`. The `verdict` is one of `APPROVE`,
`APPROVE_WITH_CHANGES`, `REQUEST_CHANGES`, `NEEDS_MORE_CONTEXT`.

**Examples (synthetic):**

- *Groovy DSL library review* — `projectType=java_library`, `gradleDsl=groovy`, `reviewFocus=dependency_management`.
- *Kotlin DSL review* — `gradleDsl=kotlin`, `reviewFocus=plugin_configuration`.
- *Multi-module settings review* — `projectType=multi_module_platform`, `reviewFocus=multi_module_governance`.
- *Version catalog review* — `reviewFocus=dependency_management`, `versionCatalogContext="[versions]..."`.
- *Configuration cache review* — `reviewFocus=configuration_cache`, `riskLevel=high`.
- *Spring Boot starter review* — `projectType=spring_boot_starter`, `reviewFocus=all`.
- *Publishing review* — `projectType=gradle_plugin`, `reviewFocus=publishing`.
- *Build failure log review* — `buildFailureLogs="Could not resolve ... > FAILURE"`, `reviewFocus=dependency_management`.
