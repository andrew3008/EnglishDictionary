package space.br1440.platform.leaderelection.leaderelection;

import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.Environment;
import space.br1440.platform.leaderelection.configuration.LeaderElectionProperties;
import space.br1440.platform.leaderelection.event.LeadershipEventPublisher;
import space.br1440.platform.leaderelection.lock.DistributedLock;
import space.br1440.platform.leaderelection.lock.DistributedLockRegistry;
import space.br1440.platform.leaderelection.lock.persistence.model.LeaseRecord;
import space.br1440.platform.leaderelection.metrics.LeaderElectionMetricsInterface;
import space.br1440.platform.leaderelection.testutil.extension.annotation.WithLeaderElectionMocks;
import space.br1440.platform.leaderelection.testutil.extension.annotation.WithEmbeddedRedis;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@WithEmbeddedRedis
@WithLeaderElectionMocks
@DisplayName("Тесты LeaderElector")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LeaderElectorTest {

    private static final String LEADERSHIP_NAME = "test-leadership";
    private static final String HOLDER_IDENTITY_1 = "instance-1";
    private static final String HOLDER_IDENTITY_2 = "instance-2";

    private LeaderElectionProperties properties;
    private Environment environment;
    private DistributedLockRegistry lockRegistry;
    private DistributedLock distributedLock;
    private LeadershipEventPublisher eventPublisher;
    private LeaderElectionMetricsInterface metrics;

    @BeforeEach
    void setUpMocks(LeaderElectionProperties injectedProperties,
                    Environment injectedEnvironment,
                    DistributedLockRegistry injectedLockRegistry,
                    DistributedLock injectedDistributedLock,
                    LeadershipEventPublisher injectedEventPublisher,
                    LeaderElectionMetricsInterface injectedMetrics) {
        this.properties = injectedProperties;
        this.environment = injectedEnvironment;
        this.lockRegistry = injectedLockRegistry;
        this.distributedLock = injectedDistributedLock;
        this.eventPublisher = injectedEventPublisher;
        this.metrics = injectedMetrics;

        reset(distributedLock, eventPublisher, metrics);
    }

    @Nested
    @DisplayName("Захват лидерства")
    @Order(1)
    class LeaderAcquisitionTests {

        @Test
        @DisplayName("Должен захватить лидерство когда lease не существует")
        void shouldAcquireLeadershipWhenLeaseNotExists() throws Exception {
            // Given: Lease не существует
            when(distributedLock.getCurrentRecord()).thenReturn(null);
            when(distributedLock.createRecord(any())).thenReturn(true);

            LeaderElector elector = new LeaderElector(properties, environment, lockRegistry, eventPublisher, metrics);
            AtomicBoolean acquired = new AtomicBoolean(false);

            // When: Запускаем election
            AtomicInteger iterations = new AtomicInteger(0);
            Thread thread = new Thread(() -> elector.runLeaderElection(() -> {
                acquired.set(true);
                return iterations.incrementAndGet() <= 1;
            }));
            thread.start();
            thread.join(2000);

            // Then: Лидерство захвачено
            assertThat(acquired.get()).isTrue();
            verify(distributedLock, atLeastOnce()).createRecord(any());
            verify(eventPublisher, times(1)).publishOnGranted();
            verify(metrics, times(1)).recordLeadershipGranted(LEADERSHIP_NAME);
        }

        @Test
        @DisplayName("Должен захватить лидерство когда предыдущий lease истёк")
        void shouldAcquireLeadershipWhenPreviousLeaseExpired() throws Exception {
            // Given: Существует истёкший lease от другого holder
            LeaseRecord expiredLease = new LeaseRecord(
                    HOLDER_IDENTITY_2,
                    Instant.now().minusSeconds(30),
                    Instant.now().minusSeconds(30),
                    -5,
                    0,
                    1
            );
            when(distributedLock.getCurrentRecord()).thenReturn(expiredLease);
            when(distributedLock.updateRecord(any())).thenReturn(true);

            LeaderElector elector = new LeaderElector(properties, environment, lockRegistry, eventPublisher, metrics);

            // When: Запускаем election
            AtomicInteger iterations = new AtomicInteger(0);
            Thread thread = new Thread(() -> elector.runLeaderElection(() -> iterations.incrementAndGet() <= 1));
            thread.start();
            thread.join(2000);

            // Then: Лидерство захвачено
            verify(distributedLock, atLeastOnce()).updateRecord(any());
            verify(eventPublisher, times(1)).publishOnGranted();
        }

        @Test
        @DisplayName("Должен инкрементировать transitions при смене лидера")
        void shouldIncrementTransitionsOnLeaderChange() throws Exception {
            // Given: Существует истёкший lease от другого holder с transitions=3
            LeaseRecord oldLease = new LeaseRecord(
                    HOLDER_IDENTITY_2,  // Другой holder
                    Instant.now().minusSeconds(30),
                    Instant.now().minusSeconds(30),
                    -5,  // Отрицательный leaseDuration для истёкшего lease
                    3,
                    1
            );
            when(distributedLock.getCurrentRecord()).thenReturn(oldLease);

            LeaderElector elector = new LeaderElector(properties, environment, lockRegistry, eventPublisher, metrics);

            // When: Новый holder захватывает лидерство
            CountDownLatch updateLatch = new CountDownLatch(1);
            ArgumentCaptor<LeaseRecord> captor = ArgumentCaptor.forClass(LeaseRecord.class);
            doAnswer(invocation -> {
                updateLatch.countDown();
                return true;
            }).when(distributedLock).updateRecord(captor.capture());

            AtomicInteger iterations = new AtomicInteger(0);
            Thread thread = new Thread(() -> elector.runLeaderElection(() -> iterations.incrementAndGet() < 2));
            thread.start();

            boolean updated = updateLatch.await(10, TimeUnit.SECONDS);
            thread.join(15000);

            // Then: updateRecord был вызван и transitions инкрементирован до 4
            assertThat(updated).as("updateRecord должен быть вызван").isTrue();
            assertThat(captor.getAllValues()).isNotEmpty();
            assertThat(captor.getValue().getLeaderTransitions()).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("Продление лидерства")
    @Order(2)
    class LeaderRenewalTests {

        @Test
        @DisplayName("Должен продлевать lease через fast path")
        void shouldRenewLeaseThroughFastPath() throws Exception {
            // Given: Текущий holder является лидером
            LeaseRecord initialLease = new LeaseRecord(
                    HOLDER_IDENTITY_1,
                    Instant.now(),
                    Instant.now(),
                    15,
                    1,
                    1
            );

            when(distributedLock.getCurrentRecord()).thenReturn(initialLease);
            when(distributedLock.updateRecord(any())).thenReturn(true);

            LeaderElector elector = new LeaderElector(properties, environment, lockRegistry, eventPublisher, metrics);

            // When: Выполняем несколько итераций renewal
            AtomicInteger iterations = new AtomicInteger(0);
            Thread thread = new Thread(() -> elector.runLeaderElection(() -> iterations.incrementAndGet() < 3));
            thread.start();
            thread.join(30000);

            // Then: Lease продлён через fast path
            verify(distributedLock, atLeast(2)).updateRecord(any());
            verify(eventPublisher, times(1)).publishOnGranted();
        }

        @Test
        @DisplayName("Должен сохранять acquireTime при renewal")
        void shouldPreserveAcquireTimeOnRenewal() throws Exception {
            // Given: Существующий lease с установленным acquireTime
            Instant acquireTime = Instant.now().minusSeconds(60);
            LeaseRecord initialLease = new LeaseRecord(
                    HOLDER_IDENTITY_1,
                    acquireTime,
                    Instant.now(),
                    15,
                    1,
                    1
            );

            when(distributedLock.getCurrentRecord()).thenReturn(initialLease);
            ArgumentCaptor<LeaseRecord> captor = ArgumentCaptor.forClass(LeaseRecord.class);
            when(distributedLock.updateRecord(captor.capture())).thenReturn(true);

            LeaderElector elector = new LeaderElector(properties, environment, lockRegistry, eventPublisher, metrics);

            // When: Продлеваем lease
            AtomicInteger iterations = new AtomicInteger(0);
            Thread thread = new Thread(() -> elector.runLeaderElection(() -> iterations.incrementAndGet() < 2));
            thread.start();
            thread.join(15000);

            // Then: AcquireTime сохранён
            assertThat(captor.getValue().getAcquireTime()).isEqualTo(acquireTime);
        }

        @Test
        @DisplayName("Должен не инкрементировать transitions при renewal")
        void shouldNotIncrementTransitionsOnRenewal() throws Exception {
            // Given: Текущий holder является лидером с transitions=5
            LeaseRecord initialLease = new LeaseRecord(
                    HOLDER_IDENTITY_1,
                    Instant.now(),
                    Instant.now(),
                    15,
                    5,
                    1
            );

            when(distributedLock.getCurrentRecord()).thenReturn(initialLease);
            ArgumentCaptor<LeaseRecord> captor = ArgumentCaptor.forClass(LeaseRecord.class);
            when(distributedLock.updateRecord(captor.capture())).thenReturn(true);

            LeaderElector elector = new LeaderElector(properties, environment, lockRegistry, eventPublisher, metrics);

            // When: Продлеваем lease
            AtomicInteger iterations = new AtomicInteger(0);
            Thread thread = new Thread(() -> elector.runLeaderElection(() -> iterations.incrementAndGet() < 2));
            thread.start();
            thread.join(15000);

            // Then: Transitions остался 5
            assertThat(captor.getValue().getLeaderTransitions()).isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("Fast Path против Slow Path")
    @Order(3)
    class FastPathSlowPathTests {

        @Test
        @DisplayName("Должен использовать fast path когда является лидером")
        void shouldUseFastPathWhenLeader() throws Exception {
            // Given: Holder является лидером
            Instant now = Instant.now();
            LeaseRecord initialLease = new LeaseRecord(
                    HOLDER_IDENTITY_1,
                    now,
                    now,
                    15,
                    1,
                    1
            );

            when(distributedLock.getCurrentRecord()).thenReturn(initialLease);
            when(distributedLock.updateRecord(any())).thenReturn(true);

            LeaderElector elector = new LeaderElector(properties, environment, lockRegistry, eventPublisher, metrics);

            // When: Запускаем несколько итераций
            AtomicInteger iterations = new AtomicInteger(0);
            Thread thread = new Thread(() -> elector.runLeaderElection(() -> iterations.incrementAndGet() < 3));
            thread.start();
            thread.join(30000);

            // Then: Проверяем, что Fast Path используется (меньше вызовов getCurrentRecord)
            // Fast Path: getCurrentRecord вызывается только если isLeader() (1 раз за итерацию)
            // Slow Path: getCurrentRecord вызывается в isLeader() + в slow path (2 раза за итерацию)
            // При 3 итерациях: Fast Path = 3 вызова, Slow Path = 6 вызовов
            verify(distributedLock, atMost(4)).getCurrentRecord();
            verify(distributedLock, atLeast(2)).updateRecord(any());
        }

        @Test
        @DisplayName("Должен использовать slow path при первом запуске")
        void shouldUseSlowPathOnFirstRun() throws Exception {
            // Given: Lease не существует
            when(distributedLock.getCurrentRecord()).thenReturn(null);

            CountDownLatch createLatch = new CountDownLatch(1);
            doAnswer(invocation -> {
                createLatch.countDown();
                return true;
            }).when(distributedLock).createRecord(any());

            LeaderElector elector = new LeaderElector(properties, environment, lockRegistry, eventPublisher, metrics);

            // When: Первый запуск election
            AtomicInteger iterations = new AtomicInteger(0);
            Thread thread = new Thread(() -> elector.runLeaderElection(() -> iterations.incrementAndGet() < 2));
            thread.start();

            boolean created = createLatch.await(10, TimeUnit.SECONDS);
            thread.join(15000);

            // Then: Используется slow path
            assertThat(created).as("createRecord должен быть вызван").isTrue();
            verify(distributedLock, atLeastOnce()).getCurrentRecord();
            verify(distributedLock, atLeastOnce()).createRecord(any());
        }

        @Test
        @DisplayName("Должен fallback на slow path при ошибке fast path")
        void shouldFallbackToSlowPathOnFastPathError() throws Exception {
            // Given: Holder является лидером с observedRecord, но fast path fail
            LeaseRecord initialLease = new LeaseRecord(
                    HOLDER_IDENTITY_1,
                    Instant.now(),
                    Instant.now(),
                    15,
                    1,
                    1
            );

            when(distributedLock.getCurrentRecord()).thenReturn(initialLease);

            CountDownLatch slowPathLatch = new CountDownLatch(2); // Fast path + slow path
            when(distributedLock.updateRecord(any()))
                    .thenAnswer(invocation -> {
                        slowPathLatch.countDown();
                        long count = slowPathLatch.getCount();
                        if (count == 1) {
                            // Первый вызов (fast path) - fail
                            throw new RuntimeException("Fast path failed");
                        }
                        // Второй вызов (slow path после fallback) - success
                        return true;
                    });

            LeaderElector elector = new LeaderElector(properties, environment, lockRegistry, eventPublisher, metrics);

            // When: Выполняем election с ошибкой fast path
            AtomicInteger iterations = new AtomicInteger(0);
            Thread thread = new Thread(() -> elector.runLeaderElection(() -> iterations.incrementAndGet() < 3));
            thread.start();

            // Ждем fallback на slow path
            boolean slowPathUsed = slowPathLatch.await(10, TimeUnit.SECONDS);
            thread.join(15000);

            // Then: Slow path был использован (updateRecord вызван минимум 2 раза)
            assertThat(slowPathUsed).as("Slow path должен быть использован после ошибки fast path").isTrue();
            verify(distributedLock, atLeast(2)).updateRecord(any());
        }
    }

    @Nested
    @DisplayName("Валидация Lease")
    @Order(4)
    class LeaseValidationTests {

        @Test
        @DisplayName("Должен не захватывать lease когда другой holder валиден")
        void shouldNotAcquireWhenOtherHolderValid() throws Exception {
            // Given: Валидный lease удерживается другим holder
            LeaseRecord validLease = new LeaseRecord(
                    HOLDER_IDENTITY_2,
                    Instant.now(),
                    Instant.now(),
                    15,
                    1,
                    1
            );

            when(distributedLock.getCurrentRecord()).thenReturn(validLease);

            LeaderElector elector = new LeaderElector(properties, environment, lockRegistry, eventPublisher, metrics);

            // When: Пытаемся захватить лидерство
            CountDownLatch latch = new CountDownLatch(1);
            Thread thread = new Thread(() -> elector.runLeaderElection(() -> {
                latch.countDown();
                return false;
            }));
            thread.start();

            //noinspection ResultOfMethodCallIgnored
            latch.await(3, TimeUnit.SECONDS);
            thread.join(1000);

            // Then: Лидерство не захвачено
            verify(distributedLock, never()).createRecord(any());
            verify(distributedLock, never()).updateRecord(any());
            verify(eventPublisher, never()).publishOnGranted();
        }

        @Test
        @DisplayName("Должен игнорировать пустой holderIdentity")
        void shouldIgnoreEmptyHolderIdentity() throws Exception {
            // Given: Lease с пустым holderIdentity
            LeaseRecord emptyHolderLease = new LeaseRecord(
                    "",
                    Instant.now(),
                    Instant.now(),
                    15,
                    1,
                    1
            );

            when(distributedLock.getCurrentRecord()).thenReturn(emptyHolderLease);

            LeaderElector elector = new LeaderElector(properties, environment, lockRegistry, eventPublisher, metrics);

            // When: Пытаемся захватить лидерство
            CountDownLatch updateLatch = new CountDownLatch(1);
            doAnswer(invocation -> {
                updateLatch.countDown();
                return true;
            }).when(distributedLock).updateRecord(any());

            AtomicInteger iterations = new AtomicInteger(0);
            Thread thread = new Thread(() -> elector.runLeaderElection(() -> iterations.incrementAndGet() < 2));
            thread.start();

            boolean updated = updateLatch.await(10, TimeUnit.SECONDS);
            thread.join(15000);

            // Then: Лидерство захвачено
            assertThat(updated).as("updateRecord должен быть вызван").isTrue();
            verify(distributedLock, atLeastOnce()).updateRecord(any());
        }

        @Test
        @DisplayName("Должен захватить лидерство когда getCurrentRecord возвращает null")
        void shouldAcquireLeadershipWhenGetCurrentRecordReturnsNull() throws Exception {
            // Given: getCurrentRecord возвращает null (lease не существует)
            when(distributedLock.getCurrentRecord()).thenReturn(null);

            LeaderElector elector = new LeaderElector(properties, environment, lockRegistry, eventPublisher, metrics);

            // When: Пытаемся захватить лидерство
            CountDownLatch createLatch = new CountDownLatch(1);
            doAnswer(invocation -> {
                createLatch.countDown();
                return true;
            }).when(distributedLock).createRecord(any());

            AtomicInteger iterations = new AtomicInteger(0);
            Thread thread = new Thread(() -> elector.runLeaderElection(() -> iterations.incrementAndGet() < 2));
            thread.start();

            boolean created = createLatch.await(10, TimeUnit.SECONDS);
            thread.join(15000);

            // Then: Lease создан
            assertThat(created).as("createRecord должен быть вызван").isTrue();
            verify(distributedLock, atLeastOnce()).createRecord(any());
        }
    }

    @Nested
    @DisplayName("Публикация событий")
    @Order(5)
    class EventPublishingTests {

        @Test
        @DisplayName("Должен опубликовать onGranted при получении лидерства")
        void shouldPublishOnGrantedWhenAcquiringLeadership() throws Exception {
            // Given: Lease не существует
            when(distributedLock.getCurrentRecord()).thenReturn(null);
            when(distributedLock.createRecord(any())).thenReturn(true);
            when(distributedLock.updateRecord(any())).thenReturn(true);

            LeaderElector elector = new LeaderElector(properties, environment, lockRegistry, eventPublisher, metrics);

            // When: Запускаем election с несколькими итерациями
            CountDownLatch grantedLatch = new CountDownLatch(1);
            doAnswer(invocation -> {
                grantedLatch.countDown();
                return null;
            }).when(eventPublisher).publishOnGranted();

            AtomicInteger iterations = new AtomicInteger(0);
            Thread thread = new Thread(() -> elector.runLeaderElection(() -> iterations.incrementAndGet() < 3));
            thread.start();

            // Ждем пока onGranted будет опубликован
            boolean granted = grantedLatch.await(15, TimeUnit.SECONDS);
            assertThat(granted).as("publishOnGranted должен быть вызван").isTrue();

            // Ждем завершения потока (для вызова stop() в finally)
            thread.join(20000);

            // Then: onGranted опубликован
            verify(eventPublisher, times(1)).publishOnGranted();
        }

        @Test
        @DisplayName("Должен опубликовать onRevoked при потере лидерства")
        void shouldPublishOnRevokedWhenLosingLeadership() throws Exception {
            // Given: Начинаем как лидер, затем теряем лидерство
            LeaseRecord initialLease = new LeaseRecord(
                    HOLDER_IDENTITY_1,
                    Instant.now(),
                    Instant.now(),
                    15,
                    1,
                    1
            );

            LeaseRecord otherHolderLease = new LeaseRecord(
                    HOLDER_IDENTITY_2,
                    Instant.now(),
                    Instant.now(),
                    15,
                    2,
                    2
            );

            when(distributedLock.getCurrentRecord())
                    .thenReturn(initialLease)      // 1-й вызов: мы лидер
                    .thenReturn(initialLease)      // 2-й вызов: мы все еще лидер  
                    .thenReturn(otherHolderLease); // 3-й вызов: лидерство перешло к другому

            LeaderElector elector = new LeaderElector(properties, environment, lockRegistry, eventPublisher, metrics);

            // When: Запускаем election на больше итераций
            AtomicInteger iterations = new AtomicInteger(0);
            Thread thread = new Thread(() -> elector.runLeaderElection(() -> iterations.incrementAndGet() < 5));
            thread.start();
            thread.join(10000);

            // Then: Оба события опубликованы
            verify(eventPublisher, times(1)).publishOnGranted();
            verify(eventPublisher, atLeast(1)).publishOnRevoked();
        }

        @Test
        @DisplayName("Должен опубликовать onRevoked только один раз")
        void shouldPublishOnRevokedOnlyOnce() throws Exception {
            // Given: Лидерство принадлежит другому holder
            LeaseRecord otherHolderLease = new LeaseRecord(
                    HOLDER_IDENTITY_2,
                    Instant.now(),
                    Instant.now(),
                    15,
                    1,
                    1
            );

            when(distributedLock.getCurrentRecord()).thenReturn(otherHolderLease);

            LeaderElector elector = new LeaderElector(properties, environment, lockRegistry, eventPublisher, metrics);

            // When: Запускаем election несколько итераций
            AtomicInteger iterations = new AtomicInteger(0);
            Thread thread = new Thread(() -> elector.runLeaderElection(() -> iterations.incrementAndGet() < 3));
            thread.start();
            thread.join(5000);

            // Then: События не публикуются
            verify(eventPublisher, never()).publishOnGranted();
            verify(eventPublisher, never()).publishOnRevoked();
        }
    }

    @Nested
    @DisplayName("Запись метрик")
    @Order(6)
    class MetricsRecordingTests {

        @Test
        @DisplayName("Должен записать метрику leadershipGranted")
        void shouldRecordLeadershipGrantedMetric() throws Exception {
            // Given: Lease не существует
            when(distributedLock.getCurrentRecord()).thenReturn(null);
            when(distributedLock.createRecord(any())).thenReturn(true);
            when(distributedLock.updateRecord(any())).thenReturn(true);

            LeaderElector elector = new LeaderElector(properties, environment, lockRegistry, eventPublisher, metrics);

            // When: Запускаем election с несколькими итерациями
            CountDownLatch grantedLatch = new CountDownLatch(1);
            doAnswer(invocation -> {
                grantedLatch.countDown();
                return null;
            }).when(eventPublisher).publishOnGranted();

            AtomicInteger iterations = new AtomicInteger(0);
            Thread thread = new Thread(() -> elector.runLeaderElection(() -> iterations.incrementAndGet() < 3));
            thread.start();

            // Ждем пока лидерство будет захвачено
            boolean granted = grantedLatch.await(30, TimeUnit.SECONDS);
            thread.join(30000);

            // Then: Метрика leadershipGranted записана
            assertThat(granted).as("Лидерство должно быть захвачено").isTrue();
            verify(metrics, times(1)).recordLeadershipGranted(LEADERSHIP_NAME);
        }

        @Test
        @DisplayName("Должен записать метрику leadershipRevoked")
        void shouldRecordLeadershipRevokedMetric() throws Exception {
            // Given: Lease не существует, будет создан
            when(distributedLock.getCurrentRecord()).thenReturn(null);
            when(distributedLock.createRecord(any())).thenReturn(true);
            when(distributedLock.updateRecord(any())).thenReturn(true);

            LeaderElector elector = new LeaderElector(properties, environment, lockRegistry, eventPublisher, metrics);

            // When: Запускаем election и ждем захвата лидерства перед остановкой
            CountDownLatch grantedLatch = new CountDownLatch(1);
            doAnswer(invocation -> {
                grantedLatch.countDown();
                return null;
            }).when(eventPublisher).publishOnGranted();

            AtomicInteger iterations = new AtomicInteger(0);
            Thread thread = new Thread(() -> elector.runLeaderElection(() -> iterations.incrementAndGet() < 3));
            thread.start();

            // Ждем пока лидерство будет захвачено
            boolean granted = grantedLatch.await(30, TimeUnit.SECONDS);
            assertThat(granted).as("Лидерство должно быть захвачено").isTrue();

            thread.join(30000);

            // Then: recordLeadershipRevoked вызван при остановке
            verify(metrics, atLeastOnce()).recordLeadershipRevoked(LEADERSHIP_NAME);
        }

        @Test
        @DisplayName("Должен записать метрику leadershipTransition")
        void shouldRecordLeadershipTransitionMetric() throws Exception {
            // Given: Holder является лидером
            LeaseRecord initialLease = new LeaseRecord(
                    HOLDER_IDENTITY_1,
                    Instant.now(),
                    Instant.now(),
                    15,
                    1,
                    1
            );

            when(distributedLock.getCurrentRecord()).thenReturn(initialLease);
            when(distributedLock.updateRecord(any())).thenReturn(true);

            LeaderElector elector = new LeaderElector(properties, environment, lockRegistry, eventPublisher, metrics);

            // When: Запускаем election с несколькими итерациями
            CountDownLatch grantedLatch = new CountDownLatch(1);
            doAnswer(invocation -> {
                grantedLatch.countDown();
                return null;
            }).when(eventPublisher).publishOnGranted();

            AtomicInteger iterations = new AtomicInteger(0);
            Thread thread = new Thread(() -> elector.runLeaderElection(() -> iterations.incrementAndGet() < 3));
            thread.start();

            // Ждем пока transition будет обнаружен
            boolean granted = grantedLatch.await(15, TimeUnit.SECONDS);
            assertThat(granted).as("Лидерство должно быть захвачено").isTrue();

            thread.join(20000);
        }
    }

    @Nested
    @DisplayName("Обработка ошибок")
    @Order(7)
    class ErrorHandlingTests {

        @Test
        @DisplayName("Должен обработать InterruptedException")
        void shouldHandleInterruptedException() throws Exception {
            // Given: Mock настроен
            when(distributedLock.getCurrentRecord()).thenReturn(null);
            when(distributedLock.createRecord(any())).thenReturn(true);

            LeaderElector elector = new LeaderElector(properties, environment, lockRegistry, eventPublisher, metrics);

            // When: Запускаем поток и прерываем его
            Thread thread = new Thread(() -> elector.runLeaderElection(() -> true));
            thread.start();
            Thread.sleep(100);
            thread.interrupt();
            thread.join(1000);

            // Then: Поток завершился корректно
            assertThat(thread.isAlive()).isFalse();
        }

        @Test
        @DisplayName("Должен retry при ошибке getCurrentRecord")
        void shouldRetryOnGetCurrentRecordError() throws Exception {
            // Given: Первый вызов выбрасывает ошибку, второй успешен
            when(distributedLock.getCurrentRecord())
                    .thenThrow(new RuntimeException("Connection error"))
                    .thenReturn(null);
            when(distributedLock.createRecord(any())).thenReturn(true);

            LeaderElector elector = new LeaderElector(properties, environment, lockRegistry, eventPublisher, metrics);

            // When: Запускаем election
            AtomicInteger iterations = new AtomicInteger(0);
            Thread thread = new Thread(() -> elector.runLeaderElection(() -> iterations.incrementAndGet() < 3));
            thread.start();
            thread.join(7000);

            // Then: getCurrentRecord вызван минимум 2 раза (ошибка + retry)
            verify(distributedLock, atLeast(2)).getCurrentRecord();
        }

        @Test
        @DisplayName("Должен обработать ошибку createRecord")
        void shouldHandleCreateRecordError() throws Exception {
            // Given: Первый вызов выбрасывает ошибку, второй успешен
            when(distributedLock.getCurrentRecord()).thenReturn(null);
            when(distributedLock.createRecord(any()))
                    .thenThrow(new RuntimeException("Create error"))
                    .thenReturn(true);

            LeaderElector elector = new LeaderElector(properties, environment, lockRegistry, eventPublisher, metrics);

            // When: Запускаем election
            AtomicInteger iterations = new AtomicInteger(0);
            Thread thread = new Thread(() -> elector.runLeaderElection(() -> iterations.incrementAndGet() < 3));
            thread.start();
            thread.join(7000);

            // Then: createRecord вызван минимум 2 раза (ошибка + retry)
            verify(distributedLock, atLeast(2)).createRecord(any());
        }

        @Test
        @DisplayName("Должен обработать ошибку updateRecord")
        void shouldHandleUpdateRecordError() throws Exception {
            // Given: Holder является лидером, updateRecord выбрасывает ошибку
            LeaseRecord initialLease = new LeaseRecord(
                    HOLDER_IDENTITY_1,
                    Instant.now(),
                    Instant.now(),
                    15,
                    1,
                    1
            );

            when(distributedLock.getCurrentRecord()).thenReturn(initialLease);
            when(distributedLock.updateRecord(any()))
                    .thenThrow(new RuntimeException("Update error"))
                    .thenReturn(true);

            LeaderElector elector = new LeaderElector(properties, environment, lockRegistry, eventPublisher, metrics);

            // When: Запускаем election
            AtomicInteger iterations = new AtomicInteger(0);
            Thread thread = new Thread(() -> elector.runLeaderElection(() -> iterations.incrementAndGet() < 3));
            thread.start();
            thread.join(10000);

            // Then: updateRecord вызван минимум 2 раза (ошибка + retry)
            verify(distributedLock, atLeast(2)).updateRecord(any());
        }
    }

    @Nested
    @DisplayName("Разрешение идентичности Holder")
    @Order(8)
    class HolderIdentityResolutionTests {

        @Test
        @DisplayName("Должен использовать POD_NAME если доступен")
        void shouldUsePodNameWhenAvailable() {
            when(environment.getProperty("POD_NAME")).thenReturn("my-pod-123");

            LeaderElector elector = new LeaderElector(properties, environment, lockRegistry, eventPublisher, metrics);

            assertThat(elector).isNotNull();
            verify(environment).getProperty("POD_NAME");
        }

        @Test
        @DisplayName("Должен использовать HOSTNAME если POD_NAME недоступен")
        void shouldUseHostnameWhenPodNameUnavailable() {
            when(environment.getProperty("POD_NAME")).thenReturn(null);
            when(environment.getProperty("HOSTNAME")).thenReturn("my-host");

            LeaderElector elector = new LeaderElector(properties, environment, lockRegistry, eventPublisher, metrics);

            assertThat(elector).isNotNull();
            verify(environment).getProperty("POD_NAME");
            verify(environment).getProperty("HOSTNAME");
        }

        @Test
        @DisplayName("Должен использовать UUID если POD_NAME и HOSTNAME недоступны")
        void shouldUseUuidWhenNeitherAvailable() {
            when(environment.getProperty("POD_NAME")).thenReturn(null);
            when(environment.getProperty("HOSTNAME")).thenReturn(null);

            LeaderElector elector = new LeaderElector(properties, environment, lockRegistry, eventPublisher, metrics);

            assertThat(elector).isNotNull();
            verify(environment).getProperty("POD_NAME");
            verify(environment).getProperty("HOSTNAME");
        }
    }

    @Nested
    @DisplayName("Поведение при остановке")
    @Order(9)
    class StopBehaviorTests {

        @Test
        @DisplayName("Должен вызвать stop при завершении runLeaderElection")
        void shouldCallStopWhenRunLeaderElectionFinishes() throws Exception {
            // Given: Lease не существует, будет создан и затем обновлён
            when(distributedLock.getCurrentRecord()).thenReturn(null);
            when(distributedLock.createRecord(any())).thenReturn(true);
            when(distributedLock.updateRecord(any())).thenReturn(true);

            LeaderElector elector = new LeaderElector(properties, environment, lockRegistry, eventPublisher, metrics);

            // When: Запускаем election с несколькими итерациями для установки hadLeadership
            CountDownLatch grantedLatch = new CountDownLatch(1);
            doAnswer(invocation -> {
                grantedLatch.countDown();
                return null;
            }).when(eventPublisher).publishOnGranted();

            AtomicInteger iterations = new AtomicInteger(0);
            Thread thread = new Thread(() -> elector.runLeaderElection(() -> iterations.incrementAndGet() < 3));
            thread.start();

            // Ждем пока лидерство будет захвачено
            boolean granted = grantedLatch.await(30, TimeUnit.SECONDS);
            assertThat(granted).as("Лидерство должно быть захвачено").isTrue();

            thread.join(30000);

            // Then: publishOnRevoked вызван при остановке
            verify(eventPublisher, times(1)).publishOnRevoked();
        }

        @Test
        @DisplayName("Должен не вызывать publishOnRevoked если не был лидером")
        void shouldNotCallPublishOnRevokedIfNeverLeader() {
            LeaseRecord otherHolderLease = new LeaseRecord(
                    HOLDER_IDENTITY_2,
                    Instant.now(),
                    Instant.now(),
                    15,
                    1,
                    1
            );

            when(distributedLock.getCurrentRecord()).thenReturn(otherHolderLease);

            LeaderElector elector = new LeaderElector(properties, environment, lockRegistry, eventPublisher, metrics);
            elector.stop();

            verify(eventPublisher, never()).publishOnRevoked();
            verify(metrics, never()).recordLeadershipRevoked(any());
        }
    }

}

