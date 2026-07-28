# P3: Agent Reader Migration Evidence

> Дата: 2026-07-28  
> Ветка: `refactor/config-accepted-state-p1`  
> Проверенный HEAD: `8f690a70b892c01394170132898a0cbfa04134e9`  
> Статус: `IMPLEMENTATION COMPLETE / VERIFICATION GREEN`

## 1. Результат

Все восемь Category A листьев читаются из одного immutable
`AcceptedPolicyEnvelope`, опубликованного единственным Agent-side
`AcceptedPolicyStateStore`:

1. `samplingEnabled`;
2. `samplingRatio`;
3. `samplingRouteRatios`;
4. `validationEnabled`;
5. `validationStrict`;
6. `exporterEnabled`;
7. `traceControlHeadersEnabled`;
8. `diagnosticsLogLevel`.

На единый snapshot переведены `CompositeSampler`, `ValidatingSpanProcessor`,
`SafeSpanExporter`, `PlatformPropagationGate`, `PlatformLogControl`,
`ReadAppliedStateHandler` и соответствующая JMX/composition wiring.

Для named sampler SPI отдельно доказано, что inline customizer перепривязывает
созданный sampler к тому же `AcceptedPolicyAccess`, которым владеет
composition root. Неиспользуемое fallback-создание `ValidatingSpanProcessor`,
порождавшее лишний изолированный owner, удалено.

`SamplerStateHolder`, `SamplerState`, `SamplerPolicyUpdate` и
`ValidationPolicyHolder` удалены вместе с тестами, проверявшими удалённые
owners. Zero-reference scan по Java-исходникам не нашёл оставшихся ссылок.

## 2. Concurrency и read path

`AcceptedPolicyAccess` выполняет одну expected-base CAS-попытку без retry и
rebase. `UNCHANGED` не вызывает CAS. `acceptedSequence` изменяется только
внутри принятого envelope при результате `APPLIED`.

`AcceptedPolicyAccessTest` подтверждает:

- публикацию всех восьми листьев через один snapshot;
- сохранение `runtimeInstanceId`;
- изменение sequence только при `APPLIED`;
- отсутствие новой публикации для `UNCHANGED` и `REJECTED`;
- неизменяемость опубликованной route map;
- отсутствие allocation на каждый read после прогрева HotSpot.

## 3. Временная Category B граница

`dropPaths` и force-record values не входят в Category A. До coherent P6
cutover они хранятся в отдельном временном carrier. `ScrubbingPolicyHolder`
также остаётся Category B до P6.

Это допустимое C-05 промежуточное состояние только для невыпущенного train:

- supported production writer не переключён;
- два supported live writer не активируются одновременно;
- промежуточный artifact не подлежит публикации;
- атомарный namespace/writer cutover и удаление Category B owners остаются
  обязанностью P6.

Единственные оставшиеся ручные `previous.version() + 1` находятся в
`ScrubbingPolicyUpdate` и относятся к этой отложенной Category B области.

## 4. Выполненная верификация

Следующие проверки завершились успешно:

```text
:platform-tracing-otel:test
:platform-tracing-otel-javaagent-extension:test
:platform-tracing-spring-boot-autoconfigure:compileTestJava
:platform-tracing-e2e-tests:compileTestJava
:platform-tracing-bench:compileJmhJava
acceptedStatePrimitiveVerify
privateMBeanServerVerify
tracingConfigurationInventoryVerify
pr4ArchitectureFitnessVerify
pr1ModuleTaxonomyVerify
build
```

Полный `build`: `BUILD SUCCESSFUL`, 128 tasks. Обычная build-конфигурация
пометила opt-in E2E как `SKIPPED`; это не считается успешным E2E-прогоном.

Локальные JMH smoke-задачи завершились успешно:

```text
:platform-tracing-bench:jmh -PjmhQuickSmoke -PjmhInclude=CompositeSampler
:platform-tracing-bench:jmh -PjmhQuickSmoke -PjmhInclude=ValidatingSpanProcessorBenchmark
```

Они проверяют исполнение migrated hot paths и concurrent update fixture, но
не заменяют формальное P5 performance acceptance.

Дополнительные статические проверки:

- `git diff --check` — без ошибок;
- references на удалённые holders — 0;
- wildcard imports в изменённых Java-файлах — 0;
- UTF-8 BOM в изменённых Java-файлах — 0;
- generated starter/private-MBeanServer baselines не изменены.

## 5. E2E статус

Docker endpoint `tcp://192.168.100.70:2375` во время финальной проверки
недоступен (`TcpTestSucceeded=False`). Поэтому полный opt-in E2E для текущего
P3 diff имеет статус `NOT_RUN`, а не `PASS`.

Ранее предоставленный результат `65 tests, 0 failures/errors/skipped`
относится к состоянию до текущего P3 diff и не используется как доказательство
P3.

## 6. Gate

```text
P3 IMPLEMENTATION COMPLETE
P3 VERIFICATION GREEN
P4 UNBLOCKED
P6 CUTOVER NOT PERFORMED
PRODUCTION ROLLOUT FORBIDDEN
```
