# Platform Tracing Slice D Evidence

Дата: 2026-07-19
Ветка: `feature/runtime-control-hardening`
Исходный HEAD: `f07ef5b`

## Результат

Slice D завершён в пределах подтверждённого дефекта ALIGN-04:

- `SpanFactory.fromSpec(SpanSpec)` теперь применяет тот же `AttributePolicy.validateAndNormalize`, который используется semantic builders;
- policy выполняется на terminal operation (`start`, `run`, `call`, `callChecked`) до вызова `TracingRuntime.startSpan`;
- STRICT отклоняет невалидную спецификацию и не передаёт её в runtime;
- WARN передаёт в runtime нормализованную копию, не изменяя исходный `SpanSpec`;
- при копировании сохраняются name, category, relationship, links, reason и reference;
- если нормализация не меняет attributes, исходный immutable `SpanSpec` передаётся без лишней копии;
- known-defect marker `FROM_SPEC_POLICY_BYPASS` удалён из активного реестра.

Новая governance seam реализована package-private классом `SpanSpecGovernance`; публичный API и ABI не изменены.

## ALIGN-09

Декомпозиция `SemanticSpanSpecs` не выполнялась. Аудит HEAD подтвердил уже существующее разделение `SemanticSpanSpecs` и `OperationSpanSpecs`; доказанного god-class defect нет. ALIGN-09 закрыт как verification-only, без косметического перемещения кода.

## Тесты

`FromSpecPolicyGovernanceTest` проверяет:

- parity прямого STRICT policy и пути `fromSpec`;
- fail-before-runtime для невалидной спецификации;
- WARN-нормализацию для каждой `SpanCategory`;
- сохранение root relationship, remote links, reason и reference.

Routing/lifecycle тесты обновлены с проверки object identity на проверку нормализованного контракта. Исходный spec остаётся неизменным.

## Верификация

- `gradlew.bat :platform-tracing-core:compileJava :platform-tracing-core:compileTestJava --no-daemon` — PASS.
- `gradlew.bat :platform-tracing-core:test --tests "*FromSpecPolicyGovernanceTest" --tests "*SpanExecutionTest" --no-daemon` — PASS.
- `gradlew.bat :platform-tracing-core:test --no-daemon` — PASS, 454 tests.
- `gradlew.bat pr4ArchitectureFitnessVerify pr1ModuleTaxonomyVerify --no-daemon` — PASS.
- `gradlew.bat build --no-daemon` — PASS, 116 actionable tasks.
- wildcard-import scan по изменённым Java-файлам — 0 совпадений.
- `git diff --check` — PASS.

Общий e2e test task при полном build остался `SKIPPED` из-за существующего opt-in gate. Внешний Docker daemon не требовался для Slice D; диагностическое сообщение Windows Docker CLI об абсолютном volume path не изменило успешный результат Gradle build.

## Открытый checkpoint

CP-C2 остаётся `PROPOSED / AWAITING APPROVAL`. Slice C2 и зависящая от него blocking chain не стартуют до явного решения architecture committee.
