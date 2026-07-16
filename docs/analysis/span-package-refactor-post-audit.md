# Post-audit: рефакторинг `api.span`

Дата: 2026-07-16  
Решение: [ADR-api-span-package-boundary](../decisions/ADR-api-span-package-boundary.md)

## Итог

Рефакторинг реализован без compatibility aliases и `@Deprecated` bridges. Стабильные root/spec
контракты не менялись. Public enrichment теперь platform-owned и generic-only; OTel category path,
API sanitizers и API `SemconvKeys` удалены.

## Изменённая граница

Создано:

- `api.span.enrich.SpanEnricher`;
- `core.enrichment.DefaultSpanEnricher`;
- `core.semconv.SemconvKeys`;
- package-private `core.manual.UrlSanitizer`;
- sampler/exporter и sanitizer tests;
- ADR и этот post-audit.

Удалено:

- `api.span.enrich.SpanEnrichment`;
- `core.enrichment.DefaultSpanEnrichment`;
- old concrete `core.enrichment.SpanEnricher`;
- `PlatformSpanContextKeys` и marker characterization tests;
- `api.semconv.SemconvKeys`;
- `api.span.sanitize.SqlSanitizer`, `UrlSanitizer` и старый объединённый test.

Сохранено без shape/FQN изменений:

- `TraceOperations`, `SpanFactory`, `SpanCategory`, `SpanResult`, `RemoteSpanLink`;
- весь `api.span.spec`;
- propagation/control contracts на `opentelemetry-context`;
- scrubbing SPI и sampling/export/control packages.

## Проверенные инварианты

- [x] `SpanEnricher` имеет единственный метод `enrichCurrentSpan`.
- [x] `GenericSpanEnrichment` имеет только `requestId`, `userHash`, `result`.
- [x] `TraceOperations` по-прежнему имеет только `traceContext()` и `spans()`.
- [x] Удалённые public FQN отсутствуют в API JAR.
- [x] API JAR содержит только `GenericSpanEnrichment.class` и `SpanEnricher.class` в
  `api/span/enrich`.
- [x] В API main sources нет импортов `io.opentelemetry.api.*`.
- [x] `dependencyInsight` не находит `opentelemetry-api` в API `compileClasspath`.
- [x] `opentelemetry-context` сохранён только для утверждённых propagation/control контрактов.
- [x] `SemconvKeys` находится в `core.semconv`; old API FQN отсутствует.
- [x] `businessTag`, category enrichment и category marker отсутствуют в production code.
- [x] URL sanitizer package-private и расположен рядом с `DefaultHttpTracing`.
- [x] SQL sanitizer отсутствует.
- [x] Scalar, non-empty list и empty list attributes видны custom sampler до `startSpan()`.
- [x] Exported `SpanData` сохраняет string/long/double/boolean list types.
- [x] Канонический `platform.trace.type` устанавливается после spec attributes.
- [x] Spring публикует один bean по API `SpanEnricher`; пользовательский bean его переопределяет.
- [x] ArchUnit запрещает возврат удалённых API/marker/sanitizer границ.

## Выполненные проверки

Успешно:

```powershell
.\gradlew.bat :platform-tracing-api:test :platform-tracing-core:test `
  :platform-tracing-spring-boot-autoconfigure:test :platform-tracing-test:test --no-daemon

.\gradlew.bat :platform-tracing-samples:compileJava `
  :platform-tracing-bench:compileJmhJava --no-daemon

.\gradlew.bat pr4ArchitectureFitnessVerify --rerun-tasks --no-daemon

.\gradlew.bat :platform-tracing-api:dependencyInsight `
  --dependency opentelemetry-api --configuration compileClasspath --no-daemon
```

Первый aggregate architecture run остановился на одном флейке вне scope:
`PlatformDropOldestExportSpanProcessorLifecycleTest` (1 failure из 747). Изолированный повтор с
`--rerun-tasks` прошёл, после чего полный `pr4ArchitectureFitnessVerify --rerun-tasks` прошёл.

Docker-backed E2E не запускался: Docker client доступен, но endpoint
`192.168.100.70:2375` не отвечает. Это инфраструктурное ограничение локальной среды, а не
functional failure рефакторинга.

## Остаточный риск

- Внешние service repositories не входят в workspace; их consumers остаются
  `INSUFFICIENT_EVIDENCE`. Breaking change принят намеренно для pre-production API.
- Полное удаление `opentelemetry-context` из API не выполнялось: это отдельное решение о
  propagation/control boundary.
