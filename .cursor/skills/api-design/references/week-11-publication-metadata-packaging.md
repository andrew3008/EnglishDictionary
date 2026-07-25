# Week 11 — Публикация, metadata, packaging

## Контекст

Publication выводит architectural decisions в bytes и machine-readable contracts. Два параллельных контракта: POM и Gradle Module Metadata.

## POM и Gradle Module Metadata

Gradle consumer предпочитает `.module`; Maven читает POM.

**Must:** проверять **отдельно** для каждого формата:

- coordinates
- compile/runtime edges
- versions/constraints
- optional flags
- variants/artifacts
- capabilities
- Java version attributes
- exclusions, relocation
- sources/Javadoc association

Различие semantics **must** быть намеренным и документированным.

## Publication component

**Must:** публиковать из `components.java`, не случайный JAR.

```gradle
publishing {
    publications {
        mavenJava(MavenPublication) {
            from components.java
        }
    }
}
```

Ручной `tasks.jar` без component metadata → bytes без правильных dependencies и variants.

### Version mapping

Resolved publication повышает reproducibility release, но может превратить local resolution policy в downstream constraint.

**Must** ответить: диапазон или tested exact set; override freedom; rich constraints в POM; locking для producer или consumer; release train ownership.

Version mapping — **contract decision**, не косметика POM.

## Состав публикации

Production library **must** включать (по policy):

| Artifact | Назначение |
|---|---|
| main JAR | runtime classes |
| POM | Maven contract |
| Gradle Module Metadata | Gradle contract |
| sources JAR | IDE, debugging |
| Javadoc JAR | API discovery |
| checksums/signatures | integrity |
| BOM metadata | version alignment |
| licenses/notices | redistribution |
| SBOM/provenance | supply chain |

**Must-not:** tests, fixtures, benchmarks, internal samples в runtime artifact.

### Classifiers

Sources/Javadoc — OK. **Must-not:** classifier вместо independently consumable capability с собственными dependencies. Maven dependency на classifier не получает отдельный POM graph.

Отдельная capability → отдельные coordinates или feature variant с явной Maven strategy.

## Relocation координат

Relocation POM указывает новые coordinates при смене groupId/artifactId.

**Must-not:** relocation POM не решает package rename, binary compatibility, old/new JAR conflict, duplicate classes, config key migration.

**Must-not:** один binary под old и new coordinates одновременно. Old coordinates — marker/relocation или facade без duplicate classes.

## Immutability release

Published release **must-not** заменяться другим binary под тем же GAV.

Различать: immutable releases, mutable snapshots, staged RC, promoted artifacts **без rebuild**.

### Promote, не rebuild

Production **must** получать те же bytes, прошедшие verification. Иначе tested digest ≠ deployed digest.

## Snapshots и release candidates

Snapshot — evolving line, **must-not** долгосрочный production contract: содержимое меняется, cache policy, graph drift, rollback неоднозначен.

Staged candidate:

```text
build once → publish staging → external consumer tests
           → approve → promote immutable release
```

## Reproducible builds

Одинаковые source + instructions + environment → bit-for-bit identical artifacts.

Источники nondeterminism: timestamps, absolute paths, locale, UUID, processor output, JDK version, permissions.

**Must:** gate сравнивает два isolated builds. Configuration flag без byte comparison — не доказательство.

## Integrity: checksum, signature, SBOM, provenance

| Механизм | Вопрос |
|---|---|
| checksum | bytes не изменились случайно |
| signature | artifact подписан identity |
| SBOM | какие components заявлены |
| provenance | где и как построен |
| reproducibility | можно ли получить те же bytes |

Ни один **не заменяет** остальные.

SBOM **must** учитывать embedded/relocated components. Shaded dependency не исчезает из supply-chain obligation.

## POM metadata

Помимо dependencies: name, description, URL, license, SCM, developers, issue management, relocation.

**Must-not:** library POM навязывает consumer repositories. Repository trust — application/organization policy.

## Publication pipeline

```text
1. Фиксировать source revision и toolchain
2. Строить archives reproducibly
3. Генерировать POM/GMM и auxiliary artifacts
4. Structural/API/dependency checks
5. Публиковать в isolated staging repository
6. External Gradle/Maven consumers
7. Runtime и optional scenarios
8. SBOM, signatures, provenance
9. Promote те же bytes в release repository
10. Сохранить release evidence
```

**Must-not:** `publishToMavenLocal` как release verification — stale artifacts, substitutions, past builds.

Release test **must:** resolve только из isolated staging и approved upstream repositories.

## Shading и relocation

Relocation создаёт private copy под новым package prefix. Изолирует incompatible implementation от application classpath.

Цена: stack traces, reflection, serialization, service metadata, resources, native-image config, security scanning/SBOM, licenses, patch management, source mapping.

**Must-not:** relocation types в public API, service metadata, serialized names, reflection config.

Shading оправдана, когда: true private implementation, version conflict вероятен, размер приемлем, metadata переписывается корректно, команда принимает ownership за CVE.

## Fat JAR vs library JAR

| Продукт | Contract |
|---|---|
| Executable application archive | агрегирует runtime |
| Reusable library JAR | внешняя dependency metadata |

Fat/uber JAR как library → duplicate embedded classes, потеря module identity, licenses, невозможность mediation, hidden vulnerabilities, resource collisions.

**Must-not:** Spring Boot starter как bootJar с nested dependencies.

Self-contained CLI — отдельный distribution artifact с другими coordinates.

## Resources и metadata merge

JAR содержит: `META-INF/services`, auto-configuration imports, configuration metadata, native-image hints, `module-info.class`, MR entries, licenses, schemas.

**Must:** merge strategy для каждого namespace.

Пример policy:

```text
META-INF/services/**     → merge distinct lines
META-INF/LICENSE*        → preserve/rename per policy
module-info.class        → never blind-merge
application.properties   → fail
unknown duplicate          → fail
```

**Must-not:** `duplicatesStrategy = EXCLUDE` глобально — conflicts становятся order-dependent runtime behavior.

## ServiceLoader и provider metadata

`META-INF/services/<service-type>` и JPMS `provides ... with ...` — publication contract.

Контракт: service type, provider class identity, constructor requirements, classloader visibility, multiplicity, order policy, failure semantics.

При aggregate JAR **must:** merge service files, не перезаписывать последним.

При relocation **must:** provider class names переписаны; service type не приватизирован; JPMS согласован; reflection config обновлён; licenses сохранены.

## Ключевые тезисы

1. POM и GMM — два publication contracts.
2. Publication из component, не случайный JAR.
3. Release coordinates — immutable contents; promote, не rebuild.
4. Shaded dependency остаётся в SBOM.
5. Fat JAR и reusable library — разные продукты.
6. Unknown duplicate resource — fail, не EXCLUDE.
7. Service descriptors — operational runtime contract.
