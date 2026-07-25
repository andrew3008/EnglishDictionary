# Неделя 09 — API, SPI, internal: роли и границы

## Три роли (не три modifier)

| Роль | Кто реализует | Кто вызывает | Evolution risk |
|---|---|---|---|
| **API** | библиотека | consumer | сломать caller |
| **SPI** | внешний provider | библиотека/container | сломать implementor |
| **Internal** | библиотека | внутренний код | accidental effective contract |

`public` **≠** supported API. Framework entry point **may be** public, но не user API.

## Dual-role interface — высокая цена

Interface, который consumer **и** вызывает, **и** реализует = API + SPI одновременно.

**Must** разделять:

```java
public interface Registry { Optional<PolicyView> find(String id); }      // API
public interface PolicyProvider { Collection<PolicyDefinition> load(); } // SPI
```

## Intended vs effective role

| | Intended | Effective |
|---|---|---|
| Источник | автор policy | реальные зависимости consumers |
| Пример | `@Internal` bean | 20 сервисов заменяют по bean name |

**Must** проверять обе карты. `@Internal` без enforcement **недостаточен**.

## Contract closure

> Публично = транзитивное замыкание типов, annotations, generics, exceptions, constants, необходимых для compile/implement.

Public method returning `VendorRequest` → vendor type в API.

SPI extends framework interface → provider depends on framework.

## Dependency direction

**Must:**

```
consumer → api
provider → spi → api
implementation → api + spi + vendor
autoconfigure → api + spi + impl + Spring
starter → composition only
```

Stable types **must-not** depend on volatile vendor/runtime SDK.

Optional integration **must-not** leak vendor types в base API → adapter artifact на границе.

## Consumer API

**Must:** domain operations и stable values, не pipeline stages.

**Must-not:** consumer видит orchestration (`DefaultTracingControl`, `OtelPolicyApplier`).

Public interface **≠** extensible interface. Если consumer impl не поддерживается — **must** say explicitly; prefer sealed/final facade.

Convenience methods в API **ok** если через core operation. Convenience defaults в SPI **опасны**.

## Framework-facing contract

Auto-configuration class, processor, serializer hook — consumer = framework, не application developer. Support определяется registration mechanism.

## Contract map (минимум)

Для каждого элемента:

| Field | Content |
|---|---|
| role | API / SPI / framework / config / ops / internal / test |
| audience | кто compiles/implements/calls |
| discovery | DI / ServiceLoader / property / scan |
| stability | high / very high / none |
| owner | lifecycle owner |

Categories: consumer API, provider SPI, framework integration, configuration/metadata, operational, internal, test support.

## Must / Must-not

**Must:**
- assign role per public/protected element
- separate caller API from implementor SPI
- build transitive dependency closure
- minimal dependencies в api artifact
- document dual-role types explicitly

**Must-not:**
- treat all `public` as API
- treat all `interface` as SPI
- dual-role god interface
- vendor types in neutral signatures
- interface per internal helper

## Logical vs physical boundaries

Отдельный artifact **оправдан**, когда:
- consumers компилируются без runtime/vendor deps
- providers отделены от implementation
- dependency closure проверяется независимо

**Must-not** jar per package без изменения dependency graph или release cadence.

Package taxonomy (`api`, `spi`, `internal`) **helps** navigation; **must not** replace contract documentation.

## Public dependency closure — checklist

**Must scan:** parameters, returns, fields, generic bounds, annotations, super-types, declared exceptions, inline constants.

Compile-time constants **may** embed in consumer bytecode — evolving values prefer accessor method.

Serialization (Jackson, config binding): constructors, record components, property names = data contract.

## Negative statement (documentation)

**Must** явно указать unsupported:
- concrete implementation classes
- generated bean names
- internal packages

Negative statement работает **только** без examples, зависящих от internal surface.

## Call direction vs obligation direction

Execution graph может менять направление стрелки (callback), но obligation остаётся двусторонним:
- consumer **must** implement callback contract
- library **must** invoke per protocol

Review **must** фиксировать оба направления для каждого surface.

## Test support API

Fixtures/probes для consumer tests **may** быть supported test API.

**Must-not** normalize reflection/internal imports в consumer tests — offer supported fixtures instead.

## Ключевые тезисы

1. API/SPI/internal — роли в контракте, не package names.
2. Dual-role interface = повышенная цена evolution.
3. Effective contract = реальные зависимости.
4. Public surface = transitive closure.
5. API artifact must have minimal dependencies.
6. Physical module boundary follows dependency graph, not diagram.
7. `@Internal` + exports + build rules = enforcement trio.
