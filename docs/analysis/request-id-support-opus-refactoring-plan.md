# Opus Refactoring Plan — RequestIdSupport core utility

> **Статус:** APPROVED. **Codex readiness:** YES. Документ фиксирует утверждённый план и итоговый refactoring scope.

## 1. Executive Verdict

**Решение: Option A — `@UtilityClass RequestIdSupport` в `space.br1440.platform.tracing.core.propagation`.**

Старый дизайн держал request-id validation как API SPI:

- `api.propagation.RequestIdSupport` — интерфейс с `MAX_LENGTH`;
- `api.propagation.RequestIdSupports` — eager `ServiceLoader` holder;
- `core.propagation.RequestIdSupportImpl` — единственная implementation;
- `META-INF/services/...RequestIdSupport` — descriptor обратно в core.

Это создавало цикл `core -> api -> ServiceLoader -> core`, ложную extension point, constant-interface antipattern и classpath trap при отсутствии core. Поскольку решение ещё не вышло в production, compatibility aliases, deprecated bridges и wrappers не вводятся.

## 2. Independent Repository Findings

| Finding | Evidence | Result |
|---|---|---|
| Три production call-site | `DefaultInboundTraceControlExtractor`, `TraceResponseHeaderServletFilter`, `TraceResponseHeaderWebFilter` | Все переводятся на static utility |
| Единственный provider | `platform-tracing-core/src/main/resources/META-INF/services/...RequestIdSupport` | SPI не является реальной extension point |
| Логика pure | `RequestIdSupportImpl`: trim, char-loop allowlist `[A-Za-z0-9_-]`, reject oversize, UUID fallback | Переносится 1:1 |
| Autoconfigure может импортировать core utility | ArchUnit ограничивает только `core.runtime.versioned`, а не весь core | webmvc/webflux import допустим |
| Blanket `api.. -> ServiceLoader` сейчас невозможен | `OtelTraceparentReaders` всё ещё использует `ServiceLoader` | Нужен scoped guard/temporary exception |

## 3. Target Design

```java
@UtilityClass
public final class RequestIdSupport {
    public static final int MAX_LENGTH = 128;

    @Nullable
    public static String sanitizeOrNull(@Nullable String raw) { ... }

    @Nonnull
    public static String resolve(@Nullable String incoming) { ... }
}
```

Utility живёт в `platform-tracing-core`, не зависит от Spring, OTel, Servlet, Reactor или `ServiceLoader`.

## 4. Implementation Slices

1. Создать `core.propagation.RequestIdSupport` и static unit tests.
2. Переключить три call-site на `RequestIdSupport.sanitizeOrNull(...)` / `RequestIdSupport.resolve(...)`.
3. Удалить API SPI/holder, core impl, ServiceLoader descriptor и старый impl-test.
4. Заменить ArchUnit guard на targeted rules: нет `RequestIdSupport*` в api, нет request-id ServiceLoader holder, core utility dependency-light.
5. Обновить ADR и точечные docs/Javadoc ссылки.

## 5. Verification Plan

```powershell
.\gradlew.bat :platform-tracing-api:compileJava `
              :platform-tracing-core:compileJava `
              :platform-tracing-autoconfigure-webmvc:compileJava `
              :platform-tracing-autoconfigure-webflux:compileJava `
              --no-daemon

.\gradlew.bat :platform-tracing-core:test `
              :platform-tracing-test:test `
              --no-daemon
```

Residual search must show no Java references to `RequestIdSupports`, `RequestIdSupportImpl`, or `api.propagation.RequestIdSupport`. `ServiceLoader` may remain for unrelated SPI such as `OtelTraceparentReaders`.

## 6. Follow-Ups Out Of Scope

- Configurable `RequestIdPolicy`.
- Value object `RequestId`.
- Multiple request-id header names.
- Per-service max length.
- Traceparent `ServiceLoader` refactoring.
