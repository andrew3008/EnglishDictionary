# Week 14 — Testing, review, checklists

## Контекст

Configuration API тестируется как schema + resolution + behavior. Review идёт сверху вниз: язык → resolution → binding → tooling/ops → evolution → доказательства.

## Тестовая стратегия

| Уровень | Что проверяет |
|---|---|
| Converter unit | textual grammar, units, malformed, boundaries |
| Binder slice | key-to-type, nested, maps, lists, absence |
| Validation | missing, ranges, cross-field, mode-specific |
| Environment matrix | precedence/overrides (YAML, env, CLI) |
| Auto-config context | влияние на bean graph |
| Metadata artifact | keys, descriptions, hints в JAR |
| Migration | alias, conflict, warning |
| Security diagnostics | redaction в exceptions/Actuator/logs |

```java
new ApplicationContextRunner()
    .withConfiguration(AutoConfigurations.of(ClientAutoConfiguration.class))
    .withPropertyValues(
        "platform.client.timeout=2s",
        "platform.client.retry.max-attempts=3")
    .run(context -> {
        assertThat(context).hasNotFailed();
        assertThat(context.getBean(ClientProperties.class).timeout())
            .isEqualTo(Duration.ofSeconds(2));
    });
```

**Must:** отсутствие, empty, malformed, boundary, source override, profile/list replacement, unknown key, deprecated key, secret-bearing failure. Happy-path file недостаточен.

## Configuration API Review

1. **Инвентаризация** — keys, groups, dynamic maps, aliases, env names, defaults, metadata, operational representations.
2. **Семантическая модель** — задача; type/grammar; absence/empty/null; authoritative default; range; security class; влияние на bean graph/runtime.
3. **Resolution** — sources, precedence, profiles, imports, list replacement, map merge, origin; несколько значений одновременно.
4. **Binding/validation** — conversion vs invariants; nested; early lifecycle; качество failures.
5. **Tooling/ops** — metadata ↔ runtime; hints ↔ values; deprecations ↔ aliases; Actuator ↔ redaction.
6. **Evolution** — старые deployments; rolling upgrade; оба ключа; срок удаления; migration diagnostic.
7. **Доказательство** — каждое обещание ↔ test. Непроверяемое = предположение.

## Анти-паттерны

1. Properties object как набор SDK builder fields.
2. `timeout-ms` в имени вместо unit в value.
3. Boolean explosion вместо mode enum.
4. Defaults в трёх несогласованных местах (field / metadata / condition).
5. List там, где нужна identity map.
6. `@Value` + SpEL как публичная группа настроек.
7. Secret в `toString` / validation message / health.
8. Ignore invalid fields.
9. Metadata без проверки published JAR.
10. Deprecation marker без runtime alias.
11. Promise dynamic reload без control-plane protocol.
12. Properties bean с lookup beans / network.

## Контрольный чек-лист

**Язык / namespace**

- [ ] Prefix владеет capability; нет коллизий Boot/соседних модулей
- [ ] Canonical kebab-case единообразен
- [ ] Env representation и collision table проверены
- [ ] Дерево = mental model, не implementation graph

**Defaults / binding**

- [ ] Authoritative default согласован со слоями
- [ ] Absent/null/empty/Optional семантики явные
- [ ] Lists replace; map merge/delete документированы
- [ ] Immutable DTO; setters без side effects

**Types / validation**

- [ ] Duration/DataSize/enum где уместно
- [ ] Conversion ≠ validation ≠ remote health
- [ ] Cross-field/mode invariants; Origin-aware failures
- [ ] Binding до side effects

**Metadata / ops / evolution**

- [ ] Metadata в JAR совпадает с runtime keys
- [ ] Deprecation + alias + conflict policy
- [ ] Secrets sanitization; Actuator exposure policy
- [ ] SemVer classification для default/validation/rename
- [ ] Bootstrap vs dynamic control разделено

**Tests**

- [ ] Converter + binder + validation + context matrix
- [ ] Migration + secret redaction + metadata artifact

## Ключевые тезисы

- Configuration contract шире Java properties class.
- Review без Environment matrix и metadata artifact неполон.
- Непроверяемое обещание default/alias/redaction — риск релиза.
