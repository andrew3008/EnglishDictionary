# План устранения избыточной сложности в Java Agent span processors

> Дата: 2026-08-08
> Репозиторий: `E:\Platform_Traces`
> Baseline исследования: `d1abf6f6`
> Пакет: `space.br1440.platform.tracing.otel.javaagent.processor`
> Статус: `IMPLEMENTATION PLAN / REVIEW REQUIRED`
> Тип изменения: pre-production cleanup; deprecated-обертки и совместимые aliases не создаются.

## 1. Цель

Уменьшить количество фиктивного состояния, мертвых метрик, пустых lifecycle hooks и
необоснованных package boundaries в Agent-side processor pipeline, сохранив:

- порядок обработки span'а;
- fail-closed validation и scrubbing;
- изоляцию нефатальных ошибок между processor'ами;
- проброс `TracingValidationException` из `onEnding`;
- JMX owner/generation semantics;
- watchdog timeouts;
- DROP_OLDEST export semantics;
- отсутствие второго SDK/runtime owner.

План не ставит целью сократить число классов любой ценой. Класс остается отдельным, если
владеет самостоятельным runtime-контрактом, состоянием или фазой OpenTelemetry SDK.

## 2. Фактический baseline

В production-пакете находятся 10 классов:

| Класс | Фаза/роль | Решение |
|---|---|---|
| `BaggageSpanProcessor` | `onStart`, controlled baggage projection | оставить |
| `EnrichingSpanProcessor` | `onEnding`, platform/DB/remote enrichment | оставить, удалить неподтвержденную проекцию |
| `ScrubbingSpanProcessor` | `onEnding`, sanitizer | оставить |
| `ValidatingSpanProcessor` | `onEnding`, fail-closed validation | оставить |
| `ClassificationSpanProcessor` | `onEnding`, latency/priority classification | оставить |
| `SpanWatchdogProcessor` | `onStart`/`onEnd` + scheduler | оставить, убрать ложную `onEnding`-роль |
| `MetricsSpanProcessor` | `onEnd`, span-limit counters | сузить и переименовать |
| `PlatformCompositeSpanProcessor` | ordered execution, fault isolation, diagnostics | оставить custom implementation |
| `PlatformDropOldestExportSpanProcessor` | bounded queue/export lifecycle | оставить без внутренней декомпозиции в этом плане |
| `processor.utils.IpPrefixTruncator` | scrubbing-only helper | перенести к владельцу scrubbing |

Pipeline создается в
`platform-tracing-otel-javaagent-extension/src/main/java/space/br1440/platform/tracing/otel/javaagent/factory/PlatformSpanProcessorFactory.java`
в порядке:

```text
Baggage -> Enriching -> Scrubbing -> Validating -> Classification -> Watchdog -> Metrics
```

Порядок является поведенческим контрактом и фиксируется
`PipelinePolicyCharacterizationTest`. Его изменение в этот cleanup не входит.

## 3. Подтвержденный overengineering

### 3.1 Фиктивный validation owner

При `platform.tracing.validation.enabled=false` фабрика не добавляет
`ValidatingSpanProcessor` в SDK pipeline, но создает второй экземпляр только ради
`jmxRegistrar.setValidating(...)`.

Это дает противоречивое наблюдение: JMX сообщает `ownerPresent=true`, хотя processor не
зарегистрирован и не получает `onEnding`. `OwnerRegistrationSlot` и
`PlatformValidationControl` уже умеют корректно кодировать отсутствие owner.

**Целевое состояние:** выключенная validation оставляет `validatingSlot` пустым;
`ownerPresent=false`, generation не увеличивается, value-поля получают утвержденные
absent placeholders. Нельзя создавать диагностический processor, не участвующий в pipeline.

### 3.2 Мертвые счетчики в `MetricsSpanProcessor`

`endedSpans`, `errorSpans` и `timeoutSpans` читаются только unit-тестом самого класса.
Production JMX публикует исключительно:

- dropped attributes;
- dropped events;
- dropped links.

