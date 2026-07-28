# Week 16 — Governance, review, checklists

## Контекст

Governance отвечает: что публичный контракт; кто владеет; какие изменения допустимы; какими доказательствами в release. Automation без policy и policy без automation одинаково опасны.

## Governance model

Минимум: Public Contract Inventory; owners; ADRs; compatibility policy; change classification; mandatory reviewers; automated gates; exception process; release evidence; post-release feedback.

## Contract ownership

| Контракт | Возможный owner |
|---|---|
| Java API/SPI | platform API owner |
| Configuration | starter + DX |
| Dependencies | build/platform governance |
| Metrics/traces | observability platform |
| Health/probes | SRE/runtime |
| Security/redaction | security |
| Release/version | library governance |

Коллективное владение без final approver = нет владельца. Owner = семантическая целостность + migration policy.

## Change classification

PR перечисляет затронутые surfaces, не один checkbox «breaking»:

| Изменение | Impact |
|---|---|
| Rename public method | Java source/binary |
| Новый default | behavioral/configuration |
| Rename bean | Spring/operational |
| Новый transitive dep | dependency/runtime |
| Rename metric tag | dashboards/alerts |
| Новый health status | orchestration |
| Log level change | alerting/cost |
| Новое endpoint field | wire consumer |
| Иной back-off target | topology/ownership |

Pre-production: breaking предпочтительнее cosmetic shims — но docs/tests/ops schema синхронизируются.

## Release gates

См. week-16-testing-signals-gates. Gate обходят → исправить точность/policy, не навсегда suppress.

## Exception process

Содержит: нарушенный rule; scope; rationale; risk; affected consumers; mitigation; owner; expiration; follow-up; approval.

**Must-not:** `ignore all changes in package`. Pre-production exception может разрешить breaking без deprecation — **не** рассинхрон docs/tests/ops schema.

## Документация как gate

Installation/deps; minimal config; defaults/effective; replacement/extension; operational signals; health semantics; metric catalog; diagnostic codes; security/exposure; failure/recovery; troubleshooting; baseline; migration.

Генерируемый catalog полезен; не заменяет семантику. Metric catalog без recording point и operational question = список имён.

## Runbook contract

```text
signal → interpretation → impact → verification
  → immediate action → escalation → recovery confirmation
```

Для actionable code: условия; причины; безопасные reads; запрещённые действия; confirmation recovery; escalation. Alert без действия сомнителен. Starter owner участвует в alert semantics.

## Deprecation / removal

Usage inventory; replacement; dual-publish (cost!); dashboards migration; release notes; end date; removal gate. Pre-production: атомарный break + обновить consumers часто чище dual-publish. Deadline удаления проверяется.

## Анти-паттерны

1. **Health всегда UP** — подтверждает только наличие bean. Связать с domain state / diagnostic / capability descriptor.
2. **Лог как единственная диагностика** — нет текущего состояния; format нестабилен. Log = transition; health/gauge = state; counter = частота.
3. **Giant SpringBootTest** — медленный, одна комбинация, скрывает matrix. Portfolio вместо monolith.
4. **Governance через wiki и память** — без executable rules устаревает. Machine detection + human judgment + recorded decision + synced docs/tests.

## Operational Contract Review

1. Какую problem решает каждый signal?
2. Authoritative runtime state model?
3. Consumer и действие?
4. Observation vs control?
5. Cardinality/security/redaction?
6. Instrumentation ownership / duplicates?
7. Test evidence / gates?
8. Change classification / migration?
9. Inventory обновлён?

Review завершается обновлённым inventory и набором gates — не «выглядит хорошо».

## Контрольный чек-лист

- [ ] Operational inventory (startup/health/metrics/logs/traces/endpoints/audit)
- [ ] State model + projections согласованы
- [ ] Observation ≠ control; write ops с audit
- [ ] Health bounded; readiness policy; redaction
- [ ] Metric identity + cardinality budget; no duplicate instrumentation
- [ ] Context matrix + signal/transition tests + gates
- [ ] Owners + change classification + exception process
- [ ] Docs/runbooks/metric catalog синхронизированы
- [ ] Deprecation deadlines исполнимы

## Ключевые тезисы

- Governance = inventory + owners + gates + exceptions с expiration.
- Breaking в pre-production допустим; рассинхрон evidence — нет.
- Review обновляет inventory и gates, не выдаёт словесный verdict.
- Anti-patterns: always-UP health, log-only, giant BootTest, wiki governance.
