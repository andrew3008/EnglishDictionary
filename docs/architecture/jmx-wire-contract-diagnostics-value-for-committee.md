# Диагностическая польза JMX wire type-name gate — материал для архитектурного комитета

| Поле | Значение |
|------|----------|
| Версия | 1.0 |
| Дата | 2026-08-04 |
| Статус | **Brief для архитектурного комитета** |
| План | `wire_contract_constants_use` (type-name gate + classification parity + producer reasonCode) |
| ADR | [ADR-jmx-observation-wire-contract](../decisions/ADR-jmx-observation-wire-contract.md) |
| Примеры payload | [jmx-wire-contract-diagnostics-examples](../runbook/jmx-wire-contract-diagnostics-examples.md) |
| Runbook | [tracing-status-diagnostics](../runbook/tracing-status-diagnostics.md) |

---

## 1. Вопрос комитету

Нужно ли считать runtime-диагностику `COMPOSITE_TYPE_NAME_MISMATCH` (и связанный early-reject decode path) **достаточной и оправданной** частью публичного операционного контракта Platform Tracing, или это избыточная поверхность без существенной диагностической отдачи?

Документ отвечает на этот вопрос **только в части диагностической пользы**. Сопутствующие эффекты плана (починка producer sampling-ключей, golden/parity gates) упомянуты для правильной attribution ценности, но не подменяют оценку diagnostics.

---

## 2. Executive verdict

| Утверждение | Оценка |
|-------------|--------|
| Type-name gate как **fail-closed защита wire-контракта** | **Оправдан** |
| Type-name diagnostics как **богатый forensic/runbook инструмент** | **Не оправдан ожидать** |
| Разработка gate «зря» | **Нет** — при корректной рамке ценности |
| Нужен ли сейчас enrichment payload (expected/actual typeName, nested path) | **Нет как must-have** до появления реальной частоты version-skew инцидентов |

**Одной фразой:** функционал даёт *дешёвый, стабильный, алертируемый сигнал несовместимости контракта*; он не даёт *пошаговой диагностики «какой nested item и какое имя пришло»*. Это страховочный слой correctness, а не основной ops playbook.

---

## 3. Контекст: какую проблему закрывает

### 3.1 Архитектурная предпосылка

Agent extension и Spring autoconfigure живут в **разных classloader'ах**. Общий Java-тип wire-контракта невозможен; нормативные имена `CompositeType` и item'ов зеркалируются двумя независимыми копиями `JmxObservationWireContract`.

Следствие: расхождение копий (непарный выкат JAR агента и starter, чужой MBean под platform ObjectName, ошибочная подстановка nested composite) может проявляться как:

- пустые / частично пустые observation;
- `NaN` на point-in-time gauges;
- нулевые / «залипшие» cumulative counters;

без явной причины «контракт сломан», если decode молча деградирует или падает на позднем item-mismatch с менее однозначным kind.

### 3.2 Что сделано в рамках плана (diagnostic surface)

1. **Runtime gate** `JmxObservationCodec.requireCompositeTypeName` на 11 нормативных именах (6 top-level + 5 nested Export/ProcessorMetrics).
2. **Ранний порядок decode:** typeName → schemaVersion → items / ownerState.
3. **Классификация отказа:** `JmxDiagnosticKind.COMPOSITE_TYPE_NAME_MISMATCH`, category `CONTRACT_VIOLATION`.
4. **Публикация:**
   - `tracingStatus.jmxContractDiagnostics[]` (`point`, `kind`, lifetime/unrecovered episode);
   - WARN once-per-episode с rate-limit;
   - сопутствующие health-сигналы: `domain.readable=0`, `owner.present=NaN` при нечитаемом домене.
5. **Executable evidence:** unit + wiring-тесты, что gate реально вызван из каждого `*JmxView`, а не только объявлен в enum.