Три лишних `LongAdder`, проверки status/timeout и getters создают hot-path работу без
операционного потребителя.

**Целевое состояние:** processor владеет только тремя span-limit counters и называется
`SpanLimitsMetricsProcessor`. `MetricsSpanProcessor` удаляется без deprecated alias.

Имя owner синхронно меняется в Agent и Spring wire-contract copies и в
`ProcessorCompositeErrorKey`. Поскольку решение еще не публиковалось, compatibility alias
и schema-version bump не нужны; golden/parity gate обязан доказать, что обе стороны изменены
атомарно.

Публичное configuration property `platform.tracing.metrics.enabled` в данном плане не
переименовывается: это отдельный configuration-contract change, не условие очистки package.
Его Javadoc должен точно указывать, что переключатель включает сбор потерь из-за SDK
span limits, а не все метрики tracing-платформы.

### 3.3 Ложная `onEnding`-роль watchdog

`SpanWatchdogProcessor` реализует internal OTel `ExtendedSpanProcessor`, но его
`onEnding` пуст, а `isOnEndingRequired()` всегда возвращает `false`. Комментарий прямо
объясняет это унификацией API, которая runtime не требуется.

**Целевое состояние:** watchdog реализует публичный `SpanProcessor`; пустые методы и
зависимость от internal Extended API удаляются. `onStart`, `onEnd`, scheduler,
`forceFlush`, `shutdown` и JMX counters сохраняются.

### 3.4 Псевдослой `processor.utils`

`IpPrefixTruncator` используется только scrubbing-кодом. Одноэлементный общий `utils`
package скрывает ownership и создает ложную возможность повторного использования.

**Целевое состояние:** класс переносится в
`space.br1440.platform.tracing.otel.javaagent.scrubbing.engine`, получает минимально
необходимую visibility; старый package удаляется. `package-info.java` не добавляется.

### 3.5 Неподтвержденная `platform.policy.version` baggage projection

`EnrichingSpanProcessor` читает baggage key `platform.policy.version` и переносит его в
одноименный span attribute. В текущем production-коде не найден producer или downstream
consumer; есть только один unit-тест и архитектурные документы. Одновременно baggage
projection уже принадлежит `BaggageSpanProcessor`, поэтому скрытая special-case проекция в
enricher размывает ownership.

Удаление разрешено только после исполняемого zero-consumer gate:

```powershell
rg -n --hidden --glob '!**/build/**' --glob '!docs/analysis/**' `
  'platform\.policy\.version' .
```

Дополнительно проверяются Collector/Jaeger configuration, dashboards, alerts, tests,
fixtures и deployment manifests. Если найден реальный consumer, Slice 2 останавливается:
ключ получает одного явного owner и контракт, а не удаляется по числу Java references.

## 4. Что не является overengineering

### 4.1 `PlatformCompositeSpanProcessor`

Stock OTel composite поддерживает `ExtendedSpanProcessor`, но не предоставляет одновременно:

- per-delegate nonfatal fault isolation;
- deterministic error counters по processor name;
- JMX read model этих counters;
- специальный проброс `TracingValidationException` из `onEnding`.

Поэтому замена на `SpanProcessor.composite(...)` запрещена, пока отдельный evidence packet
не докажет эквивалентность `ADR-composite-processor.md` и
`ADR-processor-errors-metric.md`.

Допустимое упрощение: после characterization сделать `shutdown()` идемпотентным и удалить
самостоятельный `close()` loop, если тест подтверждает, что стандартный `close()` вызывает
тот же единичный shutdown path. Нельзя вводить generic phase executor ради устранения пяти
небольших циклов: различия в обработке validation, logging и result aggregation существенны.

### 4.2 `PlatformDropOldestExportSpanProcessor`

Stock BatchSpanProcessor не реализует утвержденную DROP_OLDEST policy и связанные
диагностические категории. Класс является отдельной export concurrency subsystem.

В этом плане запрещены:

- замена на stock BSP;
- дробление на queue/worker/coordinator abstractions без отдельного concurrency review;
- package move только ради симметрии;
- изменение flush/shutdown/drop semantics.

### 4.3 Scrubbing, validation и watchdog

Размер этих классов сам по себе не является доказательством overengineering. Они владеют
разными security/runtime contracts и mutable diagnostics. Их слияние запрещено.

## 5. Целевая структура

```text
processor/
  BaggageSpanProcessor
  ClassificationSpanProcessor
  EnrichingSpanProcessor
  PlatformCompositeSpanProcessor
  PlatformDropOldestExportSpanProcessor
  ScrubbingSpanProcessor
  SpanLimitsMetricsProcessor
  SpanWatchdogProcessor
  ValidatingSpanProcessor

