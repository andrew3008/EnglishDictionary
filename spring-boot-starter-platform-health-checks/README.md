# Spring Boot Platform Health Checks Starter

### Основные возможности
- **PostgreSQL Health Checks** - проверка подключения, пула соединений и производительности
- **MinIO Health Checks** - проверка buckets и операций
- **Kafka Health Checks** - проверка producer, consumer, topics и кластера
- **Бизнес Health Checks** - создание кастомных health checks с поддержкой критичности


### 1. Добавление зависимости
Библиотека с Health Checks реализована в виде Spring Boot Starter, который может быть добавлен через Gradle DSL:
```
implementation("space.br1440.platform:spring-boot-starter-platform-health-checks:${platformVersion}")
```
При добавлении зависимости автоматически активируются:
1. **Auto-configuration beans** — `@Configuration` классы регистрируются через `AutoConfiguration.imports` (Spring Boot 3.0+ mechanism)
2. **Conditional activation** — компоненты активируются только при наличии required dependencies на classpath (`@ConditionalOnClass`)
3. **Property-based control** — все функции управляются через `application.yml` без code changes
4. **Transitive dependencies** — все необходимые библиотеки (Micrometer, Caffeine, клиенты для MinIO/Kafka) подтягиваются автоматически


#### Auto-configuration contract

Стартер использует Spring Boot 3.0+ механизм регистрации через `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. Это обеспечивает:
- **Explicit imports** — четкий контракт регистрации конфигураций без reflection overhead
- **Faster startup** — optimized class loading и reduced classpath scanning
- **Better IDE support** — автокомплит и navigation в современных IDE

**Зарегистрированные auto-configurations:**
```
space.br1440.platform.healthchecks.configuration.PostgresHealthAutoConfiguration  # @ConditionalOnClass(DataSource.class)
space.br1440.platform.healthchecks.configuration.MinioHealthAutoConfiguration     # @ConditionalOnClass(MinioClient.class)
space.br1440.platform.healthchecks.configuration.KafkaHealthAutoConfiguration     # @ConditionalOnClass(KafkaProducer.class)
space.br1440.platform.healthchecks.configuration.BusinessHealthAutoConfiguration  # всегда активна
```

**Условия активации (conditional logic):**

| Auto-configuration | Требуется на classpath | Property switch | Default |
|-------------------|------------------------|----------------|---------|
| PostgresHealthAutoConfiguration | `spring-boot-starter-jdbc` + PostgreSQL driver | `platform.health.postgres.enabled` | `true` |
| MinioHealthAutoConfiguration | `minio` client library | `platform.health.minio.enabled` | `true` |
| KafkaHealthAutoConfiguration | `spring-kafka` | `platform.health.kafka.enabled` | `true` |
| BusinessHealthAutoConfiguration | Spring Boot Actuator | `platform.health.business.enabled` | `true` |

**Graceful degradation:** Если требуемая зависимость отсутствует на classpath, соответствующая auto-configuration просто не активируется (без ошибок компиляции или runtime exceptions).


### 2. Production-ready конфигурация Actuator endpoints
Доступ к API описываемых Health Checks предоставляется через health endpoints из Spring Boot Actuator через `management.*` namespace. 
Конфигурация должна учитывать:
1. **Security posture** — endpoints содержат sensitive data (connection strings, topology, версии)
2. **Performance impact** — необоснованная экспозиция endpoints увеличивает attack surface
3. **Compliance** — регуляторные требования к disclosure информации (GDPR, PCI DSS)
4. **Operational access** — разделение доступа для операторов, мониторинга и external probes

**Пример конфигурации для Spring Boot Actuator'а:**
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,metrics       # Минимальная экспозиция — только health и metrics (принцип least privilege)
  endpoint:
    health:
      show-details: when-authorized   # Details только для authenticated users с указанными roles (защита от info disclosure)
      # show-details: always          # НИКОГДА в production — раскрывает топологию, версии, credentials patterns
      # show-details: never           # Для публичных endpoints (load balancers) — только UP/DOWN без details
      roles: ADMIN,ACTUATOR           # RBAC: разделение ролей для операторов и мониторинга
```


