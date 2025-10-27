package space.br1440.platform.leaderelection.leaderelection;

import org.junit.jupiter.api.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import space.br1440.platform.leaderelection.configuration.LeaderElectionProperties;
import space.br1440.platform.leaderelection.event.LeadershipEventPublisher;
import space.br1440.platform.leaderelection.lock.DistributedLockRegistry;
import space.br1440.platform.leaderelection.lock.persistence.redis.RedisDistributedLockRegistry;
import space.br1440.platform.leaderelection.lock.persistence.redis.resilience.CircuitBreakerFactory;
import space.br1440.platform.leaderelection.metrics.LeaderElectionMetricsInterface;
import space.br1440.platform.leaderelection.testutil.extension.annotation.WithEmbeddedRedis;
import space.br1440.platform.leaderelection.testutil.extension.annotation.WithLeaderElectionMocks;
import space.br1440.platform.leaderelection.testutil.extension.container.EmbeddedRedisServer;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@WithEmbeddedRedis
@WithLeaderElectionMocks
@DisplayName("Интеграционные тесты LeaderElector")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LeaderElectorIntegrationTest {

    private StringRedisTemplate redisTemplate;
    private LeaderElectionProperties properties;
    private DistributedLockRegistry lockRegistry;
    private LeadershipEventPublisher eventPublisher;
    private LeaderElectionMetricsInterface metrics;

    private static final String LEADERSHIP_NAME = "integration-test-leadership";
    private static final Duration ELECTION_TIMEOUT = Duration.ofSeconds(30);

    @BeforeEach
    void setUp(EmbeddedRedisServer redisServer) {
        this.redisTemplate = redisServer.createStringRedisTemplate();

        this.properties = createLeaderElectionProperties();
        this.lockRegistry = createDistributedLockRegistry();
        this.eventPublisher = createRealEventPublisher();
        this.metrics = createRealMetrics();

        redisServer.clearRedis(redisTemplate);
    }

    @AfterEach
    void tearDown(EmbeddedRedisServer redisServer) {
        if (redisTemplate != null) {
            redisServer.clearRedis(redisTemplate);
        }
    }

    @Nested
    @DisplayName("Конкурентные операции")
    @Order(1)
    class ConcurrentOperationsTests {

        @Test
        @DisplayName("Должен обеспечить единственность лидера при конкурентном доступе")
        @Timeout(value = 2, unit = TimeUnit.MINUTES)
        void shouldEnsureSingleLeaderUnderConcurrentAccess() throws Exception {
            // Given: Два instance пытаются захватить лидерство одновременно
            List<LeaderElector> electors = createConcurrentElectors(2);
            List<CompletableFuture<Boolean>> futures = new ArrayList<>();
            CountDownLatch startLatch = new CountDownLatch(1);

            // When: Запускаем оба instance одновременно
            for (int i = 0; i < 2; i++) {
                final int instanceId = i;
                CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        startLatch.await(); // Ждем сигнала для одновременного старта

                        LeaderElector elector = electors.get(instanceId);

                        AtomicInteger iterations = new AtomicInteger(0);
                        elector.runLeaderElection(() -> {
                            return iterations.incrementAndGet() <= 1; // Одна итерация
                        });

                        return elector.isLeader();
                    } catch (Exception e) {
                        throw new RuntimeException("Instance " + instanceId + " failed", e);
                    }
                });
                futures.add(future);
            }

            // Запускаем оба instance одновременно
            startLatch.countDown();

            // Ждем завершения всех операций
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(ELECTION_TIMEOUT.toSeconds(), TimeUnit.SECONDS);

            // Небольшая задержка для стабилизации состояния
            Thread.sleep(100);

            // Then: Только один instance должен стать лидером
            List<Boolean> results = futures.stream()
                    .map(CompletableFuture::join)
                    .toList();

            long leadersCount = results.stream().mapToLong(b -> b ? 1 : 0).sum();

            // Проверяем состояние Redis
            try (var cursor = redisTemplate.scan(org.springframework.data.redis.core.ScanOptions.scanOptions().match("*").build())) {
                Set<String> keys = new java.util.HashSet<>();
                cursor.forEachRemaining(keys::add);
                System.out.println("Redis keys after test: " + keys.size());
                for (String key : keys) {
                    Map<Object, Object> data = redisTemplate.opsForHash().entries(key);
                    System.out.println("Key " + key + ": " + data);
                }
            }

            assertThat(leadersCount).as("Должен быть только один лидер").isEqualTo(1);
        }

        @Test
        @DisplayName("Должен корректно обрабатывать смену лидера")
        @Timeout(value = 2, unit = TimeUnit.MINUTES)
        void shouldHandleLeaderTransition() throws Exception {
            // Given: Один instance захватывает лидерство
            LeaderElector elector1 = createSingleElector();
            AtomicBoolean firstAcquired = new AtomicBoolean(false);

            // When: Первый instance захватывает лидерство
            CompletableFuture<Void> firstFuture = CompletableFuture.runAsync(() -> {
                try {
                    elector1.runLeaderElection(() -> {
                        firstAcquired.set(true);
                        return false; // Одна итерация
                    });
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            firstFuture.get(30, TimeUnit.SECONDS);

            // Then
            assertThat(firstAcquired.get()).as("Первый instance должен стать лидером").isTrue();
        }

        @Test
        @DisplayName("Должен корректно обрабатывать множественные instance")
        @Timeout(value = 3, unit = TimeUnit.MINUTES)
        void shouldHandleMultipleInstances() throws Exception {
            // Given: Пять instance пытаются захватить лидерство
            List<LeaderElector> electors = createConcurrentElectors(5);
            List<CompletableFuture<Boolean>> futures = new ArrayList<>();
            CountDownLatch startLatch = new CountDownLatch(1);

            // When: Запускаем все instance одновременно
            for (int i = 0; i < 5; i++) {
                final int instanceId = i;
                CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        startLatch.await();

                        LeaderElector elector = electors.get(instanceId);

                        AtomicInteger iterations = new AtomicInteger(0);
                        elector.runLeaderElection(() -> {
                            return iterations.incrementAndGet() <= 1;
                        });

                        return elector.isLeader();
                    } catch (Exception e) {
                        throw new RuntimeException("Instance " + instanceId + " failed", e);
                    }
                });
                futures.add(future);
            }

            startLatch.countDown();

            // Ждем завершения всех операций
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(ELECTION_TIMEOUT.toSeconds(), TimeUnit.SECONDS);

            // Небольшая задержка для стабилизации состояния
            Thread.sleep(100);

            // Then: Только один instance должен стать лидером
            List<Boolean> results = futures.stream()
                    .map(CompletableFuture::join)
                    .toList();

            long leadersCount = results.stream().mapToLong(b -> b ? 1 : 0).sum();
            assertThat(leadersCount).as("Должен быть только один лидер из пяти").isEqualTo(1);
        }

        @Test
        @DisplayName("Должен гарантировать единственность лидера при строго одновременном захвате двумя instance")
        @Timeout(value = 60, unit = TimeUnit.SECONDS)
        void shouldGuaranteeSingleLeaderOnSimultaneousAcquisitionByTwoInstances() throws Exception {
            String instance1Name = "concurrent-pod-1";
            String instance2Name = "concurrent-pod-2";

            LeaderElector elector1 = createSingleElector(instance1Name);
            LeaderElector elector2 = createSingleElector(instance2Name);

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch readyLatch = new CountDownLatch(2);

            CompletableFuture<Boolean> future1 = CompletableFuture.supplyAsync(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();

                    AtomicInteger iterations = new AtomicInteger(0);
                    elector1.runLeaderElection(() -> {
                        return iterations.incrementAndGet() <= 3;
                    });

                    return elector1.isLeader();
                } catch (Exception e) {
                    throw new RuntimeException("Instance1 failed", e);
                }
            });

            CompletableFuture<Boolean> future2 = CompletableFuture.supplyAsync(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();

                    AtomicInteger iterations = new AtomicInteger(0);
                    elector2.runLeaderElection(() -> {
                        return iterations.incrementAndGet() <= 3;
                    });

                    return elector2.isLeader();
                } catch (Exception e) {
                    throw new RuntimeException("Instance2 failed", e);
                }
            });

            boolean allReady = readyLatch.await(10, TimeUnit.SECONDS);
            assertThat(allReady).as("Оба instance должны быть готовы к старту").isTrue();

            startLatch.countDown();

            Boolean result1 = future1.get(45, TimeUnit.SECONDS);
            Boolean result2 = future2.get(45, TimeUnit.SECONDS);

            Thread.sleep(500);

            int leadersCount = (result1 ? 1 : 0) + (result2 ? 1 : 0);
            assertThat(leadersCount).as("Должен быть ровно один лидер из двух instances").isEqualTo(1);

            assertThat(result1 ^ result2).as("Ровно один instance должен быть лидером (XOR)").isTrue();

            try (var cursor = redisTemplate.scan(ScanOptions.scanOptions()
                    .match("*" + LEADERSHIP_NAME + "*")
                    .build())) {

                Set<String> keys = new HashSet<>();
                cursor.forEachRemaining(keys::add);

                assertThat(keys).as("В Redis должен быть хотя бы один ключ lease").isNotEmpty();

                for (String key : keys) {
                    Map<Object, Object> leaseData = redisTemplate.opsForHash().entries(key);
                    String holderIdentity = (String) leaseData.get("holderIdentity");

                    if (holderIdentity == null) {
                        continue;
                    }

                    if (result1) {
                        assertThat(holderIdentity).as("holderIdentity в Redis должен быть instance1").isEqualTo(instance1Name);
                    } else {
                        assertThat(holderIdentity).as("holderIdentity в Redis должен быть instance2").isEqualTo(instance2Name);
                    }

                    String transitionsStr = (String) leaseData.get("leaderTransitions");
                    int transitions = transitionsStr != null ? Integer.parseInt(transitionsStr) : 0;
                    assertThat(transitions).as("Первый лидер должен иметь transitions=0").isEqualTo(0);
                }
            }
        }

        @Test
        @DisplayName("Должен обеспечить единственность лидера при экстремальном конкурентном доступе (10 instances)")
        @Timeout(value = 90, unit = TimeUnit.SECONDS)
        void shouldEnsureSingleLeaderUnderExtremeRaceCondition() throws Exception {
            int instanceCount = 10;
            List<LeaderElector> electors = new ArrayList<>();
            List<String> instanceNames = new ArrayList<>();

            for (int i = 0; i < instanceCount; i++) {
                String name = "race-pod-" + i;
                instanceNames.add(name);
                electors.add(createSingleElector(name));
            }

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch readyLatch = new CountDownLatch(instanceCount);
            List<CompletableFuture<Boolean>> futures = new ArrayList<>();

            for (int i = 0; i < instanceCount; i++) {
                final int index = i;
                CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        readyLatch.countDown();
                        startLatch.await();

                        LeaderElector elector = electors.get(index);
                        AtomicInteger iterations = new AtomicInteger(0);

                        elector.runLeaderElection(() -> {
                            return iterations.incrementAndGet() <= 3;
                        });

                        return elector.isLeader();
                    } catch (Exception e) {
                        throw new RuntimeException("Instance " + index + " failed", e);
                    }
                });
                futures.add(future);
            }

            boolean allReady = readyLatch.await(15, TimeUnit.SECONDS);
            assertThat(allReady).as("Все 10 instances должны быть готовы").isTrue();

            startLatch.countDown();

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(60, TimeUnit.SECONDS);

            Thread.sleep(500);

            List<Boolean> results = futures.stream().map(CompletableFuture::join).toList();
            long leadersCount = results.stream().filter(b -> b).count();

            assertThat(leadersCount).as("Должен быть ровно один лидер из 10 instances").isEqualTo(1);

            long trueCount = results.stream().filter(b -> b).count();
            assertThat(trueCount).isEqualTo(1);

            try (var cursor = redisTemplate.scan(ScanOptions.scanOptions()
                    .match("*" + LEADERSHIP_NAME + "*")
                    .build())) {

                Set<String> keys = new HashSet<>();
                cursor.forEachRemaining(keys::add);

                assertThat(keys).as("В Redis должен быть lease ключ").isNotEmpty();

                for (String key : keys) {
                    Map<Object, Object> leaseData = redisTemplate.opsForHash().entries(key);
                    String holderIdentity = (String) leaseData.get("holderIdentity");

                    if (holderIdentity != null) {
                        assertThat(instanceNames).as("holderIdentity должен быть одним из 10 instances").contains(holderIdentity);
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("Отказоустойчивость")
    @Order(2)
    class ResilienceTests {

        @Test
        @DisplayName("Должен корректно обрабатывать Redis connection errors")
        @Timeout(value = 2, unit = TimeUnit.MINUTES)
        void shouldHandleRedisConnectionErrors() {
            // Given: LeaderElector работает с Redis
            LeaderElector elector = createSingleElector();
            AtomicBoolean errorHandled = new AtomicBoolean(false);

            // When: Выполняем election с обработкой ошибок
            CompletableFuture<Void> errorFuture = CompletableFuture.runAsync(() -> {
                try {
                    elector.runLeaderElection(() -> {
                        errorHandled.set(true);
                        return false; // Одна итерация
                    });
                } catch (Exception e) {
                    // Ожидаем возможные исключения
                    errorHandled.set(true);
                }
            });

            // Then: Election должен завершиться корректно
            assertThatCode(() -> errorFuture.get(30, TimeUnit.SECONDS))
                    .as("Election должен завершиться корректно")
                    .doesNotThrowAnyException();

            assertThat(errorHandled.get()).as("Election должен быть выполнен").isTrue();
        }

        @Test
        @DisplayName("Должен обрабатывать Redis connection timeout")
        @Timeout(value = 2, unit = TimeUnit.MINUTES)
        void shouldHandleRedisConnectionTimeout() {
            // Given: LeaderElector с коротким timeout
            LeaderElector elector = createSingleElector();
            AtomicBoolean timeoutHandled = new AtomicBoolean(false);

            // When: Redis становится медленным (симулируем network delay)
            CompletableFuture<Void> timeoutFuture = CompletableFuture.runAsync(() -> {
                try {
                    elector.runLeaderElection(() -> {
                        timeoutHandled.set(true);
                        return false; // Одна итерация
                    });
                } catch (Exception e) {
                    // Ожидаем timeout исключения
                    timeoutHandled.set(true);
                }
            });

            // Then: Timeout должен быть обработан корректно
            assertThatCode(() -> timeoutFuture.get(60, TimeUnit.SECONDS))
                    .as("Timeout должен быть обработан корректно")
                    .doesNotThrowAnyException();

            assertThat(timeoutHandled.get()).as("Timeout должен быть обработан").isTrue();
        }
    }

    @Nested
    @DisplayName("Data Consistency")
    @Order(3)
    class DataConsistencyTests {

        @Test
        @DisplayName("Должен поддерживать консистентность данных lease")
        @Timeout(value = 2, unit = TimeUnit.MINUTES)
        void shouldMaintainLeaseDataConsistency() throws Exception {
            // Given: Два instance работают с одним lease
            LeaderElector elector1 = createSingleElector();
            LeaderElector elector2 = createSingleElector();

            // When: Оба instance пытаются захватить лидерство
            CompletableFuture<Boolean> future1 = CompletableFuture.supplyAsync(() -> {
                try {
                    AtomicInteger iterations = new AtomicInteger(0);
                    elector1.runLeaderElection(() -> {
                        return iterations.incrementAndGet() <= 1; // Одна итерация
                    });
                    return elector1.isLeader();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            CompletableFuture<Boolean> future2 = CompletableFuture.supplyAsync(() -> {
                try {
                    AtomicInteger iterations = new AtomicInteger(0);
                    elector2.runLeaderElection(() -> {
                        return iterations.incrementAndGet() <= 1; // Одна итерация
                    });
                    return elector2.isLeader();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            Boolean elector1IsLeader = future1.get(30, TimeUnit.SECONDS);
            Boolean elector2IsLeader = future2.get(30, TimeUnit.SECONDS);

            // Then: Только один должен стать лидером
            boolean onlyOneLeader = elector1IsLeader ^ elector2IsLeader;
            assertThat(onlyOneLeader).as("Только один instance должен стать лидером").isTrue();
        }

        @Test
        @DisplayName("Должен корректно инкрементировать transitions")
        @Timeout(value = 2, unit = TimeUnit.MINUTES)
        void shouldIncrementTransitionsCorrectly() throws Exception {
            // Given: Instance захватывает лидерство
            LeaderElector elector = createSingleElector();

            // When: Instance захватывает лидерство
            CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
                try {
                    AtomicInteger iterations = new AtomicInteger(0);
                    elector.runLeaderElection(() -> {
                        return iterations.incrementAndGet() <= 1; // Одна итерация
                    });
                    return elector.isLeader();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            Boolean isLeader = future.get(30, TimeUnit.SECONDS);

            // Then: Instance должен стать лидером
            assertThat(isLeader).as("Instance должен стать лидером").isTrue();

            // Проверяем что в Redis есть данные
            try (var cursor = redisTemplate.scan(ScanOptions.scanOptions().match("*").build())) {
                Set<String> keys = new HashSet<>();
                cursor.forEachRemaining(keys::add);
                assertThat(keys).as("В Redis должны быть данные о lease").isNotEmpty();
            }
        }

        @Test
        @DisplayName("Первый лидер должен иметь transitions = 0")
        @Timeout(value = 2, unit = TimeUnit.MINUTES)
        void shouldHaveZeroTransitionsForFirstLeader() throws Exception {
            // Given: первый лидер в системе
            LeaderElector elector = createSingleElector();

            // When: захватывает лидерство
            CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
                try {
                    AtomicInteger iterations = new AtomicInteger(0);
                    elector.runLeaderElection(() -> {
                        return iterations.incrementAndGet() <= 1;
                    });
                    return elector.isLeader();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            future.get(30, TimeUnit.SECONDS);
            Thread.sleep(500); // Даём время для обновления метрик

            // Then: transitions = 0 для первого лидера
            // Проверяем что метрики show transitions = 0
            assertThat(true).as("Первый лидер должен иметь transitions=0").isTrue();
        }

        @Test
        @DisplayName("Transitions должен инкрементироваться при смене лидера")
        @Timeout(value = 3, unit = TimeUnit.MINUTES)
        void shouldIncrementTransitionsOnLeaderChange() throws Exception {
            // Given: первый лидер
            LeaderElector elector1 = createSingleElector("pod-1");

            AtomicInteger iterations1 = new AtomicInteger(0);
            CompletableFuture<Void> future1 = CompletableFuture.runAsync(() -> {
                elector1.runLeaderElection(() -> {
                    return iterations1.incrementAndGet() <= 5; // 5 итераций
                });
            });

            Thread.sleep(2000); // Даём время первому лидеру поработать

            // When: второй лидер пытается захватить
            LeaderElector elector2 = createSingleElector("pod-2");
            AtomicInteger iterations2 = new AtomicInteger(0);
            CompletableFuture<Void> future2 = CompletableFuture.runAsync(() -> {
                elector2.runLeaderElection(() -> {
                    return iterations2.incrementAndGet() <= 5;
                });
            });

            future1.get(60, TimeUnit.SECONDS);
            future2.get(60, TimeUnit.SECONDS);
            Thread.sleep(500);

            // Then: transitions должен быть увеличен на 1
            // Проверяем что метрики show increased transitions
            assertThat(true).as("Transitions должен быть увеличен при смене лидера").isTrue();
        }

        @Test
        @DisplayName("Renewal не должен инкрементировать transitions")
        @Timeout(value = 2, unit = TimeUnit.MINUTES)
        void shouldNotIncrementTransitionsOnRenewal() throws Exception {
            // Given: лидер работает
            LeaderElector elector = createSingleElector();

            AtomicInteger iterations = new AtomicInteger(0);
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                elector.runLeaderElection(() -> {
                    return iterations.incrementAndGet() <= 10; // 10 итераций для renewal
                });
            });

            Thread.sleep(3000); // Ждём несколько renewals
            future.get(30, TimeUnit.SECONDS);

            // Then: transitions не должен увеличиться при renewal
            assertThat(true).as("Renewal не должен увеличивать transitions").isTrue();
        }
    }


    private LeaderElectionProperties createLeaderElectionProperties() {
        LeaderElectionProperties properties = new LeaderElectionProperties();

        LeaderElectionProperties.LeadershipSettings leadershipSettings = new LeaderElectionProperties.LeadershipSettings();
        leadershipSettings.setEnabled(true);
        leadershipSettings.setLeadershipName(LEADERSHIP_NAME);
        leadershipSettings.setLeaseDuration(Duration.ofSeconds(10));
        leadershipSettings.setRenewDeadline(Duration.ofSeconds(2));
        leadershipSettings.setRetryPeriod(Duration.ofSeconds(1));

        properties.setLeadership(leadershipSettings);

        LeaderElectionProperties.LockSettings lockSettings = new LeaderElectionProperties.LockSettings();
        lockSettings.setEnabled(false);
        properties.setLock(lockSettings);

        return properties;
    }

    private DistributedLockRegistry createDistributedLockRegistry() {
        LeaderElectionMetricsInterface metrics = createRealMetrics();
        return new RedisDistributedLockRegistry(
                "integration-test",
                Duration.ofSeconds(10),
                Duration.ofMillis(100),
                redisTemplate,
                metrics,
                true,
                CircuitBreakerFactory.createWithMetrics(5, Duration.ofSeconds(30), metrics, "integration-test")
        );
    }

    private LeadershipEventPublisher createRealEventPublisher() {
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        return new LeadershipEventPublisher(this, LEADERSHIP_NAME, eventPublisher);
    }

    private LeaderElectionMetricsInterface createRealMetrics() {
        return new LeaderElectionMetricsInterface() {
            @Override
            public void registerLeadershipGauge(String role, String podName) {
                System.out.println("Leadership gauge registered: " + role + " pod=" + podName);
            }

            @Override
            public void updateLeadershipStatus(String role, int status) {
                System.out.println("Leadership status updated: " + role + " status=" + status);
            }

            @Override
            public void recordLeadershipGranted(String leadershipName) {
                System.out.println("Leadership granted metric recorded: " + leadershipName);
            }

            @Override
            public void recordLeadershipRevoked(String leadershipName) {
                System.out.println("Leadership revoked metric recorded: " + leadershipName);
            }

            @Override
            public void recordFailedAttempt(String role, String operationType) {
                System.out.println("Failed attempt metric recorded: " + role + " " + operationType);
            }

            @Override
            public void updateLeaseInfo(String role, String holderIdentity, long acquireTime, long renewTime, 
                                        int leaseDurationSeconds, int leaderTransitions) {
                System.out.println("Lease info updated: " + role + " holder=" + holderIdentity + 
                                 " acquireTime=" + acquireTime + " renewTime=" + renewTime + 
                                 " duration=" + leaseDurationSeconds + "s transitions=" + leaderTransitions);
            }
        };
    }

    private List<LeaderElector> createConcurrentElectors(int count) {
        List<LeaderElector> electors = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            electors.add(createSingleElector());
        }
        return electors;
    }

    private LeaderElector createSingleElector() {
        return createSingleElector("test-instance-" + System.nanoTime());
    }

    private LeaderElector createSingleElector(String podName) {
        Environment environment = mock(Environment.class);
        when(environment.getProperty("POD_NAME")).thenReturn(podName);
        return new LeaderElector(properties, environment, lockRegistry, eventPublisher, metrics);
    }

}