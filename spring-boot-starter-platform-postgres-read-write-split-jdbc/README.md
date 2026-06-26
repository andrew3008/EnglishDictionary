# spring-boot-starter-platform-postgres-read-write-split-jdbc

Spring Boot стартер для CQRS-маршрутизации JDBC-подключений к кластеру PostgreSQL: два независимых HikariCP-пула (write / read), single activation gate по `PG_TOPOLOGY=DIRECT`, zero-config через `PG_*` ENV-переменные, Actuator health, Micrometer metrics + Observation, аккуратная работа с ротацией пароля и Patroni switchover.

Артефакт совместим с JPA, JdbcTemplate и jOOQ; включается без изменений в коде потребителя при наличии `@Transactional(readOnly = true)` или аннотации [`@UseReadReplica`](src/main/java/space/br1440/platform/datasource/annotation/UseReadReplica.java).

---

## Контракт активации

Стартер активируется через переменную окружения:

| `PG_TOPOLOGY` | Поведение |
|---|---|
| `DIRECT` (case-insensitive) | Стартер активен. Поднимаются два HikariCP-пула на multi-host JDBC URL pgJDBC. |
| `HAPROXY` или не задано | Стартер не создаёт ни одного бина. Микросервис идёт по канонической `DataSourceAutoConfiguration` через `spring.datasource.url=${POSTGRES_URL}`. |

---

## Подключение

```groovy
dependencies {
    api platform("space.br1440.platform:spring-boot-starter-platform-bom:<version>")
    implementation 'space.br1440.platform:spring-boot-starter-platform-postgres-read-write-split-jdbc'
}
```

Версия не указывается — она задаётся через платформенный BOM.

---

## Конфигурация

Полный канонический префикс — `platform.datasource.postgres-cluster.*`. Дефолты для всех полей инжектируются из `PG_*` ENV-переменных классом [`PostgresClusterEnvironmentPostProcessor`](src/main/java/space/br1440/platform/datasource/configuration/environment/PostgresClusterEnvironmentPostProcessor.java) с самым низким приоритетом, поэтому в типовом сценарии разработчик не пишет ни одного `platform.datasource.*` ключа в собственном `application.yml`.

### ENV-переменные