Kind `COMPOSITE_TYPE_NAME_MISMATCH` существовал в модели диагностики раньше; план сделал его **достижимым и обязательным** на decode path. Без gate enum обещал защиту, которой не было — это сам по себе архитектурный дефект публичной diagnostic taxonomy.

---

## 4. Диагностическая польза — по сценариям

Оценка шкалы: **высокая / средняя / низкая / отсутствует** относительно вопроса «помогает ли оператору или платформенной команде быстрее и надёжнее понять и локализовать проблему».

### 4.1 Version skew agent ↔ autoconfigure (непарный выкат)

| Аспект | Оценка |
|--------|--------|
| Обнаружение | **Высокая** |
| Локализация домена | **Средняя–высокая** (`point` / `domain`) |
| Локализация nested member | **Низкая** (один `EXPORT_OBSERVATION` на processor и exporter) |
| Root-cause «какое имя пришло» | **Низкая** (в snapshot нет expected/actual typeName) |
| Действие | **Высокая** по смыслу: откатить/выровнять артефакты, не «крутить sampling ratio» |

Это **основной** операционный сценарий ценности. Симптом без gate выглядел бы как «пропала телеметрия домена»; с gate — явный `CONTRACT_VIOLATION` + `COMPOSITE_TYPE_NAME_MISMATCH` + `unrecovered=true`.

### 4.2 Чужой / неправильный MBean под platform ObjectName

| Аспект | Оценка |
|--------|--------|
| Обнаружение | **Высокая** |
| Отличие от absent owner | **Высокая** (`readable=0` при `registered=1` + contract kind vs honest absence) |
| Отличие от transport/security failure | **Высокая** (category `CONTRACT_VIOLATION`, не `EXECUTION_FAILURE`) |

Полезно в интеграционных и security-sensitive окружениях, где ObjectName-пространство может быть загрязнено.

### 4.3 Случайный nested composite drift (Export / ProcessorMetrics)

| Аспект | Оценка |
|--------|--------|
| Обнаружение факта поломки домена | **Высокая** |
| Указание «какой nested» | **Низкая** без дополнительного расследования |
| Предотвращение ложного decode placeholder/payload | **Высокая** (gate до `ownerState`) |

Fail-closed корректность выше forensic granularity — сознательный trade-off текущего дизайна snapshot.

### 4.4 Повседневная диагностика sampling / export / scrubbing

| Аспект | Оценка |
|--------|--------|
| Польза type-name diagnostics | **Отсутствует / пренебрежимо мала** |

При согласованных артефактах kind практически не срабатывает. Ежедневные вопросы («почему drop_path вырос», «почему queue overflow») решаются метриками и observation DTO, не type-name gate.

### 4.5 CI / предотвращение регрессий

| Аспект | Оценка |
|--------|--------|
| Runtime diagnostics в CI | **Низкая** (не основной канал) |
| Соседние gates плана (golden byte-identity, classification parity, wiring-тесты) | **Высокая** как prevention |

Для комитета важно не смешивать: **prevention** (тесты/golden) и **detection** (runtime diagnostic) — разные слои. Runtime diagnostic страхует то, что prevention не покрывает: независимый выкат agent JAR vs application starter в runtime topology.

---

## 5. Что diagnostics **намеренно не** обещают

Следующие ожидания **не** являются целями текущего функционала; их отсутствие — не дефект реализации плана, а граница дизайна:

1. **Forensic payload** — snapshot не содержит `expectedTypeName` / `actualTypeName` / JSON-path nested item.
2. **Per-nested contract point** — nested Export/ProcessorMetrics failures агрегируются в top-level `*_OBSERVATION` point.
3. **Различение Absent / ContractViolation / ExecutionFailure в значении Micrometer gauge** — по ADR point-in-time остаётся `NaN`; различение — в `jmxContractDiagnostics` + `registered`/`readable`/`owner.present`.
4. **Диагностика бизнес-политики sampling** — producer normalization `decision:reasonCode` чинит *метрики решений*, но не создаёт новых contract-diagnostic kinds.
5. **Замена schemaVersion gate** — type-name и schemaVersion ортогональны; при одновременном drift первым сработает type-name.

