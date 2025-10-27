package space.br1440.platform.leaderelection.leaderelection;

import org.junit.jupiter.api.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import space.br1440.platform.leaderelection.configuration.LeaderElectionProperties;
import space.br1440.platform.leaderelection.event.LeadershipEventPublisher;
import space.br1440.platform.leaderelection.lock.DistributedLockRegistry;
import space.br1440.platform.leaderelection.lock.persistence.redis.RedisDistributedLockRegistry;
import space.br1440.platform.leaderelection.lock.persistence.redis.resilience.CircuitBreakerFactory;
import space.br1440.platform.leaderelection.metrics.LeaderElectionMetricsInterface;
import space.br1440.platform.leaderelection.metrics.NoOpLeaderElectionMetrics;
import space.br1440.platform.leaderelection.testutil.extension.annotation.WithEmbeddedRedis;
import space.br1440.platform.leaderelection.testutil.extension.annotation.WithLeaderElectionMocks;
import space.br1440.platform.leaderelection.testutil.extension.container.EmbeddedRedisServer;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Disabled("Slow test")
@WithEmbeddedRedis
@WithLeaderElectionMocks
@DisplayName("LeaderElector Stop-Start Cycle Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LeaderElectorStopStartTest {

    private StringRedisTemplate redisTemplate;
    private LeaderElectionProperties properties;
    private DistributedLockRegistry lockRegistry;
    private LeaderElectionMetricsInterface metrics;

    private static final String LEADERSHIP_NAME = "stop-start-test-leadership";

    @BeforeEach
    void setUp(EmbeddedRedisServer redisServer) {
        this.redisTemplate = redisServer.createStringRedisTemplate();
        this.properties = createLeaderElectionProperties();
        this.lockRegistry = createDistributedLockRegistry();
        this.metrics = createRealMetrics();
    }

    @Nested
    @DisplayName("Stop-Start Cycles")
    class StopStartCycles {

        @Test
        @DisplayName("Должен корректно восстанавливать лидерство после stop → start")
        @Timeout(value = 3, unit = TimeUnit.MINUTES)
        @Order(1)
        void shouldRecoverLeadershipAfterStopStart() throws Exception {
            // Given: один лидер работает
            LeaderElector elector1 = createElector("instance-1");
            AtomicInteger iterations = new AtomicInteger(0);
            
            elector1.runLeaderElection(() -> iterations.incrementAndGet() <= 5);
            
            // Проверяем, что первый instance стал лидером
            assertThat(elector1.isLeader())
                    .as("Instance 1 должен быть лидером")
                    .isTrue();

            // When: останавливаем лидера
            elector1.stop();

            // Ждём, чтобы lease истёк
            Thread.sleep(properties.getLeadership().getLeaseDuration().toMillis() * 2);

            // И запускаем снова
            LeaderElector elector1Restarted = createElector("instance-1");
            AtomicInteger iterationsAfterRestart = new AtomicInteger(0);
            
            elector1Restarted.runLeaderElection(() -> {
                try {
                    boolean isLeader = iterationsAfterRestart.incrementAndGet() <= 5;
                    if (isLeader) {
                        Thread.sleep(100);
                    }
                    return isLeader;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            });

            // Then: instance должен снова стать лидером
            assertThat(elector1Restarted.isLeader())
                    .as("Instance 1 должен снова стать лидером после перезапуска")
                    .isTrue();

            elector1Restarted.stop();
        }

        @Test
        @DisplayName("Должен предотвратить двойное лидерство при stop → start с конкурентами")
        @Timeout(value = 3, unit = TimeUnit.MINUTES)
        @Order(2)
        void shouldPreventDoubleLeadershipOnStopStartWithCompetitors() throws Exception {
            // Given: два instance конкурируют за лидерство
            LeaderElector elector1 = createElector("instance-1");
            LeaderElector elector2 = createElector("instance-2");

            ExecutorService executor1 = Executors.newSingleThreadExecutor();
            ExecutorService executor2 = Executors.newSingleThreadExecutor();

            // Первый instance становится лидером
            AtomicInteger iterations1 = new AtomicInteger(0);
            CompletableFuture<Void> future1 = CompletableFuture.runAsync(() -> {
                elector1.runLeaderElection(() -> iterations1.incrementAndGet() <= 2);
            }, executor1);

            // Даём время первому instance захватить лидерство
            Thread.sleep(1000);

            // Проверяем, что instance 1 - лидер
            assertThat(elector1.isLeader())
                    .as("Instance 1 должен быть лидером")
                    .isTrue();

            // Когда: останавливаем лидера
            elector1.stop();
            executor1.shutdown();

            // Ждём, чтобы lease истёк
            Thread.sleep(properties.getLeadership().getLeaseDuration().toMillis() * 2);

            // Второй instance начинает лидерскую гонку
            AtomicInteger iterations2 = new AtomicInteger(0);
            CompletableFuture<Void> future2 = CompletableFuture.runAsync(() -> {
                elector2.runLeaderElection(() -> iterations2.incrementAndGet() <= 2);
            }, executor2);

            // Одновременно перезапускаем первый instance
            LeaderElector elector1Restarted = createElector("instance-1");
            ExecutorService executor1Restarted = Executors.newSingleThreadExecutor();
            AtomicInteger iterations1Restarted = new AtomicInteger(0);
            
            CompletableFuture<Void> future1Restarted = CompletableFuture.runAsync(() -> {
                elector1Restarted.runLeaderElection(() -> iterations1Restarted.incrementAndGet() <= 2);
            }, executor1Restarted);

            // Ждём завершения всех операций
            CompletableFuture.allOf(future1, future2, future1Restarted).get(60, TimeUnit.SECONDS);

            // Затем: проверяем, что только один instance лидер
            boolean isLeader2 = elector2.isLeader();
            boolean isLeader1Restarted = elector1Restarted.isLeader();

            // XOR условие: ровно один должен быть лидером
            assertThat(isLeader2 ^ isLeader1Restarted)
                    .as("Ровно один instance должен быть лидером (XOR condition)")
                    .isTrue();

            assertThat(isLeader2 ? 1 : (isLeader1Restarted ? 1 : 0))
                    .as("Только один instance должен быть лидером")
                    .isEqualTo(1);

            // Cleanup
            elector2.stop();
            elector1Restarted.stop();
            executor2.shutdown();
            executor1Restarted.shutdown();
        }

        @Test
        @DisplayName("Должен корректно обрабатывать множественные stop → start циклы")
        @Timeout(value = 5, unit = TimeUnit.MINUTES)
        @Order(3)
        void shouldHandleMultipleStopStartCycles() throws Exception {
            // Given
            ExecutorService executor = Executors.newSingleThreadExecutor();

            for (int cycle = 0; cycle < 3; cycle++) {
                // Создаём новый elector для каждого цикла
                LeaderElector elector = createElector("instance-restart");

                // When: запускаем лидерскую гонку
                AtomicInteger iterations = new AtomicInteger(0);
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    elector.runLeaderElection(() -> iterations.incrementAndGet() <= 2);
                }, executor);

                // Ждём завершения
                future.get(30, TimeUnit.SECONDS);

                // Then: instance должен быть лидером
                assertThat(elector.isLeader())
                        .as("Instance должен быть лидером в цикле " + cycle)
                        .isTrue();

                // Stop
                elector.stop();

                // Ждём истечения lease
                Thread.sleep(properties.getLeadership().getLeaseDuration().toMillis() * 2);
            }

            executor.shutdown();
        }
    }

    @Nested
    @DisplayName("Graceful Shutdown Tests")
    class GracefulShutdownTests {

        @Test
        @DisplayName("Должен освобождать лидерство при stop")
        @Timeout(value = 2, unit = TimeUnit.MINUTES)
        @Order(1)
        void shouldReleaseLeadershipOnStop() throws Exception {
            // Given
            LeaderElector elector = createElector("instance-shutdown");
            ExecutorService executor = Executors.newSingleThreadExecutor();

            AtomicInteger iterations = new AtomicInteger(0);
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                elector.runLeaderElection(() -> iterations.incrementAndGet() <= 2);
            }, executor);

            future.get(30, TimeUnit.SECONDS);

            // Проверяем, что лидер
            assertThat(elector.isLeader())
                    .as("Instance должен быть лидером")
                    .isTrue();

            // When: останавливаем
            elector.stop();

            // Then: внутреннее состояние может быть не синхронизировано с Redis,
            // поэтому проверяем, что после stop лидер не выполняет задачи
            // (это косвенная проверка, что лидерство действительно освобождено)
            Thread.sleep(3000);

            // Проверяем через другого instance, что лидерство действительно освобождено
            LeaderElector elector2 = createElector("instance-shutdown-2");
            ExecutorService executor2 = Executors.newSingleThreadExecutor();
            
            AtomicInteger iterations2 = new AtomicInteger(0);
            CompletableFuture<Void> future2 = CompletableFuture.runAsync(() -> {
                elector2.runLeaderElection(() -> iterations2.incrementAndGet() <= 2);
            }, executor2);

            future2.get(30, TimeUnit.SECONDS);

            // Если второй instance стал лидером, значит первый действительно освободил лидерство
            assertThat(elector2.isLeader())
                    .as("Instance 2 должен захватить лидерство после stop instance 1")
                    .isTrue();

            elector2.stop();
            executor.shutdown();
            executor2.shutdown();
        }

        @Test
        @DisplayName("Должен позволить другой instance захватить лидерство после graceful shutdown")
        @Timeout(value = 3, unit = TimeUnit.MINUTES)
        @Order(2)
        void shouldAllowOtherInstanceToAcquireLeadershipAfterGracefulShutdown() throws Exception {
            // Given: два instance
            LeaderElector elector1 = createElector("instance-graceful-1");
            LeaderElector elector2 = createElector("instance-graceful-2");

            ExecutorService executor1 = Executors.newSingleThreadExecutor();
            ExecutorService executor2 = Executors.newSingleThreadExecutor();

            // Instance 1 захватывает лидерство
            AtomicInteger iterations1 = new AtomicInteger(0);
            CompletableFuture<Void> future1 = CompletableFuture.runAsync(() -> {
                elector1.runLeaderElection(() -> iterations1.incrementAndGet() <= 2);
            }, executor1);

            future1.get(30, TimeUnit.SECONDS);

            // Проверяем, что instance 1 - лидер
            assertThat(elector1.isLeader())
                    .as("Instance 1 должен быть лидером")
                    .isTrue();

            // When: останавливаем instance 1
            elector1.stop();
            executor1.shutdown();

            // Ждём, чтобы lease истёк
            Thread.sleep(properties.getLeadership().getLeaseDuration().toMillis() * 2);

            // Запускаем instance 2
            AtomicInteger iterations2 = new AtomicInteger(0);
            CompletableFuture<Void> future2 = CompletableFuture.runAsync(() -> {
                elector2.runLeaderElection(() -> iterations2.incrementAndGet() <= 2);
            }, executor2);

            future2.get(30, TimeUnit.SECONDS);

            // Then: instance 2 должен стать лидером
            assertThat(elector2.isLeader())
                    .as("Instance 2 должен стать лидером после graceful shutdown instance 1")
                    .isTrue();

            elector2.stop();
            executor2.shutdown();
        }
    }

    // Helper methods

    private LeaderElector createElector(String instanceName) {
        Environment environment = mock(Environment.class);
        when(environment.getProperty("POD_NAME")).thenReturn(instanceName);

        ApplicationEventPublisher appEventPublisher = mock(ApplicationEventPublisher.class);
        LeadershipEventPublisher eventPublisher = new LeadershipEventPublisher(
                appEventPublisher, LEADERSHIP_NAME, appEventPublisher
        );

        return new LeaderElector(properties, environment, lockRegistry, eventPublisher, metrics);
    }

    private LeaderElectionProperties createLeaderElectionProperties() {
        LeaderElectionProperties props = new LeaderElectionProperties();

        LeaderElectionProperties.LeadershipSettings leadershipSettings = 
                new LeaderElectionProperties.LeadershipSettings();
        leadershipSettings.setEnabled(true);
        leadershipSettings.setLeadershipName(LEADERSHIP_NAME);
        leadershipSettings.setLeaseDuration(Duration.ofSeconds(15));
        leadershipSettings.setRenewDeadline(Duration.ofSeconds(10));
        leadershipSettings.setRetryPeriod(Duration.ofSeconds(2));
        props.setLeadership(leadershipSettings);

        return props;
    }

    private DistributedLockRegistry createDistributedLockRegistry() {
        return new RedisDistributedLockRegistry(
                "test",
                Duration.ofSeconds(15),
                Duration.ofMillis(100),
                redisTemplate,
                new NoOpLeaderElectionMetrics(),
                false,
                CircuitBreakerFactory.create(5, Duration.ofSeconds(30))
        );
    }

    private LeaderElectionMetricsInterface createRealMetrics() {
        return new NoOpLeaderElectionMetrics();
    }
}

