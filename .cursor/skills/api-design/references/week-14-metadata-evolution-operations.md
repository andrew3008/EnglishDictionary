# Week 14 — Metadata, evolution, operations

## Контекст

Metadata — tooling API. Binding — runtime API. Secrets, Actuator и deprecation — operational/compatibility поверхность Configuration API.

## Metadata как tooling contract

`META-INF/spring-configuration-metadata.json`: groups, properties, hints.

Помогает: completion; type/default/description; deprecation; value hints; group linkage.

**Must-not:** считать metadata управлением binder. Отсутствие в metadata ≠ bind failure. Hint ≠ runtime validation. Deprecated marker ≠ migration.

Расхождение IDE vs runtime — опасный дефект. Metadata и binding проверяются отдельно.

## Генерация

`spring-boot-configuration-processor` → metadata в published JAR.

Проверять artifact, не только `build/classes`:

- [ ] metadata в JAR
- [ ] все intended keys
- [ ] нет утечки internal groups
- [ ] types/defaults корректны
- [ ] descriptions читаемы
- [ ] deprecations/hints объединены
- [ ] нет stale entries

## Descriptions и defaults

Javadoc → descriptions (record `@param`). Plain text. Хорошее описание: что; grammar/unit; absence; связи; security/ops consequence. Плохое: «The batch size.»

Default extraction эвристична — не authoritative без проверки.

## Value hints

Fixed values / providers (class reference, logger, bean reference, profile). `handle-as` для String-as-other-type.

Ограничения: IDE support различается; hint не ограничивает binder; bean-reference ≠ lookup; `any` разрешает произвольное.

Hints = discoverability. Closed vocabulary → enum сильнее. Open → hint + качественная runtime ошибка.

## Deprecation keys

Property живёт в Git/Helm/secrets/pipelines → длинный migration window.

Metadata: level warning/error; reason; replacement; since. Runtime alias обязателен, иначе marker только tooling.

| Старый | Новый | Результат |
|---|---|---|
| нет | нет | default |
| задан | нет | старое + warning |
| нет | задан | новое |
| одинаково | одинаково | новое + diagnostic |
| по-разному | по-разному | conflict failure или documented precedence |

Молчаливое предпочтение одного = скрытая ошибка rollout. Security-sensitive → explicit conflict.

## Compatibility matrix

| Изменение | Типично |
|---|---|
| Optional key + безопасный default | minor |
| Изменён default существующего | часто major |
| Расширен accepted range | minor |
| Сужен range / усилена validation | major |
| Добавлен enum value | analysis |
| Rename + working alias | minor + deprecation |
| Удалён alias | major |
| List model → map | major |
| Property стала обязательной | major |
| Новая textual form | minor |

«Исправление неправильного default» может быть breaking. Transitive SDK default change → configuration semantics change.

## Secrets

Определить: допустимые sources; mounted file/config tree; inline?; exception appearance; Actuator sanitization; rotation без restart; очистка credential objects.

**Must-not** в: `toString`; startup log; validation rejected value; metrics tag; health detail; condition outcome.

Имя с `password` недостаточно — tokens, keys, JSON credentials проверять явно. String secret ≠ обещание memory erasure.

## Actuator /configprops и /env

Контракт: exposure; auth; additional sanitization; custom `toString`/nested; `SanitizingFunction`; origin для troubleshooting.

`configprops` = bound groups; `env` = Environment. Разница ловит «raw есть, не связалось» / «model default есть, Environment key нет».

Endpoint ≠ единственная диагностика startup failure.

## Static vs dynamic

`@ConfigurationProperties` = bootstrap snapshot. External store change ≠ dynamic reload.

Dynamic требует: revision delivery; atomicity; validation; thread visibility; rollback; resource lifecycle; audit/metrics; in-flight behavior.

```text
bootstrap configuration -> создание runtime component
runtime control plane    -> управляемое изменение состояния
```

Restart-only — писать прямо. Refresh — temporal semantics в Configuration API.

## Programmatic Binder

`Binder.get(environment).bind(name, Bindable.of(...))` — dynamic groups / tooling. `bind` vs `bindOrCreate` меняет absence semantics.

Custom `BindHandler` — локально. **Must-not:** заменять обычный `@ConfigurationProperties` без причины (теряются convention, metadata, registration).

## Ключевые тезисы

- Metadata ≠ binder; проверять artifact JAR.
- Deprecation = marker + runtime alias + conflict policy.
- Default change часто major.
- Secrets вне всех diagnostic surfaces.
- Bootstrap config ≠ runtime control plane.