Любое требование «сделать diagnostics богаче» должно проходить отдельным decision: стоимость API/PII/стабильности wire diagnostic schema vs частота реальных skew-инцидентов.

---

## 6. Ценность в системе сигналов (не изолированно)

Type-name diagnostic полезен **в связке**, а не как единственный индикатор:

```text
jmxContractDiagnostics.kind = COMPOSITE_TYPE_NAME_MISMATCH
        + domain.registered = 1
        + domain.readable   = 0
        + owner.present     = NaN
        + WARN once/episode
        ⇒ «домен подключен, контракт чтения сломан»
```

Противопоставление соседним состояниям:

| Состояние | Типичный сигнал |
|-----------|-----------------|
| MBean отсутствует | `registered=0`, diagnostic либо отсутствует, либо другой kind |
| Owner absent (честно) | observation читается, `owner.present=0`, **без** `COMPOSITE_TYPE_NAME_MISMATCH` |
| Contract type-name broken | `registered=1`, `readable=0`, kind `COMPOSITE_TYPE_NAME_MISMATCH` |
| Execution/transport failure | category `EXECUTION_FAILURE`, другие kind |

Именно **дифференциация** этих четырёх классов состояний составляет диагностическую пользу — больше, чем «ещё одна строка в JSON».

---

## 7. Стоимость владения

| Статья | Оценка |
|--------|--------|
| Runtime overhead | Пренебрежимо: одно сравнение строк на decode observation |
| Публичная поверхность | Один уже существовавший enum-kind + обязательный вызов в decode |
| Операционная нагрузка | Низкая при healthy deploy; алерт имеет смысл как rare/severity |
| Риск шума | Низкий при парных артефактах; высокий только если платформа системно допускает skew |
| Сопровождение | Требует синхронного изменения 11 констант + golden + wiring-тестов при rename CompositeType |

С точки зрения комитета: **высокая асимметрия** — дешёвая страховка против дорогого класса ошибок (тихо неверная/пустая телеметрия домена).

---

## 8. Attribution: не смешивать ценность плана

План `wire_contract_constants_use` содержит несколько независимых deliverable. Их diagnostic/operational ценность **разная**:

| Deliverable | Класс ценности | Диагностическая польза |
|-------------|----------------|------------------------|
| Producer `metricReasonCode` (вместо winningRule) | **Correctness метрик** | Высокая для sampling SRE; это не `jmxContractDiagnostics` |
| Explicit `FIXED_ENUM` classification + parity tests | **Prevention / contract integrity** | Высокая в CI; runtime silent |
| Golden byte-identity + `jmxWireGoldenParityVerify` | **Prevention** | Высокая в CI; runtime silent |
| Type-name gate + `COMPOSITE_TYPE_NAME_MISMATCH` | **Runtime detection / fail-closed** | Средняя, узкая, но критичная в своём классе |
| Примеры/runbook diagnostics | **Operability docs** | Средняя; без gate бесполезны |

Вывод для комитета: даже если признать runtime type-name diagnostics «узкими», **план в целом не держится только на них**. Основная correctness-ценность — producer/consumer key alignment; type-name gate — обязательный страховочный слой той же wire-программы.

---

## 9. Риски неправильной рамки ожидания

| Ошибочное ожидание | Последствие |
|--------------------|-------------|
| «Теперь всегда понятно, какой nested item сломан» | Разочарование; потребует enrichment API |
| «Это заменит метрики sampling/export» | Неверная ops-модель |
| «Kind в enum без gate достаточен» | Ложная уверенность в защитах |
| «Раз срабатывает редко — удалить» | Возврат к quiet degradation при skew |

Рекомендуемая рамка коммуникации (docs/SRE):

