# Библиотека Platform Coordination

Java-библиотека для распределенной координации и выбора лидера в Spring Boot приложениях с использованием Redis в качестве бэкенда.

Данная библиотека предоставляет возможности распределенной блокировки, выбора лидера и координации для Spring Boot приложений, работающих в кластерных окружениях. 
Она гарантирует, что запланированные задачи и критически важные операции выполняются только одним экземпляром приложения за раз, 
предотвращая повреждение данных и конфликты ресурсов в развертываниях с несколькими репликами.

## Архитектура

Библиотека построена на основе Redis и предоставляет следующие основные компоненты:

### Основные модули
- **Lock Management** - Управление распределенными блокировками через Redis
- **Leader Election** - Алгоритм выбора лидера с использованием Redis Lease API
- **Scheduler Locking** - Интеграция с Spring Scheduler для распределенного выполнения задач
- **Event System** - Система событий для уведомления о смене лидера
- **Metrics & Monitoring** - Интеграция с Micrometer для мониторинга
- **Resilience** - Circuit Breaker и Retry механизмы для надежности

## Ключевые особенности
- **Redis-based**: Использует Redis как надежный бэкенд для координации
- **Spring Boot Integration**: Бесшовная интеграция с Spring Framework
- **Аннотации**: Простой декларативный подход с использованием аннотаций
- **Production-ready**: Проверено в enterprise окружениях
- **Resilience**: Встроенные механизмы Circuit Breaker и Retry
- **Автоматический failover**: Встроенные механизмы выбора лидера и переключения
- **Гибкая конфигурация**: Комплексные опции конфигурации для различных сценариев
- **Metrics Integration**: Интеграция с Micrometer для мониторинга

## Интеграция с библиотекой

### Gradle зависимость

```gradle
dependencies {
    implementation 'space.br1440.platform:coordination:1.0.0'
}
```

### Базовая конфигурация

Добавьте следующую конфигурацию в ваш `application.yml`:

```yaml
platform:
  coordination:
    enabled: true
    redis:
      host: localhost
      port: 6379
      database: 0
      namespace: "my-app"
    leadership:
      lease-duration: PT15S
      renew-deadline: PT10S
      retry-period: PT2S
    lock:
      default-lock-at-most-for: PT30M
      default-lock-at-least-for: PT1M
```

### Простой пример использования

```java
@Component
public class DataSyncService {

    @Scheduled(cron = "0 0 2 * * ?")
    @SchedulerLock(name = "data-sync", lockAtMostFor = "PT30M")
    public void performDataSync() {
        // Этот метод будет выполняться только одним экземпляром в кластере
        syncDataFromExternalSystem();
    }
}
```

## Основные аннотации

### @SchedulerLock

Основная аннотация для распределенной блокировки запланированных методов.

```java
@SchedulerLock(
    name = "уникальный-идентификатор-задачи",
    lockAtMostFor = "PT30M",
    lockAtLeastFor = "PT1M"
)
```

**Параметры:**
- `name`: Уникальный идентификатор для распределенной блокировки
- `lockAtMostFor`: Максимальное время удержания блокировки (формат ISO-8601)
- `lockAtLeastFor`: Минимальное время удержания блокировки (опционально)

### @DistributedLock

Для применения распределенной блокировки к произвольным методам, не ограничиваясь запланированными задачами.

```java
@DistributedLock(
    name = "data-export",
    lockAtMostFor = "PT15M"
)
public void exportData() {
    // Метод защищен распределенной блокировкой
}
```

### @LeaderAware

Аннотация для инжекции информации о лидерстве в поля и методы.

```java
@Component
public class LeaderAwareService {
    
    @LeaderAware
    private Boolean isLeader;
    
    @LeaderAware
    private LeaderInfo leaderInfo;
    
    @LeaderAware
    public void onLeadershipChange(boolean isLeader) {
        if (isLeader) {
            // Логика при получении лидерства
        }
    }
}
```

### Hook аннотации

#### @OnLockAcquired

Выполнение методов при получении распределенной блокировки.

```java
@OnLockAcquired(lockName = "data-sync")
public void onSyncStarted() {
    // Логика подготовки перед выполнением основной задачи
    initializeResources();
}
```

#### @OnLockReleased

Выполнение методов при освобождении распределенной блокировки.

```java
@OnLockReleased(lockName = "data-sync")
public void onSyncCompleted() {
    // Логика очистки после завершения основной задачи
    cleanupResources();
}
```

## Свойства конфигурации

### Базовая конфигурация

```yaml
platform:
  coordination:
    enabled: true                    # Включить/отключить библиотеку
    redis:
      host: localhost                # Redis хост
      port: 6379                     # Redis порт
      database: 0                    # Redis база данных
      namespace: "my-app"            # Namespace для ключей
    leadership:
      lease-duration: PT15S          # Длительность lease
      renew-deadline: PT10S          # Дедлайн обновления lease
      retry-period: PT2S             # Период повтора операций lease
    lock:
      default-lock-at-most-for: PT30M  # Максимальная длительность блокировки по умолчанию
      default-lock-at-least-for: PT1M  # Минимальная длительность блокировки по умолчанию
      expire-after: PT1M             # Время истечения блокировки
      idle-between-tries: PT50MS     # Пауза между попытками получения блокировки
```

