# Week 13 — Testing, review, checklists

## Контекст

Conditions тестируются как executable decision table, не как happy-path context smoke. Review завершается ownership graph, diagnostics и evolution diff — не «annotations выглядят нормально».

## Тестовая матрица

Минимум по измерениям:

| Измерение | Cases |
|---|---|
| Classpath | present / absent |
| Enablement | missing / true / false / invalid |
| Replacement | none / one / multiple |
| Candidate choice | primary / no primary / fallback / qualifier |
| Hierarchy | current / parent / both |
| Context kind | non-web / servlet / reactive |
| Optional integrations | dep present / absent |
| Ordering | provider before / after |

Полное декартово произведение не обязательно. Pairwise — только после фиксации invariants и high-risk combinations.

### Assertions

Не только `hasSingleBean`:

- exact type и (если contract) name;
- default отсутствует при replacement;
- user instance сохранена;
- unrelated slices остаются;
- startup failure — ожидаемая cause/message;
- customizers в заявленном порядке;
- condition outcome объясняет negative case;
- optional class не загружается без dependency.

## Инструменты

```java
private final ApplicationContextRunner contextRunner =
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AcmeAutoConfiguration.class));

@Test
void backsOffWhenUserProvidesService() {
    contextRunner
        .withUserConfiguration(UserServiceConfiguration.class)
        .run(context -> {
            assertThat(context).hasSingleBean(AcmeService.class);
            assertThat(context).doesNotHaveBean(DefaultAcmeService.class);
        });
}
```

`withPropertyValues`, `withBean`, `withClassLoader`, user configuration — отдельные координаты.

- `FilteredClassLoader` — отсутствие optional library.
- Web / Reactive runners — web-specific slices.
- Hierarchy — явный parent/child; доказать SearchStrategy.
- Native/AOT — отдельные build/runtime tests (`ApplicationContextRunner` ≠ native).

## Negative tests и mutations

Back-off требует доказать **отсутствие** platform definition. Наличие user bean недостаточно (оба могут сосуществовать).

Negative assertions: default type отсутствует; internal default name отсутствует; side effect не произошёл; disabled slice не публикует operations; missing optional class → no linkage error.

Мутации для ревью: concrete return type; удалить `after`; CURRENT вместо ALL; второй candidate; `@Primary`; condition method→class; indexed property без base key; удалить optional dep. Если tests не ловят — контракт не защищён.

## Conditional Configuration Review

1. **Outputs** — все definitions: runtime, internal, customizers, operational, lifecycle, hints, analyzers; owner slice + роль.
2. **Inputs** — classes, properties/defaults, bean conditions, qualifiers/names, context kind/hierarchy, ordering.
3. **Decision table** — registration / back-off / failure / diagnostic / test; отдельно opt-in без prerequisite и multi-candidate.
4. **Ownership graph** — какой seam подавляет какой default; adapters; class-level не удаляет unrelated.
5. **Lifecycle** — optional isolation; no bean creation in conditions; no SpEL early init; registration ≠ creation; no I/O.
6. **Diagnostics** — report / exception / analyzer / log / Actuator; back-off ≠ ошибка; явная ошибка ≠ silent no-match.
7. **Evolution** — diff decision tables; опасны: новый opt-out, расширенный classpath match, смена replacement type, SearchStrategy, primary/default, подъём condition, rename auto-config class.

## Анти-паттерны

1. `@ConditionalOnMissingBean` без target на concrete return type.
2. Один condition вокруг всей auto-configuration.
3. Classpath как intent.
4. Silent opt-in failure (`enabled=true`, prerequisite отсутствует).
5. First wins by registration order.
6. Ambiguity treated as absence.
7. Bean name как скрытый protocol.
8. SpEL с bean reference.
9. Collection через base-key presence.
10. Ordering как универсальное лекарство.
11. Back-off без negative assertions.
12. Remote readiness как condition.

## Контрольный чек-лист

**Семантика**

- [ ] Inputs/outputs/причины no-match для каждого slice
- [ ] Activation отделена от replacement
- [ ] Состояния Unsupported / Disabled / Defaulted / Overridden / Ambiguous / Invalid
- [ ] Explicit opt-in без prerequisite не скрывается
- [ ] Back-off не подменён `@Primary` / `@Fallback`

**Bean conditions**

- [ ] Target = semantic role; return type для discovery
- [ ] Type/name/annotation matching осознан
- [ ] SearchStrategy проверен для hierarchy
- [ ] Multi-candidate policy; condition ↔ injection согласованы
- [ ] ignored / parameterizedContainer документированы и протестированы

**Properties**

- [ ] Truth table понятна; matchIfMissing согласован с defaults/docs
- [ ] Новый default не активирует surprise после minor
- [ ] Collections не через base-key; property ≠ runtime toggle

**Classpath / lifecycle**

- [ ] Marker classes; optional isolation; string names в meta-annotations
- [ ] No beans / remote I/O в conditions; no SpEL early init
- [ ] ConfigurationPhase соответствует знанию

**Ordering / operations / tests**

- [ ] Ordering = реальная dependency; имена auto-config как contract учтены
- [ ] Optional ops slices независимы; ops replacement policy ясна
- [ ] Матрица + negative assertions + hierarchy/web variants где нужно

## Ключевые тезисы

- Тест conditions = decision table + negative proofs.
- Review = outputs → inputs → table → ownership graph → lifecycle → diagnostics → evolution.
- Silent opt-in failure и ambiguity-as-absence — P0 дефекты контракта.
- Ordering и bean names без публикации — accidental Spring API.