**Endpoint routing и назначение:**

| Endpoint | Consumer | Назначение | Security Level |
|----------|----------|-----------|----------------|
| `GET /actuator/health` | Load balancers, K8s probes | Агрегированный статус всех компонентов | Public (без details) |
| `GET /actuator/health/liveness` | Kubernetes liveness probe | Проверка deadlocks, memory leaks | Public (restricted path) |
| `GET /actuator/health/readiness` | Kubernetes readiness probe | Готовность принимать трафик | Public (restricted path) |
| `GET /actuator/health/postgres` | Ops dashboard, troubleshooting | Детальная диагностика PostgreSQL | Authorized (ADMIN) |
| `GET /actuator/health/minio` | Ops dashboard, troubleshooting | Детальная диагностика MinIO | Authorized (ADMIN) |
| `GET /actuator/health/kafka` | Ops dashboard, troubleshooting | Детальная диагностика Kafka | Authorized (ADMIN) |
| `GET /actuator/metrics` | Prometheus scraper, monitoring | Time-series метрики | Public (scrape endpoint) |

**Пример ответа `/actuator/health`:**
```json
{
  "status": "UP",
  "components": {
    "postgres": {
      "status": "UP",
      "details": {
        "database": "PostgreSQL",
        "validationQuery": "SELECT 1"
      }
    },
    "minio": {
      "status": "UP",
      "details": {
        "service": "MinIO",
        "bucketsCount": 5
      }
    },
    "kafka": {
      "status": "UP",
      "details": {
        "clusterId": "kafka-cluster-1",
        "brokersCount": 3
      }
    }
  }
}
```

#### Операционная диагностика через HTTP API

```bash
# Получить общий health status
curl http://localhost:8080/actuator/health

# Получить детальную информацию
curl http://localhost:8080/actuator/health?show-details=always

# Получить метрики
curl http://localhost:8080/actuator/metrics/business.health.check.duration
```

#### Kubernetes Probe Configuration

Health endpoints интегрируются с Kubernetes probe механизмами для автоматического lifecycle management pods. Kubernetes использует три типа probes для контроля состояния контейнеров:

**Probe types:**
- **Startup probe** — однократная проверка готовности к liveness/readiness (для медленного старта)
- **Liveness probe** — обнаружение deadlocks → restart pod
- **Readiness probe** — контроль traffic routing → remove from Service endpoints

**Production manifest:**
```yaml
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: app
    ports:
    - containerPort: 8080
      name: http
    
    # Startup probe — защита от преждевременных restarts при медленной инициализации
    startupProbe:
      httpGet:
        path: /actuator/health/startup
        port: 8080
      failureThreshold: 30              # 30 * 10s = 5 минут на startup
      periodSeconds: 10
      
    # Readiness probe — контроль готовности обрабатывать traffic (READINESS severity checks)
    readinessProbe:
      httpGet:
        path: /actuator/health/readiness
        port: 8080
        scheme: HTTP
      initialDelaySeconds: 5
      periodSeconds: 5                  # Более частая проверка для быстрого исключения из LB
      timeoutSeconds: 3
      failureThreshold: 2               # 2 failures → remove from Service endpoints
      successThreshold: 1
    
    # Liveness probe — обнаружение deadlocks, memory leaks (CRITICAL severity checks)
    livenessProbe:
      httpGet:
        path: /actuator/health/liveness
        port: 8080
        scheme: HTTP
      initialDelaySeconds: 30           # Задержка после успешного startup
      periodSeconds: 10                 # Частота проверки
      timeoutSeconds: 5                 # Timeout HTTP request
      failureThreshold: 3               # 3 consecutive failures → restart pod
      successThreshold: 1
```


### 3. Критичность проверок