### Расширенная конфигурация

```yaml
platform:
  coordination:
    redis:
      timeout: PT5S                  # Таймаут подключения к Redis
      connect-timeout: PT10S         # Таймаут установки соединения
      pool:
        max-active: 8                # Максимальное количество активных соединений
        max-idle: 8                  # Максимальное количество неактивных соединений
        min-idle: 0                  # Минимальное количество неактивных соединений
    resilience:
      circuit-breaker:
        enabled: true                # Включить Circuit Breaker
        failure-rate-threshold: 50   # Порог ошибок для открытия Circuit Breaker
        wait-duration-in-open-state: PT60S  # Время ожидания в открытом состоянии
        sliding-window-size: 10      # Размер скользящего окна
      retry:
        enabled: true                # Включить Retry
        max-attempts: 3              # Максимальное количество попыток
        wait-duration: PT1S          # Время ожидания между попытками
```

## Расширенные примеры использования

### Сложная запланированная задача

```java
@Component
public class ReportGenerationService {

    @Scheduled(cron = "0 0 1 * * ?")
    @SchedulerLock(
        name = "monthly-reports",
        lockAtMostFor = "PT2H",
        lockAtLeastFor = "PT5M"
    )
    public void generateMonthlyReports() {
        generateFinancialReports();
        generateOperationalReports();
        sendReportsToStakeholders();
    }

    @OnLockAcquired(lockName = "monthly-reports")
    public void prepareReportGeneration() {
        log.info("Начинаем генерацию месячных отчетов...");
        initializeReportingSystem();
    }

    @OnLockReleased(lockName = "monthly-reports")
    public void finalizeReportGeneration() {
        log.info("Генерация месячных отчетов завершена");
        cleanupTemporaryFiles();
    }
}
```

### REST API с распределенной блокировкой

```java
@RestController
@RequestMapping("/api/maintenance")
public class MaintenanceController {

    @PostMapping("/full-sync")
    @DistributedLock(
        name = "full-system-sync",
        lockAtMostFor = "PT1H"
    )
    public ResponseEntity<String> triggerFullSync() {
        performFullSystemSync();
        return ResponseEntity.ok("Полная синхронизация успешно завершена");
    }

    @PostMapping("/cache-refresh")
    @DistributedLock(
        name = "cache-refresh",
        lockAtMostFor = "PT10M"
    )
    public ResponseEntity<String> refreshCache() {
        refreshApplicationCache();
        return ResponseEntity.ok("Обновление кеша завершено");
    }
}
```

### Leader-Aware компоненты

```java
@Component
public class LeaderAwareDataProcessor {
    
    @LeaderAware
    private Boolean isLeader;
    
    @LeaderAware
    private LeaderInfo leaderInfo;
    
    @Scheduled(fixedRate = 30000)
    public void processData() {
        if (Boolean.TRUE.equals(isLeader)) {
            // Выполняем обработку только если мы лидер
            performDataProcessing();
        }
    }
    
    @LeaderAware
    public void onLeadershipChange(boolean isLeader) {
        if (isLeader) {
            log.info("Стали лидером, инициализируем ресурсы");
            initializeLeaderResources();
        } else {
            log.info("Потеряли лидерство, очищаем ресурсы");
            cleanupLeaderResources();
        }
    }
}
```

## Конфигурация Redis

### Локальная разработка

```yaml
platform:
  coordination:
    redis:
      host: localhost
      port: 6379
      database: 0
```

### Docker Compose

```yaml
version: '3.8'
services:
  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
    command: redis-server --appendonly yes
    volumes:
      - redis_data:/data

volumes:
  redis_data:
```

### Kubernetes

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: redis-config
data:
  redis.conf: |
    appendonly yes
    maxmemory 256mb
    maxmemory-policy allkeys-lru

---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: redis
spec:
  replicas: 1
  selector:
    matchLabels:
      app: redis
  template:
    metadata:
      labels:
        app: redis
    spec:
      containers:
      - name: redis
        image: redis:7-alpine
        ports:
        - containerPort: 6379
        volumeMounts:
        - name: redis-config
          mountPath: /usr/local/etc/redis/redis.conf
        command: ["redis-server", "/usr/local/etc/redis/redis.conf"]
      volumes:
      - name: redis-config
        configMap:
          name: redis-config
```

## Мониторинг и метрики

### Интеграция с Micrometer

```java
@Component
public class MonitoredProcessor {

    private final MeterRegistry meterRegistry;
    private final Counter successCounter;
    private final Timer executionTimer;

    public MonitoredProcessor(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.successCounter = Counter.builder("coordination.task.success")
                .tag("task", "data-sync")
                .register(meterRegistry);
        this.executionTimer = Timer.builder("coordination.task.duration")
                .tag("task", "data-sync")
                .register(meterRegistry);
    }