scrubbing/engine/
  IpPrefixTruncator
```

Не создаются:

- `AbstractPlatformSpanProcessor`;
- собственный phase enum;
- processor registry или ServiceLoader SPI;
- factory на каждый processor;
- подпакет на каждый класс;
- новый модуль;
- marker interfaces поверх OTel `SpanProcessor`;
- wrappers для no-op/disabled processor'ов.

## 6. Последовательность реализации

Каждый slice является отдельным reviewable commit. Следующий slice начинается только после
green exit предыдущего. Существующие незакоммиченные propagation-изменения не смешиваются
с этой работой.

### Slice 0. Baseline и characterization

**Изменения:** только tests/evidence, без production delta.

1. Зафиксировать exact processor list и order в factory.
2. Добавить factory/JMX characterization:
   - validation enabled -> один и тот же экземпляр находится в composite и owner slot;
   - validation disabled -> текущий fake owner документируется красным target-тестом или
     отдельным before-state assertion;
   - disabled watchdog/metrics/scrubbing не публикуют owner.
3. Зафиксировать composite contracts:
   - exception одного delegate не останавливает следующий;
   - fatal throwable пробрасывается;
   - `TracingValidationException` пробрасывается только из `onEnding`;
   - per-name counter увеличивается ровно один раз;
   - duplicate simple names агрегируются под текущим wire key.
4. Зафиксировать lifecycle behavior `forceFlush`, `shutdown`, повторный shutdown и `close`.
5. Выполнить zero-consumer inventory для `platform.policy.version`.
6. Сохранить before/after JMH baseline для `CompositePipelineBenchmark`; skipped run не
   считается PASS.

**Exit:** baseline green; все изменяемые контракты имеют наблюдаемый тест; нет production
изменений.

### Slice 1. Удаление фиктивного owner и ложной phase role

**Production files:**

- `factory/PlatformSpanProcessorFactory.java`;
- `processor/SpanWatchdogProcessor.java`;
- при необходимости JMX tests, но не wire shape.

**Действия:**

1. Удалить `else` с созданием `new ValidatingSpanProcessor(acceptedPolicy)`.
2. Доказать для validation disabled:
   `ownerPresent=false`, generation остается исходной, readiness/read model fail-closed.
3. Перевести watchdog с `ExtendedSpanProcessor` на `SpanProcessor`.
4. Удалить пустые `onEnding` и `isOnEndingRequired`.
5. Не менять runtime policy mutation, accepted snapshot и validation semantics при
   включенном processor.

**Exit:** в runtime нет processor instance, не зарегистрированного в SDK; watchdog не
объявляет неиспользуемую фазу; packaged Agent startup green для enabled/disabled matrix.

### Slice 2. Удаление неподтвержденного поведения enricher

**BLOCKED BY:** успешный zero-consumer gate из Slice 0.

**Production file:** `processor/EnrichingSpanProcessor.java`.

**Действия:**

1. Удалить `PLATFORM_POLICY_VERSION_KEY`.
2. Удалить чтение `platform.policy.version` из baggage и запись span attribute.
3. Удалить тест, который утверждает это скрытое поведение.
4. Обновить только нормативные документы, которые ошибочно называют проекцию действующим
   runtime-контрактом. Исторические review logs не переписывать.
5. Не переносить special case в `BaggageSpanProcessor` без отдельного утверждения
   allowlist/security policy.

**Stop condition:** найден production producer/consumer или обязательный collector query.
В этом случае Slice 2 получает `BLOCKED`, а поведение сохраняется до решения ownership.

### Slice 3. Сужение span-limit metrics

**Production files:**

- `processor/MetricsSpanProcessor.java` -> `processor/SpanLimitsMetricsProcessor.java`;
- `factory/PlatformSpanProcessorFactory.java`;
- `jmx/PlatformTracingJmxRegistrar.java`;
- `jmx/metrics/PlatformProcessorMetricsControl.java`;
- обе копии `JmxObservationWireContract.java`;
- `ProcessorCompositeErrorKey.java` в Spring autoconfigure;
- configuration Javadoc и соответствующие tests/golden fixtures.

**Действия:**

1. Удалить `endedSpans`, `errorSpans`, `timeoutSpans` и их getters.
2. Удалить status/timeout inspection, оставив один `toSpanData()` для трех drop deltas.
3. Оставить только dropped attributes/events/links counters.
4. Переименовать класс в `SpanLimitsMetricsProcessor`.
5. Синхронно заменить diagnostic owner key `MetricsSpanProcessor` на
   `SpanLimitsMetricsProcessor` в Agent, Spring, golden и composite error enum.
6. Переименовать тесты и убрать assertions мертвых counters; сохранить реальные
   `SpanLimitsVerificationTest` cases.
7. Не добавлять compatibility class, alias key или dual-write.

**Exit:** repository search не находит старый class/wire name; JMX golden parity green;
ровно три metrics series имеют end-to-end producer -> JMX -> Spring binder proof.

### Slice 4. Удаление псевдослоя и lifecycle boilerplate

**Production files:** processor classes и scrubbing engine.

1. Перенести `IpPrefixTruncator` в `scrubbing.engine`, обновить единственного consumer и
   тесты; удалить `processor.utils`.
2. Сделать `ClassificationSpanProcessor` final.
3. Удалить overrides `shutdown()`/`forceFlush()`, которые побайтно эквивалентны default
   success implementation OTel API, только после теста pinned OTel version.
4. Не удалять lifecycle overrides watchdog, composite и DROP_OLDEST processor.
5. Для composite отдельно проверить и, если characterization подтверждает ожидаемый OTel
   contract, сделать shutdown one-shot и направить `close()` через него. Это изменение не
   объединять с package move.

**Exit:** нет `processor.utils`; нет пустых lifecycle methods ради симметрии; повторный
shutdown не вызывает delegate shutdown/close повторно; fault counters и error propagation
не изменились.

## 7. Architecture gates

Добавить или усилить ArchUnit/repository gates:

1. В `processor` запрещены классы с именами `Abstract*Processor`, `*ProcessorRegistry`,
   `*ProcessorProvider` без отдельного ADR.
2. `SpanWatchdogProcessor` не зависит от `ExtendedSpanProcessor`.
3. `processor.utils` отсутствует.
4. `ValidatingSpanProcessor` создается только в enabled-ветке factory и test fixtures.
5. Каждый processor, переданный `jmxRegistrar.set*`, должен быть тем же object identity,
   который включен в composite/export pipeline.
6. Agent и Spring wire-contract golden files byte-identical.
7. Old names `MetricsSpanProcessor` и `platform.policy.version` отсутствуют после
   соответствующих успешно закрытых slices.
8. Factory сохраняет единственный ordered composition root; processor'ы не создают другие
   processor'ы.

## 8. Verification

### Focused compile/tests

```powershell
.\gradlew.bat :platform-tracing-otel-javaagent-extension:compileJava `
  :platform-tracing-otel-javaagent-extension:compileTestJava --no-daemon

.\gradlew.bat :platform-tracing-otel-javaagent-extension:test `
  :platform-tracing-spring-boot-autoconfigure:test --no-daemon
