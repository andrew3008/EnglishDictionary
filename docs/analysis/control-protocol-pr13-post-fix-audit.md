# Control Protocol PR #13 Post-Fix Audit

> Исполнитель: Codex (senior Java platform engineering pass)
> Дата: 2026-07-17
> Режим: локальные правки без commit/push.

## Verdict

**PASS.** Блокеры из `control-protocol-pr13-opus-post-audit.md` устранены: test/consumer source-set компилируются, BOM-блокер снят, empty APPLY отклоняется domain-валидацией, wire-spec синхронизирован с фактическим READ/contractVersion поведением.

## Что Исправлено

- P0-1: мигрированы test/consumer ссылки с удаленного `schema()/validator()` и legacy-подпакетов на flat `TracingControlProtocol.current().decode(...)`.
- P0-2: удален UTF-8 BOM из `KafkaTracing.java` и `OperationSpanBuilder.java`; повторный scan BOM ничего не нашел.
- P1-1: `RuntimePolicyControlDomainValidator` отклоняет пустой `APPLY_RUNTIME_POLICY` с domain violation `empty mutation rejected`; добавлен unit test.
- P2: обновлены wire-spec docs, добавлен `GoldenWireContractTest`.
- Дополнительно: исправлен late-wiring `PlatformControlProtocolMBean` в `PlatformTracingJmxRegistrar`, чтобы control MBean, зарегистрированный до handler'а, заменялся live handler'ом после `setControlHandler(...)`.

## Verification

| Команда | Результат |
|---|---|
| `:platform-tracing-api:compileTestJava` | PASS |
| `:platform-tracing-spring-boot-autoconfigure:compileTestJava` | PASS |
| `:platform-tracing-e2e-tests:compileJmxWireExtensionJava` | PASS |
| `:platform-tracing-api:test` | PASS |
| `:platform-tracing-core:test` | PASS |
| `:platform-tracing-e2e-tests:test --tests "*WireRoundTrip*" -PrunE2e` | PASS |
| `:platform-tracing-api:javadoc pr4ArchitectureFitnessVerify pr1ModuleTaxonomyVerify` | PASS |
| `build` | PASS |

## Static Scans

- Removed Java API scan: no live `schema()/validator()/validateRuntimePolicy`, legacy protocol subpackage imports, `READ_SCHEMA`, removed operation-key constants, or `ratioBounded()` usages found in Java/Gradle sources.
- BOM scan over touched Java/Gradle source roots: no UTF-8 BOM files found.

## Notes

`build` completed successfully. Non-blocking output remains from existing javadoc warnings and one external Docker connection log emitted during the build, but Gradle returned `BUILD SUCCESSFUL`.
