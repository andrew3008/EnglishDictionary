# Неделя 04 — Cognitive Walkthrough, Usability Review, чек-листы

## Cognitive Walkthrough — workflow

### Подготовка (must)

- персона: app developer / platform integrator / extension author / SRE
- задача и критерий успеха
- исходное состояние проекта
- инструменты: IDE, docs, logs, Actuator
- разумные знания vs знания только автора

### Четыре вопроса на каждом шаге

1. Поймёт ли пользователь, какого результата достичь?
2. Заметит ли доступное действие?
3. Свяжет ли действие с намерением?
4. Получит ли понятную обратную связь?

**Must применять** к полному пути: dependency → import → config → injection → call → failure → verification.

### Фиксация дефектов

| Шаг | Намерение | Сигнал | Ошибочная гипотеза | Последствие | Изменение контракта |
|---|---|---|---|---|---|
| Configure exporter | OTLP export | `enabled` + `type` | endpoint has default | no-op runtime | explicit mode + validation |

Дефект **must** описывать разрыв, не вкус reviewer.

## Usability Contract Review — 7 шагов

1. **Top tasks** — 5–7 задач основной ценности/риска; must not flat list all methods
2. **Decision inventory** — явные decisions, defaults, required knowledge, feedback; особенно скрытые решения системы
3. **Surface transitions** — README ↔ JavaDoc ↔ IDE ↔ YAML ↔ logs ↔ Actuator; must сохранять одну conceptual model
4. **Cognitive dimensions** — viscosity, hidden deps, premature commitment, hard ops, role-expressiveness, visibility, error-proneness, progressive eval
5. **Error journeys** — для каждого top task минимум: missing required, config conflict, wrong lifecycle, unavailable dependency, user override, deprecated option
6. **Progressive disclosure** — simple/advanced/SPI: общий словарь, defaults, diagnostics
7. **Usability budget** — сравнение вариантов по entry points, concepts, decisions, hidden decisions, feedback latency, recovery distance

## Чек-лист: task journey

- [ ] Персоны и top tasks определены?
- [ ] Одна каноническая entry point для типового сценария?
- [ ] Путь: config → execution → feedback → recovery?
- [ ] Onboarding, operations, migration — не только первый запуск?

## Чек-лист: когнитивная нагрузка

- [ ] Intrinsic complexity представлена честно?
- [ ] Extraneous decisions устранены/локализованы?
- [ ] Понятия образуют устойчивые chunks?
- [ ] Нет скрытого порядка, единиц, precedence в памяти?
- [ ] Simple path не создаёт operational complexity?

## Чек-лист: discoverability и naming

- [ ] IDE completion — supported API, не internal noise?
- [ ] Имена выражают роль, эффект, temporal semantics?
- [ ] Java, config, metrics, docs — общий словарь?
- [ ] Recognition, не строковый recall?

## Чек-лист: signatures, overloads, builders

- [ ] Параметры одного типа трудно перепутать?
- [ ] Порядок параметров согласован?
- [ ] Overloads — одна semantic operation?
- [ ] Builder: required values и validation point ясны?
- [ ] Staged builder не compile-time puzzle?
- [ ] Convenience API → канонический core?

## Чек-лист: defaults и automation

- [ ] Defaults безопасны, стабильны, наблюдаемы?
- [ ] Convention: activation, override, conflict policy?
- [ ] Нет silent fallback против intent?
- [ ] Classpath decisions видимы в diagnostics?

## Чек-лист: errors и feedback

- [ ] Ошибка рядом с причиной?
- [ ] Message: правило, значение, remediation?
- [ ] Taxonomy → recovery model?
- [ ] Effective configuration без secrets?
- [ ] Operational vocabulary = runtime state?

## Чек-лист: Spring Boot

- [ ] Prefix/hierarchy = conceptual model?
- [ ] Configuration metadata полна для IDE?
- [ ] Back-off semantics документирована?
- [ ] Неоднозначность → fail-fast + domain diagnostics?
- [ ] Condition outcome на языке capability?

## Контрольные вопросы (выборка)

1. Почему usability по task journey, не по методу?
2. Intrinsic vs extraneous load?
3. Почему короткий API может иметь высокую нагрузку?
4. Chunk и когда тип уменьшает нагрузку?
5. Viscosity — что измеряет?
6. Hidden dependency в starter?
7. Premature commitment vs required parameter?
8. `long timeout` как hard mental operation?
9. Discoverability vs public surface?
10. Когда progressive disclosure = два API?
11. Decision count vs строки кода?
12. Свойства сильного default?
13. Parameter object vs options bag?
14. Overload как обещание эквivalence?
15. Actionable error message?
16. Effective configuration как usability?
17. Back-off как extension contract?
18. Recovery distance?

## Итоговые тезисы

1. Task journey — единица оценки.
2. Cognitive Dimensions — профессиональный язык неудобства.
3. Discoverability = signal-to-noise, не размер surface.
4. Progressive disclosure требует одной модели на всех уровнях.
5. Error path, feedback, recovery — основные поверхности API.
6. Metadata и condition diagnostics — интерфейсы стартера.
7. Auto-configuration — объяснимая система решений.
8. Лучший API делает следующий корректный шаг видимым, естественным, проверяемым.

## Практическое задание (артефакт)

**Must produce:** Cognitive Load Map + архитектурное заключение с разделением cosmetic vs contract changes (rename, default, result type, effective config diagnostics, extension boundary).