    @SchedulerLock(name = "monitored-data-sync", lockAtMostFor = "PT30M")
    public void syncData() {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            performDataSync();
            successCounter.increment();
        } finally {
            sample.stop(executionTimer);
        }
    }
}
```

### Доступные метрики

- `coordination.leadership.granted` - Количество получений лидерства
- `coordination.leadership.revoked` - Количество потерь лидерства
- `coordination.leadership.transitions` - Количество переходов лидерства
- `coordination.lock.acquired` - Количество получений блокировок
- `coordination.lock.released` - Количество освобождений блокировок
- `coordination.lock.failed` - Количество неудачных попыток получения блокировок

## Тестирование

### Unit тестирование

```java
@ExtendWith(MockitoExtension.class)
class DataSyncServiceTest {

    @Mock
    private UserRepository userRepository;
    
    @Mock
    private ExternalApiService externalApiService;
    
    @InjectMocks
    private DataSyncService dataSyncService;

    @Test
    void shouldSyncUsersSuccessfully() {
        // Given
        List<User> users = Arrays.asList(
            new User("user1", "test1@example.com"),
            new User("user2", "test2@example.com")
        );
        when(userRepository.findModifiedAfter(any())).thenReturn(users);

        // When
        dataSyncService.syncUserData();

        // Then
        verify(externalApiService, times(2)).upsertUser(any(User.class));
        verify(userRepository).updateLastSyncTime(any(Instant.class));
    }
}
```

### Integration тестирование с Redis

```java
@SpringBootTest
@WithEmbeddedRedis
class DataSyncServiceIntegrationTest {

    @Autowired
    private DataSyncService dataSyncService;
    
    @Autowired
    private RedisTestSupport redisTestSupport;

    @Test
    void shouldSyncDataWithDistributedLock() {
        // Given
        redisTestSupport.flushAll();
        
        // When
        dataSyncService.syncUserData();
        
        // Then
        // Проверяем, что данные синхронизированы
        assertThat(redisTestSupport.get("last-sync-time")).isNotNull();
    }
}
```

## Лучшие практики

### Рекомендации по длительности блокировки

- **Короткие задачи (< 5 минут)**: Используйте `lockAtMostFor = "PT5M"`
- **Средние задачи (5-30 минут)**: Используйте `lockAtMostFor = "PT30M"`
- **Длинные задачи (> 30 минут)**: Используйте `lockAtMostFor = "PT1H"` или больше

**Важно**: Всегда устанавливайте `lockAtMostFor` с запасом времени на 20-30% больше ожидаемого времени выполнения.

### Именование Lease Role

Используйте описательные, иерархические соглашения об именовании:

```java
// Хорошие примеры
@SchedulerLock(name = "data-sync-customers")
@SchedulerLock(name = "report-generation-monthly")
@SchedulerLock(name = "cache-refresh-products")

// Избегайте общих имен
@SchedulerLock(name = "task")
@SchedulerLock(name = "job")
```

### Обработка ошибок

```java
@Component
public class RobustProcessor {

    @Scheduled(cron = "0 */15 * * * ?")
    @SchedulerLock(name = "data-processing", lockAtMostFor = "PT10M")
    public void processData() {
        try {
            performDataProcessing();
        } catch (Exception e) {
            log.error("Обработка данных завершилась с ошибкой", e);
            // Обработайте ошибку соответствующим образом
            notifyAdministrators(e);
            // Не перебрасывайте исключение, чтобы не блокировать следующие выполнения
        }
    }
}
```

## Устранение неполадок

### Типичные проблемы

**Проблема**: Задачи не выполняются ни в одном экземпляре
**Решение**: Проверьте подключение к Redis и конфигурацию namespace

**Проблема**: Несколько экземпляров выполняют одну и ту же задачу
**Решение**: Проверьте конфигурацию lease и убедитесь в уникальности значений `name`

**Проблема**: Задачи зависают бесконечно
**Решение**: Пересмотрите значения `lockAtMostFor` и реализуйте правильную обработку ошибок

### Конфигурация отладки

Включите отладочное логирование для устранения неполадок:

```yaml
logging:
  level:
    space.br1440.platform.coordination: DEBUG
```

## Системные требования

- Java 21 или выше
- Spring Boot 3.5 или выше
- Redis 6.0 или выше
- Micrometer (опционально, для метрик)

## Конфигурация Gradle

### Базовая конфигурация build.gradle

```gradle
plugins {
    id 'org.springframework.boot' version '3.5.6'
    id 'io.spring.dependency-management' version '1.1.7'
    id 'java'
}

group = 'com.example'
version = '1.0.0'
sourceCompatibility = '21'

repositories {
    mavenCentral()
}

dependencies {
    implementation 'org.springframework.boot:spring-boot-starter'
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'space.br1440.platform:coordination:1.0.0'
    
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.testcontainers:testcontainers'
    testImplementation 'org.testcontainers:junit-jupiter'
}

test {
    useJUnitPlatform()
}
```