> Type-name diagnostic = *compatibility alarm*, не *domain performance alarm*.

---

## 10. Рекомендации комитету

### 10.1 Принять как есть

1. Сохранить type-name gate и публикацию `COMPOSITE_TYPE_NAME_MISMATCH` в `jmxContractDiagnostics` как часть wire-контракта v1.
2. Считать diagnostics **достаточными для detection + triage класса «несовместимость контракта»**.
3. Не требовать enrichment expected/actual typeName в v1 без evidence частоты инцидентов.

### 10.2 Явно не требовать сейчас

1. Отдельные `JmxContractPoint` на каждый nested composite — только при подтверждённой операционной боли.
2. Автоматический deep-diff двух `JmxObservationWireContract` в runtime (запрещён classloader isolation / tautological parity policy).
3. Дублирование причины отказа в значении Micrometer gauge.

### 10.3 Условия пересмотра (trigger для enrichment)

Комитету имеет смысл вернуться к enrichment diagnostic payload, если после production rollout наблюдается хотя бы одно:

- ≥ N (предложение: 3+) подтверждённых skew-инцидентов за квартал, где `point` оказался недостаточным для triage;
- регулярный multi-tenant hosting нескольких версий agent/starter в одном процессе/кластере;
- внешние (не platform) MBean регулярно оказываются на тех же ObjectName.

До этих триггеров усложнение snapshot — speculative API growth.

---

## 11. Краткие ответы на типичные вопросы комитета

**Q: Зря разрабатывали?**  
**A:** Нет. Зря было бы обещать forensic-детализацию. Gate и kind как compatibility alarm — оправданы и дёшевы.

**Q: Почему не положить actual typeName в tracingStatus?**  
**A:** Можно позже. Сейчас приоритет — стабильный closed enum kind без утечки потенциально произвольных строк чужого MBean в статусный контракт. Closed taxonomy важнее verbose payload на редком пути.

**Q: Чем это лучше просто `readable=0`?**  
**A:** `readable=0` говорит «не прочитали». Kind говорит **класс причины** (contract type-name), отличая от execution failure и позволяя алерт без ложных срабатываний на absent owner.

**Q: Как это стыкуется с SCHEMA_VERSION?**  
**A:** Ортогонально. `schemaVersion` — явный versioning bump; typeName — идентичность OpenType-схемы composite. Оба ранних gate; порядок: typeName, затем schemaVersion.

---

## 12. Итог для голосования

| Предложение | Рекомендация |
|-------------|--------------|
| Утвердить type-name gate + `COMPOSITE_TYPE_NAME_MISMATCH` как часть wire v1 | **За** |
| Считать diagnostics достаточными для compatibility triage | **За** |
| Требовать enrichment forensic fields в текущем релизе | **Против** |
| Удалить gate как «редко срабатывает» | **Против** |

**Формулировка решения (черновик):**

> Архитектурный комитет признаёт runtime type-name gate и diagnostic kind `COMPOSITE_TYPE_NAME_MISMATCH` оправданной fail-closed защитой JMX observation wire v1 с диагностической ценностью класса *compatibility alarm*. Требования к forensic enrichment payload в v1 не предъявляются; пересмотр — по trigger-критериям §10.3.

---

## 13. Связанные материалы

- Примеры payload и WARN: [jmx-wire-contract-diagnostics-examples](../runbook/jmx-wire-contract-diagnostics-examples.md)
- Wire ADR: [ADR-jmx-observation-wire-contract](../decisions/ADR-jmx-observation-wire-contract.md)
- Absent-owner encoding: [ADR-jmx-absent-owner-encoding](../decisions/ADR-jmx-absent-owner-encoding.md)
- Status runbook: [tracing-status-diagnostics](../runbook/tracing-status-diagnostics.md)
- Implementation log: [jmx-client-domain-split-implementation](../refactoring/jmx-client-domain-split/jmx-client-domain-split-implementation.md)
