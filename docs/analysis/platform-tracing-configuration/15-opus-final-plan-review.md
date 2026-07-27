# Финальный review плана рефакторинга конфигурации трассировки

**Дата:** 2026-07-27  
**Reviewed artifact:** `docs/analysis/plans/immutable_config_namespace_split_ba8da442.plan.md`  
**Change specification:** `.cursor/plans/финальная_правка_плана_907a85ff.plan.md`

---

## 1. Итоговый вердикт

**READY_FOR_ARCHITECTURE_COMMITTEE_REVIEW**

Implementation GO **не предоставлен**. Legacy deletion GO **BLOCKED**.

---

## 2. Найденные критические противоречия

| ID | Противоречие | Разрешение |
|---|---|---|
| C-01 | Draft описывал восстановление удалённого remote key через canonical default owner | §12: missing leaf → REJECTED; explicit Config Server default only |
| C-02 | «Control ObjectName только на private server» | §11: имя на обоих серверах; writer только private |
| C-03 | §20 перечислял JMX domain groups вместо entry-point families | §20: шесть families + таблица 16 mutations |
| C-05 | P3 exit допускал transitional adapter от legacy writers | §19 P3: unreleased overlap conditions; запрет dual owners |
| C-04 / S-04 | «S-04 checkpoint later» и blanket Agent→javaagent | §6.1: закрытый перечень исключений с inventory IDs |
| C-09 | «удалить в earliest safe train» без owner | §16: explicit pre-production acceptance до P6 |
| Dual plan | Два нормативных plan-файла | Альтернативный draft удалён; канон — `immutable_config_*.plan.md` |

---

## 3. Исправленные repository inaccuracies

- Числа зафиксированы по S-04/E1: 78 properties, 29 env vars, 63 MBean members, 16 mutations, 47 read-only, 15 ungated platform writes.
- Устаревшие 64/67 properties и 71/55 JMX counts **не используются**.
- RESOURCE_ATTRIBUTE-001 отделён от CONFIG_PROPERTY-036 (resource attribute ≠ configuration key).
- Пять outbound duplicates переформулированы как DUPLICATED_ACTIVE, не «no caller».
- FIXTURE_ONLY policy names отделены от production Category A leaves.
- BOM defect Boot 3.5.5 + cloud-context 4.1.5 сохранён как independent finding.

---

## 4. Применённые нормативные правки

| Patch | Раздел плана |
|---|---|
| C-01 Config Server deleted-key semantics | §12, §19 P4, §22 |
| C-02 Control ObjectName dual registration | §11 |
| C-03 Six mutation families matrix | §20 |
| C-04 Namespace / S-04 exceptions table | §6.1 |
| C-05 No active dual architecture | §19 P3, §25 |
| C-06 Apply outcome precision | §10 |
| C-07 E3 AcceptedPolicyStateStore role | §9 |
| C-08 E7 read-member boundary | §11, §17, §20, §27 |
| C-09 Security debt acceptance | §16, §28 |
| C-10 Authority/defaults/manifest | §12, §13 |
| Russian normative vocabulary | весь документ |
| Removed CATEGORY_A HTML markers | §7 |
| Removed tracingConfigurationPlanVerify anchors | N/A (task removed in 08de01e) |

---

## 5. Решения, которые не переоткрывались

- Category A: ровно восемь leaves по binding decision 14 — **не переоткрыто**.
- Gates E1, E2, E4, E5, E9 — **CLOSED**, не переоткрыты.
- Agent-first topology, private writer, platform read-only после cutover — **binding**.
- No HTTP mutation, no new module, no SDK production parity — **binding**.
- E3 primitive choice (F1/F2) — **не выбрано**; остаётся OPEN.
- E6 Boot/Cloud/BOM — **не выбрано**.
- E7 exact read-member delta — **не утверждено**.
- E8 Implementation GO — **не предоставлено**.

---

## 6. Правки phases/deletion

- P0–P7 сохранены с preconditions, ADD/REWRITE/DELETE, tests, exit, rollback unit.
- P6 — единственный coherent cutover для legacy writers и namespace.
- Rollback unit после cutover = entire unreleased train.
- Legacy deletion matrix: six families с phase P6 и zero-reference verification.

---

## 7. Правки security

Принято решение **A**: explicit acceptance текущего security debt до P6 cutover.

- Owner: команда `platform-tracing`
- Scope: pre-production only
- Production release blocked до zero platform write surface
- Remote JMX write forbidden в shared/external environments
- Все legacy paths удаляются в P6 atomically
- Отдельная P0 security implementation **не создавалась**

---

## 8. Открытые gates

| Gate | Status |
|---|---|
| E3 | OPEN — accepted-state primitive |
| E6 | OPEN — Boot/Cloud/BOM + refresh delivery |
| E7 | OPEN — JMX read-only delta |
| E8 | OPEN — Implementation GO |

---

## 9. Остаточные риски

1. **Потеря module-count verify** — вместе с удалением `tracingConfigurationPlanVerify` автоматическая проверка 16 modules в `settings.gradle` отсутствует; контроль — manual review.
2. **Реликтовые маркеры** `CATEGORY_A_BEGIN/END` в binding decision 14 — безвредны, task удалена.
3. **Scan-safe ограничение этого отчёта** — буквальные configuration property и environment variable names намеренно omitted; ссылки через § плана и S-04 inventory IDs.
4. **Dual registration ObjectName** — целевой контракт; S-03 доказала discovery/isolation, не production dual registration.
5. **BOM misalignment** — independent defect; closure under E6.

---

## 10. Готовность к архитектурному комитету

План:

- полностью на русском языке;
- self-contained (28 разделов);
- repository-grounded (S-02/S-03/S-04/E1 counts);
- содержит одну целевую архитектуру без council debate;
- статус **READY_FOR_ARCHITECTURE_COMMITTEE_REVIEW** в §1 и YAML front matter.

Evidence tasks сохранены: `configProvenanceVerify`, `jmxManagementContractVerify`, `privateMBeanServerVerify`, `tracingConfigurationInventoryVerify`.

**Implementation GO: NOT_GRANTED**  
**Legacy deletion GO: BLOCKED**
