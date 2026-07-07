# ClassLoader Visibility Test-Only Probe Implementation Plan

> **Статус реализации:** `COMPLETED` (2026-06-17)  
> **Вариант:** `TEST_ONLY_PROBE` + `DELETE_OLD_PROPERTY_AND_MARKERS`  
> **Аудит:** `fixed-and-passed` (2026-06-17)

---

## 1. Executive Summary

- Финальный целевой статус: **TEST_ONLY_PROBE** — probe живёт исключительно в `platform-tracing-e2e-tests`, не попадает в production artifact.
- Старая совместимость **намеренно удаляется**: property `platform.tracing.spike.classloader.visibility`, маркер-prefix `SPIKE_CLASSLOADER:`, old E2E-тест, launcher, Gentoo-скрипт.
- Production spike `ClassLoaderVisibilitySpikeProbe` и пакет `factory.spike` удаляются из `platform-tracing-otel-extension/src/main`.
- **F1** переносится в отдельный test-only extension JAR, загружаемый через `otel.javaagent.extensions` внутри дочерней JVM E2E-теста; probe реализует `AutoConfigurationCustomizerProvider` → выполняется в `ExtensionClassLoader` агента.
- **F3** — см. §17 (отклонение от первоначального плана).

---

## 2. Current Evidence

*(архивный контекст на момент планирования — см. `classloader-visibility-spike-usage-inventory.md`)*

---

## 3. Target Architecture

```
platform-tracing-otel-extension/src/main  → НЕТ spike-классов; нет System.err side-channel
platform-tracing-e2e-tests/src/testExtension/  → ClassLoaderVisibilityTestProbe.java
                                                 → META-INF/services/AutoConfigurationCustomizerProvider
platform-tracing-e2e-tests/src/test/           → ClassLoaderVisibilityE2ETest
                                                 → ClassLoaderVisibilityE2ELauncher
```

**Дочерняя JVM (реализовано):**

```
-javaagent:<otel-agent.jar>
-Dotel.javaagent.extensions=<extDir>   ← каталог с extension.jar + cl-probe.jar
-Dplatform.tracing.scrubbing.rules.extensions=<custom-rule.jar>
-Dotel.javaagent.logging=application
-cp <smoke.test.runtime.classpath>
ClassLoaderVisibilityE2ELauncher
```

---

## 4. Gradle / Packaging Plan

| План | Реализовано | Статус |
|------|-------------|--------|
| sourceSet `clProbe` | sourceSet `testExtension` (`src/testExtension/`) | ✅ done (переименовано) |
| task `clProbeJar` | task `testClassLoaderProbeJar` (classifier `cl-probe`) | ✅ done |
| sysprop `smoke.cl.probe.jar` | sysprop `smoke.test.classloader.probe.extension.jar` | ✅ done |
| `dependsOn` в `test` task | `dependsOn testClassLoaderProbeJar` | ✅ done |
| child JVM: temp dir с обоими JAR | `Files.copy` → `extDir/extension.jar` + `cl-probe.jar` | ✅ done |
| custom rule через `platform.tracing.scrubbing.rules.extensions` | без изменений | ✅ done |
| embedding `platform-tracing-api` в probe JAR | добавлено (`zipTree(platform-tracing-api.jar)`) | ✅ done (отклонение: OTel 2.28.x создаёт отдельный ExtensionClassLoader на каждый JAR) |

---

## 5. Test-Only Probe Design

| План | Реализовано | Статус |
|------|-------------|--------|
| `ClVisibilityProbeCustomizer` | `ClassLoaderVisibilityTestProbe` | ✅ done |
| package `space.br1440.e2e.clvisibility` | package `space.br1440.platform.tracing.e2e.probe` | ✅ done |
| `AutoConfigurationCustomizerProvider` SPI | реализован + `addTracerProviderCustomizer` | ✅ done |
| `META-INF/services/...AutoConfigurationCustomizerProvider` | зарегистрирован | ✅ done |
| маркер-prefix `CL_VISIBILITY:` | реализован | ✅ done |
| 4 варианта ServiceLoader (default/tccl/factory/api) | реализованы | ✅ done |
| property gate | нет (probe всегда активен при загрузке JAR) | ✅ done |

