package space.br1440.platform.leaderelection.processor;

import org.junit.jupiter.api.*;
import space.br1440.platform.leaderelection.annotation.locking.OnLockAcquired;
import space.br1440.platform.leaderelection.annotation.locking.OnLockReleased;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Тесты LockHookProcessor")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LockHookProcessorTest {

    private static final String LOCK_NAME = "test-lock";
    private static final String BEAN_NAME = "testBean";

    private LockHookProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new LockHookProcessor();
    }

    @AfterEach
    void tearDown() {
        if (processor != null) {
            processor.destroy();
        }
    }

    @Nested
    @DisplayName("Hook Registration")
    @Order(1)
    class HookRegistrationTests {

        @Test
        @DisplayName("Должен зарегистрировать @OnLockAcquired хук")
        void shouldRegisterOnLockAcquiredHook() {
            // Given
            TestBeans.BeanWithOnAcquiredHook bean = new TestBeans.BeanWithOnAcquiredHook();

            // When
            processor.postProcessAfterInitialization(bean, BEAN_NAME);

            // Then
            processor.executeOnLockAcquiredHooks(LOCK_NAME);
            assertThat(bean.acquiredCallCount.get())
                    .as("Хук должен быть вызван")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("Должен зарегистрировать @OnLockReleased хук")
        void shouldRegisterOnLockReleasedHook() {
            // Given
            TestBeans.BeanWithOnReleasedHook bean = new TestBeans.BeanWithOnReleasedHook();

            // When
            processor.postProcessAfterInitialization(bean, BEAN_NAME);

            // Then
            processor.executeOnLockReleasedHooks(LOCK_NAME);
            assertThat(bean.releasedCallCount.get())
                    .as("Хук должен быть вызван")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("Должен зарегистрировать несколько хуков для одного lockName")
        void shouldRegisterMultipleHooksForSameLockName() {
            // Given
            TestBeans.BeanWithMultipleHooks bean = new TestBeans.BeanWithMultipleHooks();

            // When
            processor.postProcessAfterInitialization(bean, BEAN_NAME);

            // Then
            processor.executeOnLockAcquiredHooks(LOCK_NAME);
            assertThat(bean.hook1CallCount.get()).isEqualTo(1);
            assertThat(bean.hook2CallCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("Должен игнорировать бины без хуков")
        void shouldIgnoreBeansWithoutHooks() {
            // Given
            TestBeans.BeanWithoutHooks bean = new TestBeans.BeanWithoutHooks();

            // When
            Object result = processor.postProcessAfterInitialization(bean, BEAN_NAME);

            // Then
            assertThat(result).isSameAs(bean);
        }
    }

    @Nested
    @DisplayName("Hook Execution Order")
    @Order(2)
    class HookExecutionOrderTests {

        @Test
        @DisplayName("Должен выполнять синхронные хуки в порядке order")
        void shouldExecuteSyncHooksInOrderSpecifiedByOrderAttribute() {
            // Given
            TestBeans.BeanWithOrderedSyncHooks bean = new TestBeans.BeanWithOrderedSyncHooks();

            // When
            processor.postProcessAfterInitialization(bean, BEAN_NAME);
            processor.executeOnLockAcquiredHooks(LOCK_NAME);

            // Then
            assertThat(bean.executionOrder)
                    .as("Хуки должны быть выполнены в порядке order (1, 2, 3)")
                    .containsExactly(1, 2, 3);
        }

        @Test
        @DisplayName("Должен выполнять синхронные хуки перед асинхронными")
        void shouldExecuteSyncHooksBeforeAsyncHooks() throws InterruptedException {
            // Given
            TestBeans.BeanWithSyncAndAsyncHooks bean = new TestBeans.BeanWithSyncAndAsyncHooks();

            // When
            processor.postProcessAfterInitialization(bean, BEAN_NAME);
            processor.executeOnLockAcquiredHooks(LOCK_NAME);

            // Then
            assertThat(bean.syncCallCount.get()).isEqualTo(1);

            // And
            boolean asyncCompleted = bean.asyncLatch.await(3, TimeUnit.SECONDS);
            assertThat(asyncCompleted).isTrue();
            assertThat(bean.asyncCallCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("Должен выполнять асинхронные хуки в порядке order")
        void shouldExecuteAsyncHooksInOrderSpecifiedByOrderAttribute() throws InterruptedException {
            // Given
            TestBeans.BeanWithOrderedAsyncHooks bean = new TestBeans.BeanWithOrderedAsyncHooks();

            // When
            processor.postProcessAfterInitialization(bean, BEAN_NAME);
            processor.executeOnLockAcquiredHooks(LOCK_NAME);

            // Then
            boolean allCompleted = bean.allAsyncCompletedLatch.await(5, TimeUnit.SECONDS);
            assertThat(allCompleted).isTrue();
            assertThat(bean.executionOrder).hasSize(3);
        }
    }

    @Nested
    @DisplayName("Async Execution")
    @Order(3)
    class AsyncExecutionTests {

        @Test
        @DisplayName("Должен выполнять async=true хуки асинхронно")
        void shouldExecuteAsyncHooksAsynchronously() throws InterruptedException {
            // Given
            TestBeans.BeanWithAsyncHook bean = new TestBeans.BeanWithAsyncHook();

            // When
            processor.postProcessAfterInitialization(bean, BEAN_NAME);

            long startTime = System.currentTimeMillis();
            processor.executeOnLockAcquiredHooks(LOCK_NAME);
            long executionTime = System.currentTimeMillis() - startTime;

            // Then
            assertThat(executionTime)
                    .as("Асинхронный хук не должен блокировать вызывающий поток")
                    .isLessThan(500);

            // And
            boolean completed = bean.asyncLatch.await(3, TimeUnit.SECONDS);
            assertThat(completed).isTrue();
            assertThat(bean.callCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("Должен ждать завершения async хуков при OnLockReleased")
        void shouldWaitForAsyncHooksCompletionOnLockReleased() throws InterruptedException {
            // Given
            TestBeans.BeanWithAsyncReleasedHook bean = new TestBeans.BeanWithAsyncReleasedHook();

            // When
            processor.postProcessAfterInitialization(bean, BEAN_NAME);
            processor.executeOnLockReleasedHooks(LOCK_NAME);

            // Then
            assertThat(bean.callCount.get())
                    .as("Асинхронный OnLockReleased хук должен быть выполнен")
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Error Handling")
    @Order(4)
    class ErrorHandlingTests {

        @Test
        @DisplayName("Должен логировать ошибку но продолжать выполнение других хуков при исключении")
        void shouldLogErrorButContinueExecutingOtherHooksOnException() {
            // Given
            TestBeans.BeanWithFailingAndNormalHooks bean = new TestBeans.BeanWithFailingAndNormalHooks();

            // When
            processor.postProcessAfterInitialization(bean, BEAN_NAME);
            processor.executeOnLockAcquiredHooks(LOCK_NAME);

            // Then
            assertThat(bean.normalHookCallCount.get())
                    .as("Нормальный хук должен быть выполнен несмотря на ошибку")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("Должен обрабатывать исключения в асинхронных хуках")
        void shouldHandleExceptionsInAsyncHooks() throws InterruptedException {
            // Given
            TestBeans.BeanWithFailingAndNormalAsyncHooks bean = new TestBeans.BeanWithFailingAndNormalAsyncHooks();

            // When
            processor.postProcessAfterInitialization(bean, BEAN_NAME);
            processor.executeOnLockAcquiredHooks(LOCK_NAME);

            // Then
            boolean completed = bean.normalAsyncLatch.await(3, TimeUnit.SECONDS);
            assertThat(completed).isTrue();
            assertThat(bean.normalAsyncCallCount.get()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Empty Hooks")
    @Order(5)
    class EmptyHooksTests {

        @Test
        @DisplayName("Должен корректно обрабатывать отсутствие хуков для lockName")
        void shouldHandleNoHooksForLockName() {
            processor.executeOnLockAcquiredHooks("non-existent-lock");
            processor.executeOnLockReleasedHooks("non-existent-lock");
        }

        @Test
        @DisplayName("Должен корректно обрабатывать пустой список хуков")
        void shouldHandleEmptyHooksList() {
            // Given
            TestBeans.BeanWithOnAcquiredHook bean = new TestBeans.BeanWithOnAcquiredHook();
            processor.postProcessAfterInitialization(bean, BEAN_NAME);

            // When
            processor.executeOnLockAcquiredHooks("different-lock");

            // Then
            assertThat(bean.acquiredCallCount.get()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Multiple Beans")
    @Order(6)
    class MultipleBeansTests {

        @Test
        @DisplayName("Должен выполнять хуки из разных beans для одного lockName")
        void shouldExecuteHooksFromMultipleBeansForSameLockName() {
            // Given
            TestBeans.BeanWithOnAcquiredHook bean1 = new TestBeans.BeanWithOnAcquiredHook();
            TestBeans.AnotherBeanWithOnAcquiredHook bean2 = new TestBeans.AnotherBeanWithOnAcquiredHook();

            // When
            processor.postProcessAfterInitialization(bean1, "bean1");
            processor.postProcessAfterInitialization(bean2, "bean2");
            processor.executeOnLockAcquiredHooks(LOCK_NAME);

            // Then: Хуки из обоих bean'ов выполнены
            assertThat(bean1.acquiredCallCount.get()).isEqualTo(1);
            assertThat(bean2.acquiredCallCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("Должен изолировать хуки для разных lockName")
        void shouldIsolateHooksForDifferentLockNames() {
            // Given
            TestBeans.BeanWithHooksForDifferentLocks bean = new TestBeans.BeanWithHooksForDifferentLocks();

            // When
            processor.postProcessAfterInitialization(bean, BEAN_NAME);

            // Then: Выполнение хуков для одного lockName не влияет на другой
            processor.executeOnLockAcquiredHooks("lock-1");
            assertThat(bean.lock1CallCount.get()).isEqualTo(1);
            assertThat(bean.lock2CallCount.get()).isEqualTo(0);

            processor.executeOnLockAcquiredHooks("lock-2");
            assertThat(bean.lock1CallCount.get()).isEqualTo(1);
            assertThat(bean.lock2CallCount.get()).isEqualTo(1);
        }
    }

    static class TestBeans {

        static class BeanWithOnAcquiredHook {
            AtomicInteger acquiredCallCount = new AtomicInteger(0);

            @OnLockAcquired(lockName = LOCK_NAME)
            public void onAcquired() {
                acquiredCallCount.incrementAndGet();
            }
        }

        static class BeanWithOnReleasedHook {
            AtomicInteger releasedCallCount = new AtomicInteger(0);

            @OnLockReleased(lockName = LOCK_NAME)
            public void onReleased() {
                releasedCallCount.incrementAndGet();
            }
        }

        static class BeanWithMultipleHooks {
            AtomicInteger hook1CallCount = new AtomicInteger(0);
            AtomicInteger hook2CallCount = new AtomicInteger(0);

            @OnLockAcquired(lockName = LOCK_NAME)
            public void hook1() {
                hook1CallCount.incrementAndGet();
            }

            @OnLockAcquired(lockName = LOCK_NAME)
            public void hook2() {
                hook2CallCount.incrementAndGet();
            }
        }

        static class BeanWithoutHooks {
            public void regularMethod() {
            }
        }

        static class BeanWithOrderedSyncHooks {
            java.util.List<Integer> executionOrder = new java.util.concurrent.CopyOnWriteArrayList<>();

            @OnLockAcquired(lockName = LOCK_NAME, order = 2)
            public void hook2() {
                executionOrder.add(2);
            }

            @OnLockAcquired(lockName = LOCK_NAME, order = 1)
            public void hook1() {
                executionOrder.add(1);
            }

            @OnLockAcquired(lockName = LOCK_NAME, order = 3)
            public void hook3() {
                executionOrder.add(3);
            }
        }

        static class BeanWithSyncAndAsyncHooks {
            AtomicInteger syncCallCount = new AtomicInteger(0);
            AtomicInteger asyncCallCount = new AtomicInteger(0);
            CountDownLatch asyncLatch = new CountDownLatch(1);

            @OnLockAcquired(lockName = LOCK_NAME, async = false)
            public void syncHook() {
                syncCallCount.incrementAndGet();
            }

            @OnLockAcquired(lockName = LOCK_NAME, async = true)
            public void asyncHook() {
                asyncCallCount.incrementAndGet();
                asyncLatch.countDown();
            }
        }

        static class BeanWithOrderedAsyncHooks {
            java.util.List<Integer> executionOrder = new java.util.concurrent.CopyOnWriteArrayList<>();
            CountDownLatch allAsyncCompletedLatch = new CountDownLatch(3);

            @OnLockAcquired(lockName = LOCK_NAME, order = 2, async = true)
            public void asyncHook2() {
                executionOrder.add(2);
                allAsyncCompletedLatch.countDown();
            }

            @OnLockAcquired(lockName = LOCK_NAME, order = 1, async = true)
            public void asyncHook1() {
                executionOrder.add(1);
                allAsyncCompletedLatch.countDown();
            }

            @OnLockAcquired(lockName = LOCK_NAME, order = 3, async = true)
            public void asyncHook3() {
                executionOrder.add(3);
                allAsyncCompletedLatch.countDown();
            }
        }

        static class BeanWithAsyncHook {
            AtomicInteger callCount = new AtomicInteger(0);
            CountDownLatch asyncLatch = new CountDownLatch(1);

            @OnLockAcquired(lockName = LOCK_NAME, async = true)
            public void asyncHook() {
                try {
                    Thread.sleep(100); // Имитация работы
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                callCount.incrementAndGet();
                asyncLatch.countDown();
            }
        }

        static class BeanWithAsyncReleasedHook {
            AtomicInteger callCount = new AtomicInteger(0);

            @OnLockReleased(lockName = LOCK_NAME, async = true)
            public void asyncReleasedHook() {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                callCount.incrementAndGet();
            }
        }

        static class BeanWithFailingAndNormalHooks {
            AtomicInteger normalHookCallCount = new AtomicInteger(0);

            @OnLockAcquired(lockName = LOCK_NAME, order = 1)
            public void failingHook() {
                throw new RuntimeException("Test exception");
            }

            @OnLockAcquired(lockName = LOCK_NAME, order = 2)
            public void normalHook() {
                normalHookCallCount.incrementAndGet();
            }
        }

        static class BeanWithFailingAndNormalAsyncHooks {
            AtomicInteger normalAsyncCallCount = new AtomicInteger(0);
            CountDownLatch normalAsyncLatch = new CountDownLatch(1);

            @OnLockAcquired(lockName = LOCK_NAME, order = 1, async = true)
            public void failingAsyncHook() {
                throw new RuntimeException("Test async exception");
            }

            @OnLockAcquired(lockName = LOCK_NAME, order = 2, async = true)
            public void normalAsyncHook() {
                normalAsyncCallCount.incrementAndGet();
                normalAsyncLatch.countDown();
            }
        }

        static class AnotherBeanWithOnAcquiredHook {
            AtomicInteger acquiredCallCount = new AtomicInteger(0);

            @OnLockAcquired(lockName = LOCK_NAME)
            public void onAcquired() {
                acquiredCallCount.incrementAndGet();
            }
        }

        static class BeanWithHooksForDifferentLocks {
            AtomicInteger lock1CallCount = new AtomicInteger(0);
            AtomicInteger lock2CallCount = new AtomicInteger(0);

            @OnLockAcquired(lockName = "lock-1")
            public void onLock1Acquired() {
                lock1CallCount.incrementAndGet();
            }

            @OnLockAcquired(lockName = "lock-2")
            public void onLock2Acquired() {
                lock2CallCount.incrementAndGet();
            }
        }
    }

}