| ENV | Свойство | Назначение |
|---|---|---|
| `PG_CLUSTER_NODES` | `nodes` (CSV → `List<PgNode>`) | Узлы кластера в формате `host:port`. |
| `PG_DATABASE` | `database` | Имя базы. |
| `PG_SCHEMA` | `jdbc.properties.currentSchema` | Схема базы данных. Если не задана — PostgreSQL сохраняет серверный `search_path`. Подробнее: [PostgreSQL Schema](#postgresql-schema). |
| `PG_USERNAME` | `credentials.username` | Имя пользователя. Допускает пусто (passwordless / trust). |
| `PG_PASSWORD` | `credentials.password` | Пароль. Допускает пусто (passwordless / trust). |
| `PG_CONNECTION_PROPERTIES` | `jdbc.properties.*` (`k=v;k=v`) | Доп. параметры драйвера (`applicationName`, `sslmode`, `connectTimeout`, …). EPP отбрасывает `user`/`password`. |

### Приоритет источников (от низшего к высшему)

1. EPP `MapPropertySource` — `pg-cluster-env` (самый конец `propertySources`): `PG_*` ENV-переменные.
2. Packaged `application.yml` (classpath, в составе JAR).
3. External `application.yml` (`spring.config.location`).
4. `@TestPropertySource` / `@DynamicPropertySource` / `withPropertyValues` в тестах.
5. OS environment variables (`PLATFORM_DATASOURCE_POSTGRES_CLUSTER_*`).
6. System properties (`-D...`), command-line args.

> Уровни 2–6 перекрывают уровень 1. Для переопределения `currentSchema` достаточно добавить
> `platform.datasource.postgres-cluster.jdbc.properties.currentSchema=<schema>` в `application.yml`.

### Ключевые свойства

| Свойство | Значение по умолчанию | Описание |
|---|---|---|
| `platform.datasource.postgres-cluster.nodes` | `PG_CLUSTER_NODES` | Список нод кластера. |
| `platform.datasource.postgres-cluster.database` | `PG_DATABASE` | Имя БД. |
| `platform.datasource.postgres-cluster.credentials.username` / `password` | `PG_USERNAME` / `PG_PASSWORD` | Учётные данные. |
| `platform.datasource.postgres-cluster.connection-properties.*` | `PG_CONNECTION_PROPERTIES` | Параметры драйвера, передаются в JDBC URL. |
| `platform.datasource.postgres-cluster.read-replica.strategy` | `LOAD_BALANCED` | Стратегия read-пула, см. ниже. |
| `platform.datasource.postgres-cluster.hikari.write.*` | см. [`HikariRoutingProperties`](src/main/java/space/br1440/platform/datasource/config/HikariRoutingProperties.java) | HikariCP write-пула. |
| `platform.datasource.postgres-cluster.hikari.read.*` | см. `HikariRoutingProperties` | HikariCP read-пула. `readOnly=true` устанавливается принудительно. |

---

## PostgreSQL Schema

Стартер поддерживает задание схемы через переменную окружения `PG_SCHEMA`. Значение транслируется
в параметр `currentSchema` JDBC URL, что устанавливает `search_path` для каждого соединения на
уровне драйвера pgJDBC.

### Поведение по умолчанию

Если `PG_SCHEMA` **не задана** — стартер **не добавляет** `currentSchema` в JDBC URL. PostgreSQL
сохраняет серверный `search_path` (`"$user", public`). Это non-breaking default.

### Задание схемы

```bash
# Kubernetes Pod env / docker-compose
PG_SCHEMA=my_app_schema
```

Или через `application.yml` (приоритет 2+, перекрывает `PG_SCHEMA`):

```yaml
platform.datasource.postgres-cluster:
  jdbc:
    properties:
      currentSchema: my_app_schema
```

### Приоритет `currentSchema`

Если `currentSchema` задан несколькими способами одновременно — выигрывает источник с
наибольшим приоритетом (см. [Приоритет источников](#приоритет-источников-от-низшего-к-высшему)):
`application.yml` > `PG_SCHEMA` via EPP.

### Предупреждение: `PG_SCHEMA` и `currentSchema` в `PG_CONNECTION_PROPERTIES`

Если `currentSchema` задан одновременно через `PG_SCHEMA` **и** через `PG_CONNECTION_PROPERTIES`
(например `currentSchema=foo`), стартер логирует `WARN` и использует значение `PG_SCHEMA` в
canonical EPP map. Чтобы устранить предупреждение — используйте только один из двух источников.

### Предупреждение: `PG_SCHEMA=public`

> **Не рекомендуется** задавать `PG_SCHEMA=public` явно, если ваше приложение полагается
> на `$user`-схему или `pg_temp` в `search_path`.
>
> При `currentSchema=public` pgJDBC устанавливает `SET search_path = public`, что **убирает
> `"$user"` и `pg_temp`** из пути поиска — это отличается от серверного дефолта
> `"$user", public`. Если конкретная схема не нужна, просто не задавайте `PG_SCHEMA`.

### Миграционное руководство

Если вы ранее задавали схему через `PG_CONNECTION_PROPERTIES=currentSchema=my_schema`:

1. Перейдите на `PG_SCHEMA=my_schema` (рекомендуется) **или** оставьте `PG_CONNECTION_PROPERTIES` без изменений.
2. Не задавайте оба источника одновременно — появится `WARN` в логах при старте.

---

## Стратегии read-пула

`platform.datasource.postgres-cluster.read-replica.strategy`:

| Значение | targetServerType | loadBalanceHosts | Назначение |
|---|---|---|---|
| `LOAD_BALANCED` *(default)* | `any` | `true` | Read-heavy сервисы. Чтение размазано по всем нодам, **включая Primary**. |
| `SECONDARY_ONLY` | `preferSecondary` | `false` | Write-heavy сервисы. Чтение прижато к Standby; драйвер допускает fallback на Primary, если реплика недоступна. |

Write-пул всегда `targetServerType=primary` — стратегия не влияет на путь записи.

---

## Маршрутизация и API потребителя

Решение о том, какой пул использовать, принимается в [`RoutingDataSource.resolveKey()`](src/main/java/space/br1440/platform/datasource/postgres1/RoutingDataSource.java):

1. Явный override через `DataSourceContextHolder` (устанавливается аннотацией `@UseReadReplica` или [`RoutingDSLContextFactory`](src/main/java/space/br1440/platform/datasource/jooq/RoutingDSLContextFactory.java)).
2. `@Transactional(readOnly = true)` (`TransactionSynchronizationManager.isCurrentTransactionReadOnly()`).
3. Иначе — write-пул.

### Транзакционные чтения (предпочтительный путь)

```java
@Transactional(readOnly = true)
public List<Order> findRecent() { ... }
```

### Нетранзакционные чтения

```java
@UseReadReplica
public Stats sumOpen() { ... }
```

Аспект — [`NonTransactionalRoutingAspect`](src/main/java/space/br1440/platform/datasource/postgres1/NonTransactionalRoutingAspect.java), `Order = HIGHEST_PRECEDENCE`. **Комбинирование `@UseReadReplica` с `@Transactional` запрещено**: стартап падает с `IllegalStateException` через `RoutingAnnotationConflictValidator`. Для read-only транзакций — `@Transactional(readOnly = true)`.

### jOOQ вне Spring-транзакций

```java
List<Order> orders = dslFactory.read(dsl ->
    dsl.selectFrom(ORDERS).where(ORDERS.STATUS.eq("NEW")).fetchInto(Order.class));

dslFactory.writeVoid(dsl ->
    dsl.update(ORDERS).set(ORDERS.STATUS, "DONE").where(ORDERS.ID.eq(id)).execute());
```

`RoutingDSLContextFactory` гарантирует `clear()` контекста через `try/finally`.

---

## Архитектура бинов

```
              ┌────────────────────────────────────────┐
              │ @Primary DataSource                    │
              │  PasswordExpiryRetryDataSourceProxy    │ ← retry on SQLSTATE 28P01 + soft-evict пулов
              └────────────────────────────────────────┘
                              │
              ┌────────────────────────────────────────┐
              │ LazyConnectionDataSourceProxy          │ ← откладывает getConnection() до первого SQL
              └────────────────────────────────────────┘
                              │
              ┌────────────────────────────────────────┐
              │ RoutingDataSource                      │ ← AbstractRoutingDataSource
              │  resolveKey(): @UseReadReplica /       │   + Observation + metrics + MDC
              │   readOnly tx / WRITE                  │
              └─────────────┬────────────┬─────────────┘
                            │            │
            ┌──────────── WRITE ─┐  ┌─ READ ──────────────┐
            │  HikariDataSource  │  │  HikariDataSource    │
            │  postgres-cluster- │  │  postgres-cluster-   │
            │  write-pool        │  │  read-pool (RO)      │
            │  targetServerType= │  │  per-strategy URL    │
            │  primary           │  │                      │
            └────────────────────┘  └──────────────────────┘
```

Состав корневой авто-конфигурации — [`JdbcCqrsRoutingAutoConfiguration`](src/main/java/space/br1440/platform/datasource/config/JdbcCqrsRoutingAutoConfiguration.java); подключаемые подконфигурации: `JooqRoutingConfiguration`, `MetricsRoutingConfiguration`, `HealthRoutingConfiguration`, `ObservationRoutingConfiguration`.

Все ключевые бины маркированы `@ConditionalOnMissingBean` — потребитель может переопределить любой узел: `writeDataSource`, `readDataSource`, `RoutingDataSource`, `DataSourceRoutingLifecycle`, `NonTransactionalRoutingAspect`.

---

## Эксплуатация и наблюдаемость

### Health (Actuator)

[`DataSourceRoutingHealthIndicator`](src/main/java/space/br1440/platform/datasource/postgres/internal/integration/health/DataSourceRoutingHealthIndicator.java) регистрируется как `routingHealthIndicator` → ключ Actuator `routing` → путь `/actuator/health/routing`.

Для каждого пула выполняется `SELECT pg_is_in_recovery()` с `Statement.setQueryTimeout` (настраивается отдельно от response deadline). Single-flight coordinator ограничивает одновременные JDBC-probe **двумя** задачами (write + read); response может завершиться по `health.timeout`, тогда as-yet-running JDBC tasks дренируются через query timeout и pgJDBC/Hikari timeouts.

```yaml
platform:
  datasource:
    postgres-cluster:
      health:
        timeout: 5s          # response latency (Actuator caller wait)
        query-timeout: 3s    # SQL execution after connection acquired
```

**Timeout budget (операционный контракт):**

| Фаза | Ограничение |
|---|---|
| Response latency | `platform.datasource.postgres-cluster.health.timeout` |
| SQL execution | `health.query-timeout` → `Statement.setQueryTimeout` |
| Connection acquisition | Hikari `connectionTimeout` (не меняется стартером) |
| Network read | pgJDBC `socketTimeout` (не меняется стартером) |

Риск: `socketTimeout=0` у pgJDBC — JDBC task может дренироваться неограниченно; single-flight ограничивает **число** задач (≤2), но не гарантирует их завершение.

```json
{
  "status": "UP",
  "details": {
    "readMode": "SECONDARY_PREFERRED",
    "write": { "status": "UP", "pgRole": "primary" },
    "read":  { "status": "UP", "pgRole": "standby" }
  }
}
```

При failure компонент содержит `failureType` (без raw exception message). Агрегат: `UP` / `DEGRADED` / `DOWN` по `ReadMode`.

**Readiness (нормативный operational endpoint):**

```yaml
management:
  endpoint:
    health:
      group:
        readiness:
          include: readinessState,routing
          status:
            order: down,out-of-service,degraded,unknown,up
            http-mapping:
              down: 503
              out-of-service: 503
              degraded: 503
```

`routing` **не** включается в liveness. Root `/actuator/health` **не гарантирует** сохранение `DEGRADED` без явного global opt-in (`@Import(DegradedStatusConfiguration)` или собственные beans).

Global `StatusAggregator` / `HttpCodeStatusMapper` стартером **не** регистрируются.

### Метрики (Micrometer)

| Имя | Тип | Теги | Назначение |
|---|---|---|---|
| `datasource.routing.requests` | Counter | `target=write|read` | Учёт фактических вызовов `resolveKey()` на пул. |
| `datasource.routing.read.strategy` | Gauge | `strategy=LOAD_BALANCED|SECONDARY_ONLY` | Активная стратегия read-пула. |

Имена пулов — `postgres-cluster-write-pool`, `postgres-cluster-read-pool`. Стандартные метрики HikariCP (`hikaricp.*`) идут по этим именам.

### Observation (Micrometer Observation)

`Observation.name = datasource.routing`. Low-cardinality теги (см. [`RoutingObservationConvention.Default`](src/main/java/space/br1440/platform/datasource/observation/RoutingObservationConvention.java)):

- `db.system = postgresql`;
- `db.routing.target = write|read`;
- `db.routing.pool = <hikari pool name>`.

Переопределение — собственный бин `RoutingObservationConvention`.

### MDC

На время `getConnection()` `RoutingDataSource` выставляет:
- `db.target` — `WRITE` / `READ`;
- `db.pool` — имя HikariCP-пула.

После выдачи Connection ключи снимаются.

### Patroni switchover

- Write-пул использует `connectionTestQuery` с `pg_is_in_recovery()` (см. дефолт в [`PoolDefaults`](src/main/java/space/br1440/platform/datasource/config/support/PoolDefaults.java)) и `keepaliveTime=60_000` — stale-соединения вытесняются HikariCP без ожидания запроса от приложения.
- Read-пул сохраняет `readOnly=true` независимо от стратегии — DML/DDL не уйдут на read-сторону.

### Ротация пароля БД

[`PasswordExpiryRetryDataSourceProxy`](src/main/java/space/br1440/platform/datasource/postgres1/PasswordExpiryRetryDataSourceProxy.java) перехватывает `SQLSTATE 28P01`, дёргает callback с `softEvictConnections()` для обоих пулов и однократно повторяет `getConnection()`.