---

## 6. F1 Assertion Plan

| Проверка | Статус |
|----------|--------|
| `CL_VISIBILITY:BEGIN` / `END` | ✅ asserted |
| `extensionProbeLoaded=true` | ✅ asserted |
| `probeClassLoader=...ExtensionClassLoader` | ✅ asserted |
| варианты default/tccl/factory/api присутствуют | ✅ asserted |
| `targetFound=false` для всех native variants | ✅ asserted |
| F1 НЕ в `main()` launcher | ✅ подтверждено (launcher только `READY`) |
| E2E падает при отсутствии `CL_VISIBILITY` output | ✅ подтверждено |

---

## 7. F3 / Production Custom Rule Proof

| Responsibility | Test | Статус |
|----------------|------|--------|
| F1 classloader visibility | `ClassLoaderVisibilityE2ETest` + test-only probe JAR | ✅ |
| URLClassLoader mechanism smoke (optional) | `ClassLoaderVisibilityE2ETest` — `mechanismCustomRules`, `mechanismLoadingMode` | ✅ (не production F3) |
| **Production F3** (ExtensionRuleLoader + apply) | `CustomRuleSmokeE2ETest` — span masking in Jaeger | ✅ |

**Не используется:** парсинг `StartupDiagnostics` как E2E assertion (SLF4J binding недоступен в ExtensionClassLoader OTel 2.28.x).

**F3 overclaim fix (2026-06-17):** маркеры `f3CustomRules` / `f3LoadingMode` переименованы в `mechanismCustomRules` / `mechanismLoadingMode`; ClassLoaderVisibilityE2ETest не претендует на production `ExtensionRuleLoader` proof.

---

## 8. Production Cleanup Plan

| Файл / элемент | Статус |
|----------------|--------|
| `ClassLoaderVisibilitySpikeProbe.java` | ✅ DELETED |
| `factory/spike/` package | ✅ DELETED |
| `PlatformSpanProcessorFactory` import spike | ✅ REMOVED |
| `PlatformSpanProcessorFactory` call `emitExtensionLoadResultIfEnabled` | ✅ REMOVED |
| `StartupDiagnostics.emit(...)` | ✅ UNCHANGED |
| `ExtensionRuleLoader` semantics | ✅ UNCHANGED |

---

## 9. E2E Rename / Rewrite Plan

| Элемент | Старый | Новый | Статус |
|---------|--------|-------|--------|
| Test class | `ClassLoaderVisibilitySpikeE2ETest` | `ClassLoaderVisibilityE2ETest` | ✅ done |
| Launcher main | `ClassLoaderVisibilitySpikeMain` | `ClassLoaderVisibilityE2ELauncher` | ✅ done |
| Parser | `parseSpikeVariants()` | `parseCLVariants()` | ✅ done |
| Property gate | `platform.tracing.spike.classloader.visibility` | удалено | ✅ done |
| Marker prefix | `SPIKE_CLASSLOADER:` | `CL_VISIBILITY:` | ✅ done |
| Spike import в E2E | `ClassLoaderVisibilitySpikeProbe.*` | нет | ✅ done |

---

## 10. ArchUnit / Fitness Plan

| Проверка | Статус |
|----------|--------|
| factory.spike carve-out удалён из javadoc | ✅ done |
| правило запрета `..factory.spike..` в src/main | ✅ done (`no_factory_spike_package_in_production`) |
| broad System.err carve-out не добавлен | ✅ done |
| `pr4ArchitectureFitnessVerify` | ✅ BUILD SUCCESSFUL |

> Правило `..spike..` (все spike-пакеты) на момент этой задачи **не** добавлялось — `jmx.spike` (PR-3 JMX wire) тогда оставался в src/main по отдельному контракту.
>
> **Обновление (NEW_HYBRID, JMX spike production purge):** пакет `jmx.spike` впоследствии полностью удалён из production `src/main`; добавлены production-баны `SafeBoundaryArchTest.no_jmx_spike_package_in_production` и `no_spike_named_classes_in_production`. JMX wire round-trip харнесс перенесён в test-only `platform-tracing-e2e-tests/src/jmxWireExtension`.

