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
| `PG_CLUSTER_NODES` | `nodes` (CSV → `List<String>`) | Узлы кластера в формате `host:port`. |
| `PG_DATABASE` | `database` | Имя базы. |
| `PG_USERNAME` | `credentials.username` | Имя пользователя. Допускает пусто (passwordless / trust). |
| `PG_PASSWORD` | `credentials.password` | Пароль. Допускает пусто (passwordless / trust). |
| `PG_CONNECTION_PROPERTIES` | `connection-properties` (`k=v;k=v`) | Доп. параметры драйвера (`applicationName`, `sslmode`, `connectTimeout`, …). EPP отбрасывает `user`/`password`. |

### Приоритет источников (от низшего к высшему)

1. `MapPropertySource`, добавленный EPP в самый конец `propertySources` — дефолты из `PG_*`.
2. `application.yml` потребителя.
3. OS environment / system properties (`PLATFORM_DATASOURCE_POSTGRES_CLUSTER_*`).

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

Cхема (PostgreSQL `search_path`) **не управляется стартером** (SRP — стартер отвечает за топологию и пулы, а не за SQL-слой). Задаётся в ORM:
- JPA: `spring.jpa.properties.hibernate.default_schema`;
- JDBC/jOOQ: квалифицированные имена либо `SET search_path` в начале сессии.

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

Решение о том, какой пул использовать, принимается в [`RoutingDataSource.resolveKey()`](src/main/java/space/br1440/platform/datasource/postgres/RoutingDataSource.java):

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

Аспект — [`NonTransactionalRoutingAspect`](src/main/java/space/br1440/platform/datasource/postgres/NonTransactionalRoutingAspect.java), `Order = HIGHEST_PRECEDENCE`. **Комбинирование `@UseReadReplica` с `@Transactional` запрещено**: стартап падает с `IllegalStateException` через `RoutingAnnotationConflictValidator`. Для read-only транзакций — `@Transactional(readOnly = true)`.

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

[`CqrsRoutingHealthIndicator`](src/main/java/space/br1440/platform/datasource/health/CqrsRoutingHealthIndicator.java) регистрируется как `routingHealthIndicator` → ключ Actuator `routing` → путь `/actuator/health/routing`.

Для каждого пула параллельно (на virtual threads, таймаут 5 с) выполняется `SELECT pg_is_in_recovery()`. Один SQL даёт сразу два сигнала: факт доступности и физическую роль ноды (`primary` / `standby`).

```json
{
  "status": "UP",
  "components": {
    "routing": {
      "status": "UP",
      "details": {
        "write": { "status": "UP", "pgRole": "primary" },
        "read":  { "status": "UP", "pgRole": "standby" }
      }
    }
  }
}
```

Агрегат: `UP` (оба пула), `DEGRADED` (один из двух), `DOWN` (оба).

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

[`PasswordExpiryRetryDataSourceProxy`](src/main/java/space/br1440/platform/datasource/postgres/PasswordExpiryRetryDataSourceProxy.java) перехватывает `SQLSTATE 28P01`, дёргает callback с `softEvictConnections()` для обоих пулов и однократно повторяет `getConnection()`.
