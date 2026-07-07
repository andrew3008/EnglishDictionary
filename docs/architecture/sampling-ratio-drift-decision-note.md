# Decision Note: sampling.ratio Default Drift

**Дата:** 2026-06-16
**Решение:** `ALIGN_TO_EXTENSION_DEFAULTS`
**Статус:** ПРИНЯТО (PR-1). Реализуется в PR-5 (миграция Sampling).
**Ревьюер:** архитектор (план ExtensionConfig Refactoring)

---

## Суть дрейфа

В репозитории существует расхождение в значении `sampling.ratio` по умолчанию (когда свойство
`platform.tracing.sampling.ratio` не задано):

| Компонент | Поведение при отсутствии свойства | Значение |
|---|---|---|
| `ExtensionConfig.Sampling.ratio()` | `getDouble(SAMPLING_RATIO, DEFAULT_SAMPLING_RATIO)` | **0.1** |
| `PlatformSamplerBuilder.build()` | `getString == null` → `defaultRatio = 1.0` (hardcoded) | **1.0** |

Конкретный фрагмент в `PlatformSamplerBuilder`:

```java
double defaultRatio = 1.0;                                          // ← hardcoded implicit fallback
String ratioStr = config.getString(ExtensionPropertyNames.SAMPLING_RATIO);
if (ratioStr != null && !ratioStr.isBlank()) {
    defaultRatio = config.getDouble(ExtensionPropertyNames.SAMPLING_RATIO,
            ExtensionDefaults.DEFAULT_SAMPLING_RATIO);              // 0.1 — но только если задано
}
```

`ExtensionConfig.Sampling.ratio()`:

```java
return config.getDouble(ExtensionPropertyNames.SAMPLING_RATIO,
        ExtensionDefaults.DEFAULT_SAMPLING_RATIO);                  // 0.1 всегда при отсутствии
```

Дрейф зафиксирован тестом `SamplingParity#sampling_ratio_drift_lock_facade_01_builder_10` в
`ExtensionConfigFacadeVsFactoryParityCharacterizationTest`.

---

## Анализ происхождения

- **Facade `0.1`** — явный `ExtensionDefaults.DEFAULT_SAMPLING_RATIO`. Это задокументированный
  платформенный default — нет признаков случайности.
- **Builder `1.0`** — implicit fallback через `getString != null` guard. Логика возникла как защита
  от `getDouble` на пустой строке, но побочным эффектом стало отличие от facade: при отсутствии
  свойства builder ведёт себя как `ratio=1.0` (семплировать всё).

Это не осознанное архитектурное решение — источник истины один (`ExtensionDefaults.DEFAULT_SAMPLING_RATIO = 0.1`),
а builder его не использует.

---

## Принятое решение: `ALIGN_TO_EXTENSION_DEFAULTS`

В pre-prod контексте с нулевым трафиком выравниваем production-поведение builder'а на единственный
авторитетный источник: `ExtensionDefaults.DEFAULT_SAMPLING_RATIO = 0.1`.

**После PR-5:**
- Отсутствие `platform.tracing.sampling.ratio` → `0.1` (и в facade, и в builder)
- Пустая строка → `0.1` (fail-fast не применяется к ratio, т.к. пустота = не задано)
- Явное значение `"0.5"` → `0.5` (оба канала)

**Отброшенные альтернативы:**

| Вариант | Причина отклонения |
|---|---|
| `PRESERVE_PROD_BEHAVIOR` (оставить 1.0 в builder) | Закрепляет implicit fallback как контракт; противоречит ExtensionDefaults; при отсутствии property семплируется 100% — surprise behavior |
| `TRANSITIONAL_COMPAT` (compat-период) | Нет production-трафика для compat-защиты; только усложняет код |

---

## Последствия изменения

При выравнивании **поведение изменится** для deployments, которые:
- запускаются без явного `platform.tracing.sampling.ratio`;
- ожидают `ratio=1.0` (семплировать всё).

**Оценка риска:** низкий. Мы в pre-prod, трафика нет. Любая такая конфигурация — имплицитная
зависимость на недокументированное поведение. После изменения операторы должны явно задавать
`platform.tracing.sampling.ratio=1.0` если хотят sampling 100%.

---

## Граница с runtime-изменяемым состоянием

Это решение касается только **startup seed** в `SamplerStateHolder`. Runtime-мутации
(`setSamplingRatio` через JMX / `RuntimeConfigApplier`) остаются без изменений. После
первого JMX-пуша Spring начальный seed неактуален.

---

## Задачи PR-5

1. В `PlatformSamplerBuilder.build()` заменить `double defaultRatio = 1.0` + getString-guard на
   `double defaultRatio = config.getDouble(SAMPLING_RATIO, DEFAULT_SAMPLING_RATIO)`.
2. Обновить whitelist в `SharedDefaultsAlignmentTest` при необходимости.
3. Тест: `sampling.ratio` absent → builder ratio `0.1`.
4. Тест: `sampling.ratio` blank → builder ratio `0.1` (не `1.0`).
5. Тест: `sampling.ratio` explicit `"0.5"` → builder ratio `0.5`.
6. Тест: facade и builder согласованы для всех трёх случаев.

---

## Связанные артефакты

- Тест: `ExtensionConfigFacadeVsFactoryParityCharacterizationTest#SamplingParity`
- Файлы: `ExtensionDefaults.java`, `PlatformSamplerBuilder.java`, `ExtensionConfig.java`
- Смежный контракт: `SharedDefaultsAlignmentTest` (Spring-side)
- ADR: `docs/decisions/ADR-dual-channel-properties-v0.1.md`
- F3 RateLimit spike: `PlatformSamplerBuilder` — смотреть `rate-limit-f3-technical-spike-plan.md`
  перед merge PR-5, чтобы не конфликтовать с F3 assumptions.