---

## 11. Docs / Scripts Plan

| Артефакт | Статус |
|----------|--------|
| `docs/decisions/ADR-classloader-visibility-spike-finding.md` — Migration section | ✅ done |
| `docs/architecture/classloader-visibility-spike-usage-inventory.md` — §20 Post-Migration | ✅ done (F3 строка требует уточнения — см. §17) |
| `docs/architecture/platform-tracing-preservation-first-migration-plan.md` — DEFER → DONE | ❌ не обновлено |
| `docs/architecture/platform-tracing-current-codebase-inventory.md` — удалить spike row | ❌ не обновлено |
| `scripts/run-classloader-spike-gentoo.ps1` | ✅ DELETED |

---

## 12. File-by-File Change Plan

| Файл | Действие | Статус |
|------|----------|--------|
| `.../factory/spike/ClassLoaderVisibilitySpikeProbe.java` | DELETE | ✅ |
| `.../factory/PlatformSpanProcessorFactory.java` | EDIT | ✅ |
| `.../arch/SafeBoundaryArchTest.java` | EDIT | ✅ |
| `platform-tracing-e2e-tests/build.gradle` | EDIT | ✅ |
| `src/testExtension/.../ClassLoaderVisibilityTestProbe.java` | CREATE | ✅ |
| `src/testExtension/resources/META-INF/services/...` | CREATE | ✅ |
| `src/test/.../ClassLoaderVisibilityE2ETest.java` | CREATE | ✅ |
| `src/test/.../ClassLoaderVisibilityE2ELauncher.java` | CREATE | ✅ |
| `src/test/.../ClassLoaderVisibilitySpikeE2ETest.java` | DELETE | ✅ |
| `src/test/.../ClassLoaderVisibilitySpikeMain.java` | DELETE | ✅ |
| `scripts/run-classloader-spike-gentoo.ps1` | DELETE | ✅ |
| ADR migration section | EDIT | ✅ |
| preservation-first-migration-plan | EDIT | ❌ deferred |
| current-codebase-inventory | EDIT | ❌ deferred |

---

## 13. Implementation Order

```
Шаг 1. Gradle + probe JAR scaffold                                    [✅ DONE]
    — sourceSet testExtension (план: clProbe)
    — ClassLoaderVisibilityTestProbe.java + META-INF/services
    — testClassLoaderProbeJar собирается

Шаг 2. Новый E2E test + launcher                                      [✅ DONE]
    — ClassLoaderVisibilityE2ETest.java
    — ClassLoaderVisibilityE2ELauncher.java

Шаг 3. Прогнать новый E2E (GREEN gate)                                [✅ DONE]
    — ClassLoaderVisibilityE2ETest PASSED
    — F1: targetFound=false, probeClassLoader=ExtensionClassLoader
    — mechanism smoke: mechanismCustomRules=1, mechanismLoadingMode=PLATFORM_RULES_EXTENSIONS
    — production F3: CustomRuleSmokeE2ETest (отдельный тест)

Шаг 4. Удалить production spike                                       [✅ DONE]
    — ClassLoaderVisibilitySpikeProbe.java удалён
    — factory/spike/ удалён
    — import + call в PlatformSpanProcessorFactory удалены

Шаг 5. Удалить старые E2E файлы                                       [✅ DONE]
    — ClassLoaderVisibilitySpikeE2ETest.java удалён
    — ClassLoaderVisibilitySpikeMain.java удалён

Шаг 6. Обновить ArchUnit                                                [✅ DONE]
    — carve-out javadoc убран
    — no_factory_spike_package_in_production добавлен

Шаг 7. Обновить docs и удалить скрипт                                   [⚠️ PARTIAL]
    — ADR ✅, inventory §20 ✅
    — preservation-first-migration-plan ❌
    — current-codebase-inventory ❌
    — run-classloader-spike-gentoo.ps1 ✅ удалён

Шаг 8. Финальная валидация                                              [✅ DONE]
    — otel-extension:test BUILD SUCCESSFUL
    — ClassLoaderVisibilityE2ETest PASSED
    — pr4ArchitectureFitnessVerify BUILD SUCCESSFUL
    — rg-проверки: 0 matches spike/property/marker в active code
```