Все health checks поддерживают три уровня критичности, которые определяют влияние проверки на общий статус приложения:
```java
public enum HealthCheckSeverity {
    CRITICAL,      // Критическая проверка - влияет на общий статус DOWN
    WARNING,       // Предупреждение - не влияет на общий статус
    INFORMATIONAL  // Информационная - только для мониторинга
}
```

**Поведение по уровням:**

| Уровень | Статус при сбое | Влияние на приложение | Kubernetes |
|---------|----------------|----------------------|------------|
| `CRITICAL` | `DOWN` | Приложение считается неработоспособным | Перезапуск pod (liveness) |
| `WARNING` | `UP` | Приложение работает, но с предупреждениями | Алерты, но pod работает |
| `INFORMATIONAL` | `UP` | Только логирование, без алертов | Нет влияния |

**Примеры использования:**
- `CRITICAL`: PostgreSQL недоступен, Kafka не отвечает, основной сервис оплаты не работает
- `WARNING`: Медленные запросы к БД, низкий процент свободных соединений, внешний API партнера недоступен
- `INFORMATIONAL`: Метрики кэша, статистика использования, доступность рекомендательной системы

Три уровня severity (CRITICAL, WARNING, INFORMATIONAL) соответствуют индустриальным стандартам для систем мониторинга и алертинга. 
Подробнее о проектировании уровней severity и best practices можно узнать в статье [How to Build Alert Severity Levels](https://oneuptime.com/blog/post/2026-01-30-alert-severity-levels/view).


### 4. Базовая конфигурация

Минимальная конфигурация для активации всех доступных health checks через `platform.health.*` namespace. 
Каждый модуль включен по умолчанию (`enabled: true`) и автоматически активируется при наличии соответствующих зависимостей на classpath (см. [Auto-configuration contract](#auto-configuration-contract)).

```yaml
platform:
  health:
    postgres:
      enabled: true    # Активируется при наличии spring-boot-starter-jdbc + PostgreSQL driver
    minio:
      enabled: true    # Активируется при наличии minio-client library
    kafka:
      enabled: true    # Активируется при наличии spring-kafka
    business:
      enabled: true    # Всегда активна при наличии Spring Boot Actuator
```
**Graceful degradation**: Если зависимость отсутствует на classpath, соответствующий health check просто не активируется без ошибок или warnings.


## PostgreSQL Health Checks

### Конфигурация

```yaml
platform:
  health:
    postgres:
      enabled: true
      severity: CRITICAL      
      validation-query: "SELECT 1"  # SQL запрос для проверки доступности БД
      
      timeout:
        execution: 3s              # Таймаут выполнения запроса
        max-response: 5s           # Максимальное время ответа
      
      connection-pool:
        enabled: true              # Проверка пула соединений
        min-available-percent: 10  # Минимум доступных соединений (%)
        max-waiting-threads: 5     # Максимум ожидающих потоков 
```

### Проверяемые параметры
- Подключение к базе данных
- Выполнение SQL запросов
- Состояние пула соединений (HikariCP)
- Доступность соединений
- Ожидающие потоки

### Метрики
Автоматически собираются метрики:
- `health.check.duration` - время выполнения проверки
- `health.check.status` - статус (1=UP, 0=DOWN)
- `health.check.pool.active` - активные соединения
- `health.check.pool.idle` - свободные соединения
- `health.check.pool.total` - всего соединений
- `health.check.pool.waiting` - ожидающие потоки


## MinIO Health Checks

### Конфигурация
```yaml
platform:
  health:
    minio:
      enabled: true
      severity: READINESS
      
      connection:
        endpoint: http://localhost:9000
        access-key: minioadmin
        secret-key: minioadmin
        region: us-east-1
      
      timeout:
        execution: 5s
        max-response: 10s
      
      buckets:
        health-check: health-check-bucket   # Базовая проверка подключения к MinIO (опционально) (Доступен ли MinIO сервер)
        to-check:                           # Проверка существования критичных buckets (опционально) (Устанавливает warning, если хотя бы один не существует)
          - bucket1                         # Проверки, что все необходимые для приложения buckets существуют и доступны.
          - bucket2
        test: test-bucket                   # Тестовый bucket для проверки возможности операций чтения/записи (опционально)

      operations:
        check-operations: false              # Включить проверку read/write операций
        test-object-size: 1024               # Размер тестового объекта (bytes)
```

### Проверяемые операции
- Подключение к MinIO серверу
- Существование buckets
- Операции чтения (GET)
- Операции записи (PUT)


## Kafka Health Checks

### Конфигурация

```yaml
platform:
  health:
    kafka:
      enabled: true
      severity: READINESS
      
      connection:
        bootstrap-servers: localhost:9092
      
      timeout:
        execution: 5s
        max-response: 10s
      
      cluster:
        min-brokers: 1                      # Минимум брокеров для работы
      
      producer:
        check: true
      
      consumer:
        check: true

      topics:
        check: false                        # Включить проверку topics (по умолчанию: false)
        health-check:                       # Список топиков для проверки
          - health-check-topic
          - another-topic
        check-replication-factor: false     # Проверять фактор репликации (по умолчанию: false). Проверка гарантирует, что критичные топики имеют достаточную репликацию.
        min-replication-factor: 1           # Минимум реплик (по умолчанию: 1)
```

### Проверяемые компоненты
- **Producer** - возможность отправки сообщений
- **Consumer** - возможность получения сообщений
- **Topics** - существование и конфигурация топиков
- **Cluster** - доступность брокеров
- **Replication** - фактор репликации топиков


## Бизнес Health Checks

**Бизнес Health Checks** позволяют создавать кастомные проверки работоспособности бизнес-логики и внешних сервисов. 
В отличие от инфраструктурных health checks (PostgreSQL, Kafka, MinIO), которые проверяют доступность технических компонентов, бизнес health checks проверяют логическое состояние приложения и его зависимостей.

**Основные возможности:**
- Создание кастомных health checks через наследование `AbstractBusinessHealthIndicator`
- Уровни критичности: `CRITICAL`, `WARNING`, `INFORMATIONAL`
- Встроенное кэширование результатов для снижения нагрузки
- Асинхронное выполнение проверок с настраиваемым thread pool
- Автоматический сбор метрик для мониторинга
- Таймауты и graceful degradation при недоступности сервисов

**Типичные сценарии использования:**
- Проверка доступности внешних API
- Валидация бизнес-правил
- Мониторинг внутренних сервисов (очереди задач, планировщики, воркеры)
- Проверка состояния распределенных систем (наличие лидера, консенсус)

### Конфигурация

```yaml
platform:
  health:
    business:
      enabled: true             # Включить/выключить бизнес health checks
      metrics-enabled: true     # Включить сбор метрик
      
      cache:
        enabled: true           # Включить кэширование
        max-size: 1000          # Максимальный размер кэша
        default-ttl: 30s        # TTL по умолчанию
      
      executor:
        core-pool-size: 2       # Размер core thread pool
        max-pool-size: 10       # Максимальный размер thread pool
        keep-alive-time: 1m     # Время жизни idle потоков
```

#### Thread pool executor: асинхронное выполнение и защита от блокировок

**Архитектурная проблема:** Business health checks могут выполнять долгие операции (HTTP calls к внешним API, сложные DB queries, distributed consensus checks). Синхронное выполнение в HTTP request thread приводит к:
- **Thread pool exhaustion** — при множественных одновременных запросах к `/actuator/health` все HTTP threads блокируются на health checks
- **Cascading timeouts** — если health check зависает, HTTP request timeout (обычно 30-60s) не срабатывает, т.к. thread занят ожиданием
- **Resource contention** — конкуренция за connection pools, network sockets между health checks и бизнес-логикой

**Решение:** Dedicated thread pool (`ThreadPoolTaskExecutor`) для изоляции выполнения health checks от HTTP request threads:
```java
// В AbstractBusinessHealthIndicator.executeWithTimeout()
Future<BusinessHealthStatus> future = executor.submit(this::executeHealthCheck);
return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
```

**Архитектурные гарантии:**
1. **Non-blocking HTTP threads** — health check выполняется в отдельном thread, HTTP request thread освобождается немедленно после submit
2. **Timeout enforcement** — `Future.get(timeout)` гарантирует, что health check не может превысить заданный timeout (даже если операция зависла)
3. **Resource isolation** — thread pool ограничивает максимальное количество одновременных health checks (`max-pool-size`), предотвращая resource exhaustion
4. **Graceful degradation** — при исчерпании thread pool используется `CallerRunsPolicy` (выполнение в caller thread), что предотвращает rejection и обеспечивает fallback

**Настройка thread pool:**
| Параметр | Назначение | Рекомендации |
|----------|-----------|--------------|
| `core-pool-size` | Минимальное количество threads, которые всегда активны | 2-4 threads (базовая нагрузка) |
| `max-pool-size` | Максимум одновременных health checks | 10-20 threads (зависит от количества health checks и их latency) |
| `keep-alive-time` | Время жизни idle threads сверх core pool | 1-5 минут (баланс между resource usage и startup latency) |

**Production considerations:**
- При наличии N бизнес health checks и latency L секунд каждый, `max-pool-size` должен быть ≥ N для параллельного выполнения
- При burst нагрузке на `/actuator/health` (например, при deployment) thread pool должен выдерживать пиковую нагрузку без queue overflow
- Мониторинг thread pool metrics (active threads, queue size) через JMX или Micrometer для capacity planning


### Создание кастомного health check

```java
@Component
public class PaymentServiceHealthIndicator extends AbstractBusinessHealthIndicator {
    
    @Autowired
    private PaymentService paymentService;
    
    @Override
    protected BusinessHealthStatus executeHealthCheck() {
        try {
            boolean isHealthy = paymentService.isAvailable();            
            if (isHealthy) {
                return BusinessHealthStatus.healthy("Payment service is operational")
                    .withDetail("version", paymentService.getVersion())
                    .withDetail("uptime", paymentService.getUptime());
            } else {
                return BusinessHealthStatus.unhealthy("Payment service is unavailable");
            }
        } catch (Exception e) {
            return BusinessHealthStatus.unhealthy("Payment service check failed", e);
        }
    }
    
    @Override
    protected String getHealthCheckName() {
        return "payment-service";
    }
    
    @Override
    protected HealthCheckSeverity getSeverity() {
        return HealthCheckSeverity.CRITICAL; // CRITICAL, WARNING, INFORMATIONAL
    }
    
    @Override
    protected Duration getTimeout() {
        return Duration.ofSeconds(3);
    }
    
    @Override
    protected boolean shouldCache() {
        return true; // Включить кэширование
    }
    
    @Override
    protected Duration getCacheTtl() {
        return Duration.ofSeconds(60); // TTL для кэша
    }
}
```

#### API построения результата проверки

`BusinessHealthStatus` предоставляет fluent API для создания результата health check с дополнительными деталями:

```java
// Успешная проверка — сервис работает нормально
BusinessHealthStatus.healthy("Service is operational")
    .withDetail("key", "value");

// Критическая ошибка — сервис недоступен
BusinessHealthStatus.unhealthy("Service is down")
    .withDetail("error", "Connection timeout");

// Деградация — сервис работает с ограничениями
BusinessHealthStatus.degraded("Service is slow")
    .withDetail("responseTime", "2s");
```

#### Стратегия кэширования и контроль частоты проверок
Health checks выполняются при каждом обращении к `/actuator/health`. В high-load сценариях (load balancer polls каждые 5s, мониторинг каждые 10s, manual requests) это создает:
- **Thundering herd** — множественные одновременные проверки внешних зависимостей
- **Cascading failures** — если зависимость деградирует, health checks усугубляют нагрузку
- **Rate limit violations** — превышение лимитов внешних API (например, AWS API limits)
- **Latency spike** — задержка ответа `/actuator/health` до завершения всех синхронных проверок
- **DDoS vulnerability** — `/actuator/health` становится attack vector: каждый запрос триггерит дорогие операции (DB queries, external API calls), позволяя атакующему с минимальными затратами исчерпать connection pools, thread pools и downstream-ресурсы (amplification attack).
Для избежания этих проблем в SpringBoot Actuator предоставляет встроенный кэш (`management.endpoint.health.cache.time-to-live`), но он работает на уровне **endpoint'а** (кэширует весь ответ `/actuator/health`), а не отдельного check'а.

Для Business HealthChecks был реализован дополнительный внутренний кэш в `AbstractBusinessHealthIndicator`, который решает другие задачи:
- **Пер-индикаторный TTL** — каждый бизнес health check может иметь свой TTL (медленный внешний API — 60-300s, быстрый локальный — 5-10s)
- **Независимость от HTTP-слоя** — работает для любых вызовов (programmatic, группы `/health/readiness`, `/health/liveness`, кастомные endpoint'ы), где Actuator кэш может не применяться
- **Защита внешних зависимостей** — предотвращает thundering herd и rate limit violations при частых проверках дорогих внешних API
- **Гранулярный контроль** — можно отключить кэш для конкретного индикатора, оставив его для других

Для инфраструктурных checks (PostgreSQL, Kafka, MinIO) используется только Actuator TTL. Для бизнес health checks используется внутренний кэш с настраиваемым TTL на уровне индикатора.


## Метрики и мониторинг

### Observability: автоматическая инструментация метрик

Все health checks инструментированы через Micrometer Metrics API с автоматической публикацией в `MeterRegistry`. 
Метрики доступны в `/actuator/metrics` и экспортируются в подключенные backend'ы (Prometheus, Graphite, Datadog и т.д.).

#### PostgreSQL
```
health.check.duration            # Timer - время проверки
health.check.status              # Gauge - статус (1=UP, 0=DOWN)
health.check.pool.active         # Gauge - активные соединения
health.check.pool.idle           # Gauge - свободные соединения
health.check.pool.total          # Gauge - всего соединений
health.check.pool.waiting        # Gauge - ожидающие потоки
```

#### Бизнес Health Checks
```
business.health.check.duration   # Timer - время выполнения
business.health.check.total      # Counter - всего проверок
business.health.check.failures   # Counter - ошибки
business.health.cache.hits       # Counter - попадания в кэш
business.health.cache.misses     # Counter - промахи кэша
business.health.cache.hit.rate   # Gauge - процент попаданий
business.health.cache.size       # Gauge - размер кэша
business.health.aggregations     # Counter - агрегации
```

### Интеграция с Prometheus
```yaml
management:
  metrics:
    export:
      prometheus:
        enabled: true
```

### Интеграция с Grafana

Метрики готовы для визуализации в Grafana. Пример дашборда:
```json
{
  "panels": [
    {
      "title": "Health Check Duration",
      "targets": [
        {
          "expr": "rate(business_health_check_duration_seconds_sum[5m])"
        }
      ]
    }
  ]
}
```


## Решения частых проблем

### Проблема: Health checks не активируются
**Решение:**
1. Проверьте, что `platform.health.*.enabled=true` в конфигурации
2. Убедитесь, что Spring Boot Actuator включен
3. Проверьте логи на наличие ошибок авто-конфигурации

### Проблема: Метрики не собираются
**Решение:**
1. Проверьте `platform.health.business.metrics-enabled=true`
2. Убедитесь, что Micrometer включен
3. Проверьте, что MeterRegistry bean доступен

### Проблема: Кэш не работает
**Решение:**
1. Проверьте `platform.health.business.cache.enabled=true`
2. Убедитесь, что `shouldCache()` возвращает `true`
3. Проверьте логи на ошибки Caffeine