```

### Contract and architecture gates

```powershell
.\gradlew.bat jmxManagementContractVerify jmxWireGoldenParityVerify `
  pr1ModuleTaxonomyVerify pr4ArchitectureFitnessVerify `
  tracingConfigurationInventoryVerify --no-daemon
```

### JMH

```powershell
.\gradlew.bat :platform-tracing-bench:jmh --no-daemon
```

Минимум сравниваются `CompositePipelineBenchmark` rows. При изменении span-limit processor
добавляется или используется узкий benchmark, который реально вызывает `onEnd` над
`ReadableSpan` с dropped counts. Требования:

- одинаковые JVM/forks/warmups/iterations;
- raw result committed как evidence;
- отсутствие статистически значимой регрессии p50/p95;
- allocation regression требует объяснения и отдельного approval.

### Packaged Agent E2E

Запустить opt-in Agent E2E с реальным extension jar и Collector:

```powershell
$env:DOCKER_HOST='tcp://192.168.100.70:2375'
.\gradlew.bat :platform-tracing-e2e-tests:test -PrunE2e --no-daemon
```

Обязательные наблюдения:

- server/manual span проходит полный pipeline;
- scrubbing и validation сохраняют порядок;
- watchdog enabled/disabled не меняет Agent startup;
- JMX owner presence соответствует реально установленным processor'ам;
- dropped attributes/events/links доходят до Spring Micrometer bridge;
- forceFlush/shutdown завершаются без двойного закрытия delegate.

Skipped E2E/JMH записывается как `NOT_RUN`, а не `PASS`.

### Repository hygiene

```powershell
git diff --stat
git diff --check
rg -n --glob '*.java' '^\s*import\s+(static\s+)?[a-zA-Z0-9_.]+\.\*;' .
```

Wildcard scan должен вернуть 0 новых совпадений в измененных файлах. Кодировка и окончания
строк существующих файлов не меняются механически.

## 9. Acceptance criteria

План считается выполненным, когда одновременно выполнено следующее:

- нет fake `ValidatingSpanProcessor` для JMX;
- JMX `ownerPresent` означает фактическое присутствие runtime owner;
- watchdog использует только требуемые OTel phases;
- span-limit processor не считает неэкспортируемые показатели;
- старое имя `MetricsSpanProcessor` отсутствует;
- Agent/Spring JMX wire names и golden fixtures синхронны;
- `processor.utils` удален, `IpPrefixTruncator` принадлежит scrubbing;
- `platform.policy.version` либо удален после полного zero-consumer proof, либо Slice 2
  явно заблокирован найденным контрактом;
- custom composite сохраняет fault isolation и validation pass-through;
- DROP_OLDEST behavior не изменен;
- focused tests, architecture/JMX gates, JMH и packaged E2E имеют воспроизводимое evidence;
- текущие propagation-изменения не вошли в processor commits.

## 10. No-Go условия

Реализация останавливается и возвращается на review, если требуется хотя бы одно из
следующего:

- новый public SPI или новый module;
- второй mutable processor registry;
- ServiceLoader для application-defined processors;
- generic abstract base только ради удаления нескольких пустых методов;
- замена custom composite без доказательства fault-isolation parity;
- замена DROP_OLDEST processor на stock BSP;
- сохранение fake owner ради удобства JMX;
- dual wire names или deprecated aliases для непубликовавшегося решения;
- изменение processor order без failing characterization test;
- смешивание cleanup с текущей незавершенной propagation-веткой.

## 11. Рекомендуемая commit topology

```text
processor-cleanup/0-characterization
processor-cleanup/1-owner-and-phase
processor-cleanup/2-policy-version-projection   # только после zero-consumer PASS
processor-cleanup/3-span-limits-metrics
processor-cleanup/4-package-and-lifecycle
processor-cleanup/5-evidence
```

Каждый commit должен компилироваться и проходить focused tests. Evidence commit не должен
содержать production fixes. Rollback выполняется по slice, без отката остальных processor
contracts.