---

## 14. Stop Conditions

| Условие | Результат |
|---------|-----------|
| Probe не вызывается агентом | не сработало — `CL_VISIBILITY:BEGIN` присутствует |
| F3: StartupDiagnostics не захватывается | **сработало** — production F3 в `CustomRuleSmokeE2ETest`; classloader E2E — mechanism smoke only |
| clProbe sourceSet не компилируется | не сработало — `testExtension` компилируется |
| Gentoo cross-platform validation | отложено — скрипт удалён |

---

## 15. Validation Commands

| Команда | Результат |
|---------|-----------|
| `:platform-tracing-e2e-tests:testClassLoaderProbeJar` | ✅ BUILD SUCCESSFUL |
| `:platform-tracing-otel-extension:test` | ✅ BUILD SUCCESSFUL |
| `pr4ArchitectureFitnessVerify` | ✅ BUILD SUCCESSFUL |
| `:platform-tracing-e2e-tests:test -PrunE2e --tests "*ClassLoaderVisibilityE2ETest*"` | ✅ PASSED |
| `:platform-tracing-e2e-tests:test -PrunE2e` (full suite) | ⚠️ 17 failures — Docker/Testcontainers недоступен (pre-existing) |
| rg spike/property/marker в src/main | ✅ 0 matches |
| rg `custom-e2e-rule` в src/main | ✅ 0 matches |

---

## 16. Final Status

```text
Planning status:   COMPLETED
Implementation:    COMPLETED (2026-06-17)
F3 overclaim fix:  COMPLETED (2026-06-17)
Audit:             F3 scope separation accepted
```

---

## 17. Implementation Notes (отклонения от плана)

1. **Именование:** `testExtension` / `testClassLoaderProbeJar` / `ClassLoaderVisibilityTestProbe` вместо `clProbe` / `clProbeJar` / `ClVisibilityProbeCustomizer` — функционально эквивалентно.
2. **Embedding API:** `platform-tracing-api` встроен в probe JAR — необходимо для OTel 2.28.x (отдельный ExtensionClassLoader на JAR).
3. **F3 scope:** production proof — `CustomRuleSmokeE2ETest`; classloader E2E — optional `mechanismCustomRules` smoke only (не `ExtensionRuleLoader.load`).
4. **ArchUnit:** правило точечное `..factory.spike..`; на тот момент `jmx.spike` был вне scope. После NEW_HYBRID purge добавлены production-баны `..jmx.spike..` и `*Spike*` в `SafeBoundaryArchTest`.
5. **Docs:** `preservation-first-migration-plan`, `current-codebase-inventory` — обновлены (2026-06-17 F3 fix).
6. **Аудит-фиксы (2026-06-17):** удалён unused import `Arrays`; исправлены stale javadoc/комментарии.
7. **F3 overclaim fix (2026-06-17):** `f3*` markers → `mechanism*`; ADR/inventory/plan синхронизированы.

---

## 18. Todo Checklist

- [x] Добавить sourceSet + probe JAR task + sysprop + dependsOn в `build.gradle`
- [x] Создать `ClassLoaderVisibilityTestProbe.java` + `META-INF/services`
- [x] Создать `ClassLoaderVisibilityE2ETest.java` + `ClassLoaderVisibilityE2ELauncher.java`
- [x] Прогнать новый E2E: F1 `targetFound=false`; mechanism smoke `mechanismCustomRules=1`
- [x] F3 overclaim fix: rename markers, rescope tests/docs
- [x] Обновить `platform-tracing-preservation-first-migration-plan.md`
- [x] Обновить `platform-tracing-current-codebase-inventory.md`
- [x] Финальная валидация: otel-extension:test, pr4ArchitectureFitnessVerify, rg-проверки
