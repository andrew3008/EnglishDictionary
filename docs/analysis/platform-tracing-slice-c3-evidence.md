# Platform Tracing Slice C3 Evidence

> Дата: 2026-07-19  
> Ветка: `feature/runtime-control-hardening`  
> Базовый HEAD перед изменениями C2/C3: `b0a5272`

## 1. Результат

Slice C3 завершён. Зависимость `platform-tracing-spring-boot-autoconfigure` от
`platform-tracing-core` переведена из `api` в `implementation`. Platform implementation
больше не попадает на compile classpath внешнего starter consumer, но остаётся на runtime
classpath согласно опубликованным metadata.

WebMVC и WebFlux main-sources больше не импортируют core-типы напрямую. Существующее
поведение request-id и remote-service MDC делегировано через stateless implementation
bridges общего autoconfigure-модуля с JDK-only публичными сигнатурами. Эти bridges не
вводят identity API Slice M, не хранят request state и не обходят CP-1.

## 2. Published Metadata Gate

Добавлен `c3PublishedMetadataConsumerVerify`, который:

- публикует BOM, API, core, autoconfigure, WebMVC и servlet starter в изолированный
  временный Maven repository;
- запускает отдельный Gradle consumer без project dependencies и `mavenLocal`;
- проверяет generated POM и Gradle `.module` variants;
- подтверждает наличие `opentelemetry-api` и отсутствие platform implementation на
  `compileClasspath`;
- подтверждает наличие `platform-tracing-core` на `runtimeClasspath`;
- компилирует consumer и запускает минимальный Spring context;
- отдельно подтверждает, что import `OtelTracingRuntime` без explicit core dependency
  завершается ожидаемой ошибкой компиляции.

## 3. Architecture Gate

Добавлено общее ArchUnit-правило `WEB_AUTOCONFIGURE_MAIN_NO_CORE_IMPL`. Оно применяется
в WebMVC и WebFlux модулях и запрещает production-классам web-autoconfigure напрямую
зависеть от `space.br1440.platform.tracing.core..`.

## 4. Verification

| Проверка | Результат |
|---|---|
| Spring autoconfigure compile main/test | PASS |
| WebMVC tests, включая ArchUnit | PASS |
| WebFlux tests, включая ArchUnit | PASS |
| `c3PublishedMetadataConsumerVerify` | PASS |
| Positive external consumer compile/startup | PASS |
| Negative external import `OtelTracingRuntime` | EXPECTED COMPILE FAILURE |
| `pr4ArchitectureFitnessVerify` | PASS |
| `pr1ModuleTaxonomyVerify` | PASS |
| `build --no-daemon` | PASS |

Команды:

```powershell
.\gradlew.bat :platform-tracing-spring-boot-autoconfigure:compileJava `
  :platform-tracing-spring-boot-autoconfigure:compileTestJava `
  :platform-tracing-autoconfigure-webmvc:test `
  :platform-tracing-autoconfigure-webflux:test --no-daemon
.\gradlew.bat c3PublishedMetadataConsumerVerify `
  pr4ArchitectureFitnessVerify pr1ModuleTaxonomyVerify --no-daemon
```

## 5. Residual Gates

Slice G остаётся `NO-GO`: `CP-2 = CLARIFICATION REQUIRED`, конкретный вариант Sampling
SPI не утверждён. C3 не изменяет sampling production code и не подменяет решение CP-2.
