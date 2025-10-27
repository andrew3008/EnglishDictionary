package space.br1440.platform.leaderelection.processor;

import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import space.br1440.platform.leaderelection.annotation.leadership.LeaderAware;
import space.br1440.platform.leaderelection.configuration.LeaderElectionProperties;
import space.br1440.platform.leaderelection.event.LeadershipEventPublisher;
import space.br1440.platform.leaderelection.leaderelection.LeaderElector;
import space.br1440.platform.leaderelection.leaderelection.model.LeaderInfo;
import space.br1440.platform.leaderelection.leaderelection.model.LeaderRegistry;
import space.br1440.platform.leaderelection.lock.DistributedLockRegistry;
import space.br1440.platform.leaderelection.lock.persistence.model.LeaseRecord;
import space.br1440.platform.leaderelection.lock.persistence.redis.RedisDistributedLockRegistry;
import space.br1440.platform.leaderelection.lock.persistence.redis.resilience.CircuitBreakerFactory;
import space.br1440.platform.leaderelection.metrics.LeaderElectionMetricsInterface;
import space.br1440.platform.leaderelection.metrics.NoOpLeaderElectionMetrics;
import space.br1440.platform.leaderelection.testutil.extension.annotation.WithEmbeddedRedis;
import space.br1440.platform.leaderelection.testutil.extension.annotation.WithLeaderElectionMocks;
import space.br1440.platform.leaderelection.testutil.extension.container.EmbeddedRedisServer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@WithEmbeddedRedis
@WithLeaderElectionMocks
@DisplayName("LeaderAware Integration Tests с Embedded KeyDB")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LeaderAwareIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(LeaderAwareIntegrationTest.class);

    private static final String LEADERSHIP_NAME = "leader-aware-integration-test";
    private static final Duration ASSERTION_TIMEOUT = Duration.ofSeconds(10);

    private StringRedisTemplate redisTemplate;
    private LeaderElectionProperties properties;
    private DistributedLockRegistry lockRegistry;
    private LeaderElectionMetricsInterface metrics;
    private LeaderRegistry leaderRegistry;
    private LeaderAwareProcessor processor;

    private final List<LeaderElector> activeElectors = new CopyOnWriteArrayList<>();
    private final List<ExecutorService> executors = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp(EmbeddedRedisServer redisServer) {
        this.redisTemplate = redisServer.createStringRedisTemplate();
        this.properties = createLeaderElectionProperties();
        this.lockRegistry = createDistributedLockRegistry();
        this.metrics = new NoOpLeaderElectionMetrics();
        this.leaderRegistry = createLeaderRegistry();
        this.processor = createProcessor();

        redisServer.clearRedis(redisTemplate);
    }

    @AfterEach
    void tearDown(EmbeddedRedisServer redisServer) {
        for (LeaderElector elector : activeElectors) {
            try {
                elector.stop();
            } catch (Exception ignored) {
            }
        }
        activeElectors.clear();

        for (ExecutorService executor : executors) {
            try {
                executor.shutdownNow();
                //noinspection ResultOfMethodCallIgnored
                executor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }

        for (ExecutorService executor : executors) {
            assertThat(executor.isTerminated())
                    .as("Executor должен быть полностью остановлен после cleanup")
                    .isTrue();
        }
        executors.clear();

        if (processor != null) {
            processor.destroy();
        }

        if (redisTemplate != null) {
            redisServer.clearRedis(redisTemplate);
        }
    }

    @Nested
    @DisplayName("1. Boolean Field Injection с реальными переходами лидерства")
    @Order(1)
    class BooleanFieldInjectionTests {

        @Test
        @DisplayName("Должен автоматически обновлять boolean поле при приобретении лидерства")
        @Timeout(value = 60, unit = TimeUnit.SECONDS)
        void shouldUpdateBooleanFieldOnRealLeadershipAcquisition() throws Exception {
            // Given: Bean с @LeaderAware boolean полем
            TestBeans.TestBeanWithBooleanField testBean = new TestBeans.TestBeanWithBooleanField();
            processor.postProcessBeforeInitialization(testBean, "testBean");
            assertThat(testBean.isLeader).isFalse();

            // When: Запускаем LeaderElector, который захватит лидерство
            LeaderElector elector = createAndStartElector("instance-1", 3);

            // Then: Boolean поле должно автоматически обновиться
            awaitCondition(() -> testBean.isLeader, ASSERTION_TIMEOUT,
                    "Boolean поле должно стать true после приобретения лидерства");

            assertThat(elector.isLeader()).isTrue();
        }

        @Test
        @DisplayName("Должен обновлять поле при потере лидерства (смена лидера)")
        @Timeout(value = 90, unit = TimeUnit.SECONDS)
        void shouldUpdateBooleanFieldOnLeadershipTransition() throws Exception {
            // Given: Два beans с @LeaderAware и два electors
            TestBeans.TestBeanWithBooleanField bean1 = new TestBeans.TestBeanWithBooleanField();
            TestBeans.TestBeanWithBooleanField bean2 = new TestBeans.TestBeanWithBooleanField();
            processor.postProcessBeforeInitialization(bean1, "bean1");
            processor.postProcessBeforeInitialization(bean2, "bean2");

            // When: Первый elector становится лидером
            LeaderElector elector1 = createAndStartElector("instance-1", 5);
            awaitCondition(() -> bean1.isLeader && bean2.isLeader, ASSERTION_TIMEOUT,
                    "Оба beans должны видеть лидерство");

            assertThat(elector1.isLeader()).isTrue();

            // When: Останавливаем первого лидера и ждём истечения lease
            elector1.stop();
            Thread.sleep(properties.getLeadership().getLeaseDuration().toMillis() * 2);

            // When: Запускаем второго elector
            LeaderElector elector2 = createAndStartElector("instance-2", 5);
            awaitCondition(() -> bean2.isLeader, ASSERTION_TIMEOUT,
                    "Bean2 должен увидеть нового лидера");

            // Then: Переход лидерства отражён в beans
            assertThat(elector2.isLeader()).isTrue();
        }

        @Test
        @DisplayName("Должен синхронизировать boolean поля у нескольких beans")
        @Timeout(value = 60, unit = TimeUnit.SECONDS)
        void shouldSynchronizeBooleanFieldsAcrossMultipleBeans() throws Exception {
            // Given: 5 beans с @LeaderAware boolean полями
            List<TestBeans.TestBeanWithBooleanField> beans = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                TestBeans.TestBeanWithBooleanField bean = new TestBeans.TestBeanWithBooleanField();
                processor.postProcessBeforeInitialization(bean, "bean-" + i);
                beans.add(bean);
            }

            // When: Запускаем elector
            createAndStartElector("leader-instance", 3);

            // Then: Все beans должны синхронно обновиться
            awaitCondition(() -> beans.stream().allMatch(b -> b.isLeader), ASSERTION_TIMEOUT,
                    "Все 5 beans должны иметь isLeader=true");

            for (TestBeans.TestBeanWithBooleanField bean : beans) {
                assertThat(bean.isLeader).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("2. LeaderInfo Field Injection с AsyncLeaderInfoProxy")
    @Order(2)
    class LeaderInfoFieldInjectionTests {

        @Test
        @DisplayName("Должен инжектировать работающий LeaderInfo proxy")
        @Timeout(value = 60, unit = TimeUnit.SECONDS)
        void shouldInjectWorkingLeaderInfoProxy() throws Exception {
            // Given: Bean с LeaderInfo полем
            TestBeans.TestBeanWithLeaderInfoField testBean = new TestBeans.TestBeanWithLeaderInfoField();
            processor.postProcessBeforeInitialization(testBean, "testBean");

            assertThat(testBean.leaderInfo).isNotNull();
            assertThat(testBean.leaderInfo.getLeadershipName()).isEqualTo(LEADERSHIP_NAME);

            // When: Запускаем elector
            createAndStartElector("proxy-test-instance", 3);

            // Then: Proxy должен вернуть корректные данные
            awaitCondition(() -> testBean.leaderInfo.isLeader(), ASSERTION_TIMEOUT,
                    "LeaderInfo proxy должен показывать лидерство");

            assertThat(testBean.leaderInfo.getCurrentLeader()).isEqualTo("proxy-test-instance");
        }

        @Test
        @DisplayName("Должен обновлять LeaderInfo при смене лидера")
        @Timeout(value = 90, unit = TimeUnit.SECONDS)
        void shouldUpdateLeaderInfoOnLeaderChange() throws Exception {
            // Given: Bean с LeaderInfo
            TestBeans.TestBeanWithLeaderInfoField testBean = new TestBeans.TestBeanWithLeaderInfoField();
            processor.postProcessBeforeInitialization(testBean, "testBean");

            // When: Первый лидер
            LeaderElector elector1 = createAndStartElector("leader-v1", 5);
            awaitCondition(() -> "leader-v1".equals(testBean.leaderInfo.getCurrentLeader()),
                    ASSERTION_TIMEOUT, "LeaderInfo должен показать leader-v1");

            // When: Смена лидера
            elector1.stop();
            Thread.sleep(properties.getLeadership().getLeaseDuration().toMillis() * 2);

            createAndStartElector("leader-v2", 5);

            // Then: LeaderInfo должен обновиться (через TTL refresh + новое событие)
            awaitCondition(() -> "leader-v2".equals(testBean.leaderInfo.getCurrentLeader()),
                    Duration.ofSeconds(20), "LeaderInfo должен обновиться на leader-v2");
        }

        @Test
        @DisplayName("Должен поддерживать кастомный TTL для LeaderInfo")
        @Timeout(value = 60, unit = TimeUnit.SECONDS)
        void shouldSupportCustomTtlForLeaderInfo() throws Exception {
            // Given: Bean с кастомным TTL (2000ms)
            TestBeans.TestBeanWithCustomTtlLeaderInfo testBean = new TestBeans.TestBeanWithCustomTtlLeaderInfo();
            processor.postProcessBeforeInitialization(testBean, "testBean");

            // When: Запускаем elector
            createAndStartElector("ttl-test-leader", 3);

            awaitCondition(() -> testBean.leaderInfoCustomTtl.isLeader(),
                    ASSERTION_TIMEOUT, "Custom TTL LeaderInfo должен показать лидерство");

            // Then: Proxy создан с корректным TTL
            assertThat(testBean.leaderInfoCustomTtl).isNotNull();
            assertThat(testBean.leaderInfoCustomTtl.getCurrentLeader()).isEqualTo("ttl-test-leader");
        }
    }

    @Nested
    @DisplayName("3. Method Callbacks с реальными событиями")
    @Order(3)
    class MethodCallbackTests {

        @Test
        @DisplayName("Должен вызывать метод без параметров при смене лидерства")
        @Timeout(value = 60, unit = TimeUnit.SECONDS)
        void shouldInvokeNoArgMethodOnLeadershipChange() throws Exception {
            // Given: Bean с методом @LeaderAware без параметров
            TestBeans.TestBeanWithNoArgMethod testBean = new TestBeans.TestBeanWithNoArgMethod();
            processor.postProcessBeforeInitialization(testBean, "testBean");

            // When: Приобретаем лидерство
            createAndStartElector("method-test-leader", 3);

            // Then: Метод должен быть вызван
            boolean invoked = testBean.invocationLatch.await(ASSERTION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(invoked).as("Метод без параметров должен быть вызван").isTrue();
            assertThat(testBean.invocationCount.get()).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("Должен вызывать метод с boolean параметром и передавать корректное значение")
        @Timeout(value = 90, unit = TimeUnit.SECONDS)
        void shouldInvokeBooleanArgMethodWithCorrectValue() throws Exception {
            // Given: Bean с методом @LeaderAware(boolean)
            TestBeans.TestBeanWithBooleanArgMethod testBean = new TestBeans.TestBeanWithBooleanArgMethod();
            processor.postProcessBeforeInitialization(testBean, "testBean");

            // When: Приобретаем лидерство
            LeaderElector elector = createAndStartElector("bool-method-leader", 5);

            boolean grantedInvoked = testBean.grantedLatch.await(ASSERTION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(grantedInvoked).isTrue();
            assertThat(testBean.lastValue.get()).isTrue();

            // When: Теряем лидерство
            testBean.revokedLatch = new CountDownLatch(1);
            elector.stop();

            boolean revokedInvoked = testBean.revokedLatch.await(ASSERTION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(revokedInvoked).isTrue();
            assertThat(testBean.lastValue.get()).isFalse();
        }

        @Test
        @DisplayName("Должен вызывать несколько методов callback у одного bean")
        @Timeout(value = 60, unit = TimeUnit.SECONDS)
        void shouldInvokeMultipleCallbackMethodsOnSameBean() throws Exception {
            // Given: Bean с двумя @LeaderAware методами
            TestBeans.TestBeanWithMultipleMethods testBean = new TestBeans.TestBeanWithMultipleMethods();
            processor.postProcessBeforeInitialization(testBean, "testBean");

            // When: Приобретаем лидерство
            createAndStartElector("multi-method-leader", 3);

            // Then: Оба метода должны быть вызваны
            boolean method1 = testBean.method1Latch.await(ASSERTION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            boolean method2 = testBean.method2Latch.await(ASSERTION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            assertThat(method1).as("Первый метод должен быть вызван").isTrue();
            assertThat(method2).as("Второй метод должен быть вызван").isTrue();
        }
    }

    @Nested
    @DisplayName("4. Multi-Instance синхронизация")
    @Order(4)
    class MultiInstanceTests {

        @Test
        @DisplayName("Должен синхронизировать состояние между 3 инстансами")
        @Timeout(value = 120, unit = TimeUnit.SECONDS)
        void shouldSynchronizeStateAcrossThreeInstances() throws Exception {
            // Given: 3 beans и 3 electors
            TestBeans.TestBeanWithBooleanField bean1 = new TestBeans.TestBeanWithBooleanField();
            TestBeans.TestBeanWithBooleanField bean2 = new TestBeans.TestBeanWithBooleanField();
            TestBeans.TestBeanWithBooleanField bean3 = new TestBeans.TestBeanWithBooleanField();

            processor.postProcessBeforeInitialization(bean1, "bean1");
            processor.postProcessBeforeInitialization(bean2, "bean2");
            processor.postProcessBeforeInitialization(bean3, "bean3");

            // When: Все 3 инстанса конкурируют за лидерство
            LeaderElector elector1 = createAndStartElector("instance-1", 10);
            LeaderElector elector2 = createAndStartElector("instance-2", 10);
            LeaderElector elector3 = createAndStartElector("instance-3", 10);

            awaitCondition(() -> {
                long leaderCount = Stream.of(elector1, elector2, elector3)
                        .filter(LeaderElector::isLeader)
                        .count();
                return leaderCount == 1;
            }, Duration.ofSeconds(15), "Должен быть ровно один лидер из трёх инстансов");

            // Then: Все beans видят одно и то же состояние лидерства
            assertThat(bean1.isLeader).isEqualTo(bean2.isLeader).isEqualTo(bean3.isLeader);

            // Ровно один elector - лидер
            long leaderCount = Stream.of(elector1, elector2, elector3)
                    .filter(LeaderElector::isLeader)
                    .count();
            assertThat(leaderCount).isEqualTo(1);
        }

        @Test
        @DisplayName("Должен корректно обрабатывать failover между инстансами")
        @Timeout(value = 120, unit = TimeUnit.SECONDS)
        void shouldHandleFailoverBetweenInstances() throws Exception {
            // Given: 2 beans для двух инстансов
            TestBeans.TestBeanWithBooleanField beanA = new TestBeans.TestBeanWithBooleanField();
            TestBeans.TestBeanWithBooleanField beanB = new TestBeans.TestBeanWithBooleanField();

            processor.postProcessBeforeInitialization(beanA, "beanA");
            processor.postProcessBeforeInitialization(beanB, "beanB");

            // When: Instance A становится лидером
            LeaderElector electorA = createAndStartElector("instance-A", 10);
            awaitCondition(() -> beanA.isLeader && beanB.isLeader, ASSERTION_TIMEOUT,
                    "Оба beans должны видеть лидерство Instance A");

            // When: Instance A падает
            electorA.stop();
            
            // Ждём истечения lease
            Thread.sleep(properties.getLeadership().getLeaseDuration().toMillis() * 2);

            // When: Instance B подхватывает
            LeaderElector electorB = createAndStartElector("instance-B", 50);
            awaitCondition(electorB::isLeader, Duration.ofSeconds(30),"Instance B должен стать лидером");

            // Then: Beans синхронизированы с новым лидером
            awaitCondition(() -> beanA.isLeader && beanB.isLeader, ASSERTION_TIMEOUT,
                    "Оба beans должны видеть лидерство Instance B");
        }
    }

    @Nested
    @DisplayName("5. Failover и Recovery сценарии")
    @Order(5)
    class FailoverRecoveryTests {

        @Test
        @DisplayName("Должен восстановиться после временного сбоя лидера")
        @Timeout(value = 120, unit = TimeUnit.SECONDS)
        void shouldRecoverAfterLeaderFailure() throws Exception {
            // Given: Bean и два потенциальных лидера
            TestBeans.TestBeanWithLeaderInfoField testBean = new TestBeans.TestBeanWithLeaderInfoField();
            processor.postProcessBeforeInitialization(testBean, "testBean");

            // When: Первый лидер работает
            LeaderElector elector1 = createAndStartElector("primary-leader", 8);
            awaitCondition(() -> testBean.leaderInfo.isLeader(), ASSERTION_TIMEOUT,
                    "Primary должен стать лидером");

            String primaryLeader = testBean.leaderInfo.getCurrentLeader();
            assertThat(primaryLeader).isEqualTo("primary-leader");

            // When: Primary падает
            elector1.stop();
            
            // Ждём истечения lease (leaseDuration * 2 для надёжности)
            long waitTime = properties.getLeadership().getLeaseDuration().toMillis() * 2;
            Thread.sleep(waitTime);

            // When: Backup подхватывает
            LeaderElector elector2 = createAndStartElector("backup-leader", 8);
            awaitCondition(elector2::isLeader, ASSERTION_TIMEOUT,"Backup должен стать лидером");

            // Then: LeaderInfo обновляется на нового лидера (через TTL refresh)
            awaitCondition(() -> "backup-leader".equals(testBean.leaderInfo.getCurrentLeader()),
                    Duration.ofSeconds(20), "LeaderInfo должен показать backup-leader");
        }

        @Test
        @DisplayName("Должен обрабатывать быстрые циклы failover")
        @Timeout(value = 150, unit = TimeUnit.SECONDS)
        void shouldHandleRapidFailoverCycles() throws Exception {
            // Given: Bean отслеживающий смены лидеров
            TestBeans.TestBeanWithBooleanArgMethod testBean = new TestBeans.TestBeanWithBooleanArgMethod();
            processor.postProcessBeforeInitialization(testBean, "testBean");

            // When: Запускаем 3 цикла смены лидеров
            for (int i = 0; i < 3; i++) {
                String leaderName = "rapid-leader-" + i;

                testBean.grantedLatch = new CountDownLatch(1);
                testBean.revokedLatch = new CountDownLatch(1);

                LeaderElector elector = createAndStartElector(leaderName, 5);

                boolean granted = testBean.grantedLatch.await(ASSERTION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                assertThat(granted).as("Цикл %d: метод должен быть вызван с true", i).isTrue();
                assertThat(testBean.lastValue.get()).isTrue();

                elector.stop();

                boolean revoked = testBean.revokedLatch.await(ASSERTION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                assertThat(revoked).as("Цикл %d: метод должен быть вызван с false", i).isTrue();
                assertThat(testBean.lastValue.get()).isFalse();

                Thread.sleep(properties.getLeadership().getLeaseDuration().toMillis() * 2);
            }
        }
    }

    @Nested
    @DisplayName("6. Комплексные сценарии")
    @Order(6)
    class ComplexScenarioTests {

        @Test
        @DisplayName("Должен обрабатывать bean со всеми типами @LeaderAware")
        @Timeout(value = 60, unit = TimeUnit.SECONDS)
        void shouldHandleBeanWithAllLeaderAwareTypes() throws Exception {
            // Given: Bean с boolean полем, LeaderInfo полем и методом
            TestBeans.TestBeanWithAllTypes testBean = new TestBeans.TestBeanWithAllTypes();
            processor.postProcessBeforeInitialization(testBean, "testBean");

            // When: Запускаем elector
            createAndStartElector("complex-leader", 5);

            // Then: Все компоненты синхронизированы
            awaitCondition(() -> testBean.isLeader, ASSERTION_TIMEOUT,
                    "Boolean поле должно быть true");

            awaitCondition(() -> testBean.leaderInfo != null && testBean.leaderInfo.isLeader(),
                    ASSERTION_TIMEOUT, "LeaderInfo должен показывать лидерство");

            boolean methodInvoked = testBean.methodLatch.await(ASSERTION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(methodInvoked).as("Метод должен быть вызван").isTrue();
            assertThat(testBean.methodLastValue.get()).isTrue();

            assertThat(testBean.leaderInfo.getCurrentLeader()).isEqualTo("complex-leader");
        }

        @Test
        @DisplayName("Должен корректно работать при конкурентных обновлениях")
        @Timeout(value = 90, unit = TimeUnit.SECONDS)
        void shouldHandleConcurrentUpdates() throws Exception {
            // Given: 10 beans с @LeaderAware
            List<TestBeans.TestBeanWithBooleanField> beans = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                TestBeans.TestBeanWithBooleanField bean = new TestBeans.TestBeanWithBooleanField();
                processor.postProcessBeforeInitialization(bean, "bean-" + i);
                beans.add(bean);
            }

            // When: Запускаем 5 конкурирующих electors
            List<LeaderElector> electors = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                LeaderElector elector = createAndStartElector("concurrent-" + i, 10);
                electors.add(elector);
            }

            awaitCondition(() -> {
                long leaderCount = electors.stream().filter(LeaderElector::isLeader).count();
                return leaderCount == 1;
            }, Duration.ofSeconds(15), "Должен быть ровно один лидер из пяти");

            // Then: Ровно один лидер
            long leaderCount = electors.stream().filter(LeaderElector::isLeader).count();
            assertThat(leaderCount).isEqualTo(1);

            // Все beans консистентны
            boolean allLeader = beans.stream().allMatch(b -> b.isLeader);
            boolean allFollower = beans.stream().noneMatch(b -> b.isLeader);
            assertThat(allLeader || allFollower)
                    .as("Все beans должны иметь одинаковое состояние")
                    .isTrue();
        }
    }

    private LeaderElector createAndStartElector(String instanceName, int iterations) {
        Environment environment = mock(Environment.class);
        when(environment.getProperty("POD_NAME")).thenReturn(instanceName);

        ApplicationEventPublisher appEventPublisher = event -> {
            if (processor != null && event instanceof ApplicationEvent) {
                processor.onApplicationEvent((ApplicationEvent) event);
            }
        };

        LeadershipEventPublisher eventPublisher = new LeadershipEventPublisher(
                this, LEADERSHIP_NAME, appEventPublisher
        );

        LeaderElector elector = new LeaderElector(
                properties, environment, lockRegistry, eventPublisher, metrics
        );

        activeElectors.add(elector);

        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "elector-" + instanceName);
            t.setDaemon(true);
            return t;
        });
        executors.add(executor);

        AtomicInteger counter = new AtomicInteger(0);
        executor.submit(() -> {
            try {
                elector.runLeaderElection(() -> counter.incrementAndGet() < iterations);
            } catch (Exception e) {
                log.error("Elector {} failed", instanceName, e);
            }
        });

        return elector;
    }

    private void awaitCondition(BooleanSupplier condition, Duration timeout, String message) throws Exception {
        long startTime = System.currentTimeMillis();
        long timeoutMillis = timeout.toMillis();

        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() - startTime > timeoutMillis) {
                throw new AssertionError("Timeout waiting for condition: " + message);
            }
            Thread.sleep(200);
        }
    }

    private LeaderElectionProperties createLeaderElectionProperties() {
        LeaderElectionProperties props = new LeaderElectionProperties();
        LeaderElectionProperties.LeadershipSettings leadership = new LeaderElectionProperties.LeadershipSettings();
        leadership.setEnabled(true);
        leadership.setLeadershipName(LEADERSHIP_NAME);
        leadership.setLeaseDuration(Duration.ofSeconds(8));
        leadership.setRenewDeadline(Duration.ofSeconds(2));
        leadership.setRetryPeriod(Duration.ofSeconds(1));
        props.setLeadership(leadership);

        LeaderElectionProperties.LockSettings lock = new LeaderElectionProperties.LockSettings();
        lock.setEnabled(false);
        props.setLock(lock);

        return props;
    }

    private DistributedLockRegistry createDistributedLockRegistry() {
        return new RedisDistributedLockRegistry(
                "leader-aware-test",
                Duration.ofSeconds(10),
                Duration.ofMillis(100),
                redisTemplate,
                metrics,
                true,
                CircuitBreakerFactory.createWithMetrics(5, Duration.ofSeconds(30), metrics, "leader-aware-test")
        );
    }

    private LeaderRegistry createLeaderRegistry() {
        return new LeaderRegistry() {
            @Override
            public LeaderInfo getLeaderInfo(String leadershipName) {
                try {
                    LeaseRecord currentRecord = lockRegistry.obtain(leadershipName).getCurrentRecord();
                    if (currentRecord == null) {
                        return new LeaderInfo() {
                            @Override
                            public boolean isLeader() { return false; }
                            @Override
                            public String getCurrentLeader() { return null; }
                            @Override
                            public String getLeadershipName() { return leadershipName; }
                        };
                    }

                    String holder = currentRecord.getHolderIdentity();
                    return new LeaderInfo() {
                        @Override
                        public boolean isLeader() { return holder != null; }
                        @Override
                        public String getCurrentLeader() { return holder; }
                        @Override
                        public String getLeadershipName() { return leadershipName; }
                    };
                } catch (Exception e) {
                    log.error("Error getting leader info", e);
                    return new LeaderInfo() {
                        @Override
                        public boolean isLeader() { return false; }
                        @Override
                        public String getCurrentLeader() { return null; }
                        @Override
                        public String getLeadershipName() { return leadershipName; }
                    };
                }
            }
        };
    }

    private LeaderAwareProcessor createProcessor() {
        Environment environment = mock(Environment.class);
        return new LeaderAwareProcessor(leaderRegistry, environment, properties);
    }

    static class TestBeans {

        static class TestBeanWithBooleanField {
            @LeaderAware
            private volatile boolean isLeader = false;
        }

        static class TestBeanWithLeaderInfoField {
            @LeaderAware
            private LeaderInfo leaderInfo;
        }

        static class TestBeanWithCustomTtlLeaderInfo {
            @LeaderAware(ttlMillis = 2000)
            private LeaderInfo leaderInfoCustomTtl;
        }

        static class TestBeanWithNoArgMethod {
            private final CountDownLatch invocationLatch = new CountDownLatch(1);
            private final AtomicInteger invocationCount = new AtomicInteger(0);

            @LeaderAware
            public void onLeadershipChange() {
                invocationCount.incrementAndGet();
                invocationLatch.countDown();
                LoggerFactory.getLogger(TestBeanWithNoArgMethod.class)
                        .info("onLeadershipChange() вызван, count={}", invocationCount.get());
            }
        }

        static class TestBeanWithBooleanArgMethod {
            private volatile CountDownLatch grantedLatch = new CountDownLatch(1);
            private volatile CountDownLatch revokedLatch = new CountDownLatch(1);
            private final AtomicReference<Boolean> lastValue = new AtomicReference<>();

            @LeaderAware
            public void onLeadershipChange(boolean isLeader) {
                lastValue.set(isLeader);
                if (isLeader) {
                    grantedLatch.countDown();
                } else {
                    revokedLatch.countDown();
                }
                LoggerFactory.getLogger(TestBeanWithBooleanArgMethod.class)
                        .info("onLeadershipChange(boolean) вызван с isLeader={}", isLeader);
            }
        }

        static class TestBeanWithMultipleMethods {
            private final CountDownLatch method1Latch = new CountDownLatch(1);
            private final CountDownLatch method2Latch = new CountDownLatch(1);

            @LeaderAware
            public void firstMethod() {
                method1Latch.countDown();
                LoggerFactory.getLogger(TestBeanWithMultipleMethods.class)
                        .info("firstMethod() вызван");
            }

            @LeaderAware
            public void secondMethod(boolean isLeader) {
                method2Latch.countDown();
                LoggerFactory.getLogger(TestBeanWithMultipleMethods.class)
                        .info("secondMethod(boolean) вызван");
            }
        }

        static class TestBeanWithAllTypes {
            @LeaderAware
            private volatile boolean isLeader = false;

            @LeaderAware
            private LeaderInfo leaderInfo;

            private final CountDownLatch methodLatch = new CountDownLatch(1);
            private final AtomicReference<Boolean> methodLastValue = new AtomicReference<>();

            @LeaderAware
            public void onLeaderChange(boolean leader) {
                methodLastValue.set(leader);
                methodLatch.countDown();
            }
        }
    }

}
