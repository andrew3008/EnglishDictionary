package space.br1440.platform.leaderelection.processor;

import org.junit.jupiter.api.*;
import org.springframework.core.env.Environment;
import space.br1440.platform.leaderelection.annotation.leadership.LeaderAware;
import space.br1440.platform.leaderelection.configuration.LeaderElectionProperties;
import space.br1440.platform.leaderelection.event.OnLeaderGrantedEvent;
import space.br1440.platform.leaderelection.event.OnLeaderRevokedEvent;
import space.br1440.platform.leaderelection.leaderelection.model.LeaderInfo;
import space.br1440.platform.leaderelection.leaderelection.model.LeaderRegistry;
import space.br1440.platform.leaderelection.processor.exception.LeaderAwareConfigurationException;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Тесты LeaderAwareProcessor")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LeaderAwareProcessorTest {

    private static final String LEADERSHIP_NAME = "test-leadership";
    private static final String BEAN_NAME = "testBean";

    private LeaderRegistry leaderRegistry;
    private LeaderAwareProcessor processor;

    @BeforeEach
    void setUp() {
        leaderRegistry = mock(LeaderRegistry.class);
        Environment environment = mock(Environment.class);
        LeaderElectionProperties properties = createProperties();
        processor = new LeaderAwareProcessor(leaderRegistry, environment, properties);
    }

    @AfterEach
    void tearDown() {
        if (processor != null) {
            processor.destroy();
        }
    }

    @Nested
    @DisplayName("Boolean Field Injection")
    @Order(1)
    class BooleanFieldInjectionTests {

        @Test
        @DisplayName("Должен инжектировать boolean поле и установить начальное значение")
        void shouldInjectBooleanFieldWithInitialValue() {
            // Given
            TestBeans.TestBeanWithBooleanField bean = new TestBeans.TestBeanWithBooleanField();
            LeaderInfo leaderInfo = createLeaderInfo(true);
            when(leaderRegistry.getLeaderInfo(LEADERSHIP_NAME)).thenReturn(leaderInfo);

            // When
            processor.postProcessBeforeInitialization(bean, BEAN_NAME);

            // Then
            assertThat(bean.isLeader)
                    .as("Поле должно быть установлено в true")
                    .isTrue();
        }

        @Test
        @DisplayName("Должен обновить boolean поле при получении лидерства")
        void shouldUpdateBooleanFieldOnLeaderGranted() {
            // Given
            TestBeans.TestBeanWithBooleanField bean = new TestBeans.TestBeanWithBooleanField();
            LeaderInfo leaderInfo = createLeaderInfo(false);
            when(leaderRegistry.getLeaderInfo(LEADERSHIP_NAME)).thenReturn(leaderInfo);

            processor.postProcessBeforeInitialization(bean, BEAN_NAME);
            assertThat(bean.isLeader).isFalse();

            // When
            processor.onApplicationEvent(new OnLeaderGrantedEvent(this, LEADERSHIP_NAME));

            // Then
            assertThat(bean.isLeader)
                    .as("Поле должно быть обновлено до true")
                    .isTrue();
        }

        @Test
        @DisplayName("Должен обновить boolean поле при потере лидерства")
        void shouldUpdateBooleanFieldOnLeaderRevoked() {
            // Given
            TestBeans.TestBeanWithBooleanField bean = new TestBeans.TestBeanWithBooleanField();
            LeaderInfo leaderInfo = createLeaderInfo(true);
            when(leaderRegistry.getLeaderInfo(LEADERSHIP_NAME)).thenReturn(leaderInfo);

            processor.postProcessBeforeInitialization(bean, BEAN_NAME);
            assertThat(bean.isLeader).isTrue();

            // When
            processor.onApplicationEvent(new OnLeaderRevokedEvent(this, LEADERSHIP_NAME));

            // Then
            assertThat(bean.isLeader)
                    .as("Поле должно быть обновлено до false")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("LeaderInfo Field Injection")
    @Order(2)
    class LeaderInfoFieldInjectionTests {

        @Test
        @DisplayName("Должен инжектировать LeaderInfo поле с AsyncLeaderInfoProxy")
        void shouldInjectLeaderInfoFieldWithProxy() {
            // Given
            TestBeans.TestBeanWithLeaderInfoField bean = new TestBeans.TestBeanWithLeaderInfoField();
            LeaderInfo leaderInfo = createLeaderInfo(true);
            when(leaderRegistry.getLeaderInfo(LEADERSHIP_NAME)).thenReturn(leaderInfo);

            // When
            processor.postProcessBeforeInitialization(bean, BEAN_NAME);

            // Then
            assertThat(bean.leaderInfo)
                    .as("LeaderInfo поле должно быть установлено")
                    .isNotNull();
            assertThat(bean.leaderInfo.getLeadershipName())
                    .isEqualTo(LEADERSHIP_NAME);
        }

        @Test
        @DisplayName("Должен использовать кастомный TTL для LeaderInfo поля")
        void shouldUseCustomTtlForLeaderInfoField() {
            // Given
            TestBeans.TestBeanWithCustomTtlLeaderInfoField bean = new TestBeans.TestBeanWithCustomTtlLeaderInfoField();
            LeaderInfo leaderInfo = createLeaderInfo(false);
            when(leaderRegistry.getLeaderInfo(LEADERSHIP_NAME)).thenReturn(leaderInfo);

            // When
            processor.postProcessBeforeInitialization(bean, BEAN_NAME);

            // Then
            assertThat(bean.leaderInfoWithCustomTtl)
                    .as("LeaderInfo с кастомным TTL должно быть установлено")
                    .isNotNull();
        }
    }

    @Nested
    @DisplayName("Method Injection")
    @Order(3)
    class MethodInjectionTests {

        @Test
        @DisplayName("Должен вызвать метод без параметров при получении лидерства")
        void shouldInvokeNoArgMethodOnLeaderGranted() throws InterruptedException {
            // Given
            TestBeans.TestBeanWithNoArgMethod bean = new TestBeans.TestBeanWithNoArgMethod();
            LeaderInfo leaderInfo = createLeaderInfo(false);
            when(leaderRegistry.getLeaderInfo(LEADERSHIP_NAME)).thenReturn(leaderInfo);

            processor.postProcessBeforeInitialization(bean, BEAN_NAME);

            // When
            processor.onApplicationEvent(new OnLeaderGrantedEvent(this, LEADERSHIP_NAME));

            // Then
            boolean invoked = bean.noArgMethodLatch.await(2, TimeUnit.SECONDS);
            assertThat(invoked)
                    .as("Метод без параметров должен быть вызван")
                    .isTrue();
        }

        @Test
        @DisplayName("Должен вызвать метод с boolean параметром при получении лидерства")
        void shouldInvokeBooleanArgMethodOnLeaderGranted() throws InterruptedException {
            // Given
            TestBeans.TestBeanWithBooleanArgMethod bean = new TestBeans.TestBeanWithBooleanArgMethod();
            LeaderInfo leaderInfo = createLeaderInfo(false);
            when(leaderRegistry.getLeaderInfo(LEADERSHIP_NAME)).thenReturn(leaderInfo);

            processor.postProcessBeforeInitialization(bean, BEAN_NAME);

            // When
            processor.onApplicationEvent(new OnLeaderGrantedEvent(this, LEADERSHIP_NAME));

            // Then
            boolean invoked = bean.booleanArgMethodLatch.await(2, TimeUnit.SECONDS);
            assertThat(invoked).isTrue();
            assertThat(bean.receivedValue)
                    .as("Метод должен получить true")
                    .isTrue();
        }

        @Test
        @DisplayName("Должен вызвать метод с boolean параметром при потере лидерства")
        void shouldInvokeBooleanArgMethodOnLeaderRevoked() throws InterruptedException {
            // Given
            TestBeans.TestBeanWithBooleanArgMethod bean = new TestBeans.TestBeanWithBooleanArgMethod();
            LeaderInfo leaderInfo = createLeaderInfo(true);
            when(leaderRegistry.getLeaderInfo(LEADERSHIP_NAME)).thenReturn(leaderInfo);

            processor.postProcessBeforeInitialization(bean, BEAN_NAME);
            bean.booleanArgMethodLatch = new CountDownLatch(1);

            // When
            processor.onApplicationEvent(new OnLeaderRevokedEvent(this, LEADERSHIP_NAME));

            // Then
            boolean invoked = bean.booleanArgMethodLatch.await(2, TimeUnit.SECONDS);
            assertThat(invoked).isTrue();
            assertThat(bean.receivedValue)
                    .as("Метод должен получить false")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("Validation")
    @Order(4)
    class ValidationTests {

        @Test
        @DisplayName("Должен выбросить исключение для неподдерживаемого типа поля")
        void shouldThrowExceptionForUnsupportedFieldType() {
            // Given
            TestBeans.TestBeanWithUnsupportedFieldType bean = new TestBeans.TestBeanWithUnsupportedFieldType();
            LeaderInfo leaderInfo = createLeaderInfo(false);
            when(leaderRegistry.getLeaderInfo(LEADERSHIP_NAME)).thenReturn(leaderInfo);

            // When/Then
            assertThatThrownBy(() -> processor.postProcessBeforeInitialization(bean, BEAN_NAME))
                    .isInstanceOf(LeaderAwareConfigurationException.class)
                    .hasMessageContaining("Unsupported field type");
        }

        @Test
        @DisplayName("Должен выбросить исключение для метода с неподдерживаемой сигнатурой")
        void shouldThrowExceptionForUnsupportedMethodSignature() {
            // Given
            TestBeans.TestBeanWithUnsupportedMethodSignature bean = new TestBeans.TestBeanWithUnsupportedMethodSignature();
            LeaderInfo leaderInfo = createLeaderInfo(false);
            when(leaderRegistry.getLeaderInfo(LEADERSHIP_NAME)).thenReturn(leaderInfo);

            // When/Then
            assertThatThrownBy(() -> processor.postProcessBeforeInitialization(bean, BEAN_NAME))
                    .isInstanceOf(LeaderAwareConfigurationException.class)
                    .hasMessageContaining("Unsupported @LeaderAware method signature");
        }

        @Test
        @DisplayName("Должен выбросить исключение для метода с неправильным типом параметра")
        void shouldThrowExceptionForMethodWithWrongParameterType() {
            // Given
            TestBeans.TestBeanWithWrongParameterType bean = new TestBeans.TestBeanWithWrongParameterType();
            LeaderInfo leaderInfo = createLeaderInfo(false);
            when(leaderRegistry.getLeaderInfo(LEADERSHIP_NAME)).thenReturn(leaderInfo);

            // When/Then
            assertThatThrownBy(() -> processor.postProcessBeforeInitialization(bean, BEAN_NAME))
                    .isInstanceOf(LeaderAwareConfigurationException.class)
                    .hasMessageContaining("Unsupported @LeaderAware method signature");
        }
    }

    @Nested
    @DisplayName("Caching")
    @Order(5)
    class CachingTests {

        @Test
        @DisplayName("Должен кэшировать поля для повторной обработки бинов того же класса")
        void shouldCacheFieldsForRepeatedProcessing() {
            // Given
            TestBeans.TestBeanWithBooleanField bean1 = new TestBeans.TestBeanWithBooleanField();
            TestBeans.TestBeanWithBooleanField bean2 = new TestBeans.TestBeanWithBooleanField();
            LeaderInfo leaderInfo = createLeaderInfo(true);
            when(leaderRegistry.getLeaderInfo(LEADERSHIP_NAME)).thenReturn(leaderInfo);

            // When
            processor.postProcessBeforeInitialization(bean1, "bean1");
            processor.postProcessBeforeInitialization(bean2, "bean2");

            // Then
            assertThat(bean1.isLeader).isTrue();
            assertThat(bean2.isLeader).isTrue();
        }

        @Test
        @DisplayName("Должен кэшировать методы для повторной обработки бинов того же класса")
        void shouldCacheMethodsForRepeatedProcessing() throws InterruptedException {
            // Give
            TestBeans.TestBeanWithNoArgMethod bean1 = new TestBeans.TestBeanWithNoArgMethod();
            TestBeans.TestBeanWithNoArgMethod bean2 = new TestBeans.TestBeanWithNoArgMethod();
            LeaderInfo leaderInfo = createLeaderInfo(false);
            when(leaderRegistry.getLeaderInfo(LEADERSHIP_NAME)).thenReturn(leaderInfo);

            processor.postProcessBeforeInitialization(bean1, "bean1");
            processor.postProcessBeforeInitialization(bean2, "bean2");

            // When
            processor.onApplicationEvent(new OnLeaderGrantedEvent(this, LEADERSHIP_NAME));

            // Then
            assertThat(bean1.noArgMethodLatch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(bean2.noArgMethodLatch.await(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Nested
    @DisplayName("Lifecycle")
    @Order(6)
    class LifecycleTests {

        @Test
        @DisplayName("Должен пропустить обработку после destroy")
        void shouldSkipProcessingAfterDestroy() {
            // Given
            processor.destroy();
            TestBeans.TestBeanWithBooleanField bean = new TestBeans.TestBeanWithBooleanField();

            // When
            Object result = processor.postProcessBeforeInitialization(bean, BEAN_NAME);

            // Then
            assertThat(result).isSameAs(bean);
            assertThat(bean.isLeader)
                    .as("Поле не должно быть изменено после destroy")
                    .isFalse();
        }

        @Test
        @DisplayName("Должен продолжать обрабатывать события после destroy но не принимать новые бины")
        void shouldProcessEventsAfterDestroyButNotAcceptNewBeans() {
            // Given
            TestBeans.TestBeanWithBooleanField bean = new TestBeans.TestBeanWithBooleanField();
            LeaderInfo leaderInfo = createLeaderInfo(true);
            when(leaderRegistry.getLeaderInfo(LEADERSHIP_NAME)).thenReturn(leaderInfo);

            processor.postProcessBeforeInitialization(bean, BEAN_NAME);
            assertThat(bean.isLeader).isTrue();

            processor.destroy();

            // When
            processor.onApplicationEvent(new OnLeaderRevokedEvent(this, LEADERSHIP_NAME));

            // Then
            assertThat(bean.isLeader)
                    .as("Поле должно быть обновлено на false - listeners продолжают работать")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("Concurrency")
    @Order(7)
    class ConcurrencyTests {

        @Test
        @DisplayName("Должен корректно обрабатывать параллельные события")
        void shouldHandleConcurrentEvents() throws InterruptedException {
            // Given
            int beanCount = 10;
            TestBeans.TestBeanWithBooleanField[] beans = new TestBeans.TestBeanWithBooleanField[beanCount];
            LeaderInfo leaderInfo = createLeaderInfo(false);
            when(leaderRegistry.getLeaderInfo(LEADERSHIP_NAME)).thenReturn(leaderInfo);

            for (int i = 0; i < beanCount; i++) {
                beans[i] = new TestBeans.TestBeanWithBooleanField();
                processor.postProcessBeforeInitialization(beans[i], "bean" + i);
            }

            // When
            CountDownLatch latch = new CountDownLatch(20);
            for (int i = 0; i < 10; i++) {
                new Thread(() -> {
                    processor.onApplicationEvent(new OnLeaderGrantedEvent(this, LEADERSHIP_NAME));
                    latch.countDown();
                }).start();
                new Thread(() -> {
                    processor.onApplicationEvent(new OnLeaderRevokedEvent(this, LEADERSHIP_NAME));
                    latch.countDown();
                }).start();
            }

            latch.await(5, TimeUnit.SECONDS);

            // Then: Все бины обработаны без ошибок
            for (TestBeans.TestBeanWithBooleanField bean : beans) {
                assertThat(bean.isLeader).isIn(Boolean.TRUE, Boolean.FALSE);
            }
        }
    }

    @Nested
    @DisplayName("Error Handling")
    @Order(8)
    class ErrorHandlingTests {

        @Test
        @DisplayName("Должен выбросить исключение для неподдерживаемого типа при field injection")
        void shouldThrowExceptionForUnsupportedFieldTypeDuringInjection() {
            // Given
            TestBeans.TestBeanWithInaccessibleField bean = new TestBeans.TestBeanWithInaccessibleField();
            LeaderInfo leaderInfo = createLeaderInfo(true);
            when(leaderRegistry.getLeaderInfo(LEADERSHIP_NAME)).thenReturn(leaderInfo);

            // When/Then
            assertThatThrownBy(() -> processor.postProcessBeforeInitialization(bean, BEAN_NAME))
                    .isInstanceOf(LeaderAwareConfigurationException.class)
                    .hasMessageContaining("Unsupported field type");
        }

        @Test
        @DisplayName("Должен логировать но не падать при ошибке в listener")
        void shouldLogButNotFailOnListenerError() throws InterruptedException {
            // Given
            TestBeans.TestBeanWithFailingMethod bean = new TestBeans.TestBeanWithFailingMethod();
            LeaderInfo leaderInfo = createLeaderInfo(false);
            when(leaderRegistry.getLeaderInfo(LEADERSHIP_NAME)).thenReturn(leaderInfo);

            processor.postProcessBeforeInitialization(bean, BEAN_NAME);

            // When
            processor.onApplicationEvent(new OnLeaderGrantedEvent(this, LEADERSHIP_NAME));

            // Then
            boolean invoked = bean.failingMethodLatch.await(2, TimeUnit.SECONDS);
            assertThat(invoked)
                    .as("Метод должен быть вызван несмотря на исключение")
                    .isTrue();
        }
    }

    private LeaderElectionProperties createProperties() {
        LeaderElectionProperties props = new LeaderElectionProperties();
        LeaderElectionProperties.LeadershipSettings leadership = new LeaderElectionProperties.LeadershipSettings();
        leadership.setEnabled(true);
        leadership.setLeadershipName(LEADERSHIP_NAME);
        props.setLeadership(leadership);
        return props;
    }

    private LeaderInfo createLeaderInfo(boolean isLeader) {
        return new LeaderInfo() {
            @Override
            public boolean isLeader() {
                return isLeader;
            }

            @Override
            public String getCurrentLeader() {
                return isLeader ? "current-leader" : null;
            }

            @Override
            public String getLeadershipName() {
                return LEADERSHIP_NAME;
            }
        };
    }

    static class TestBeans {

        static class TestBeanWithBooleanField {
            @LeaderAware
            private Boolean isLeader = false;

            public boolean isPrimitiveLeader() {
                return Boolean.TRUE.equals(isLeader);
            }
        }

        static class TestBeanWithLeaderInfoField {
            @LeaderAware
            private LeaderInfo leaderInfo;
        }

        static class TestBeanWithCustomTtlLeaderInfoField {
            @LeaderAware(ttlMillis = 5000)
            private LeaderInfo leaderInfoWithCustomTtl;
        }

        static class TestBeanWithNoArgMethod {
            CountDownLatch noArgMethodLatch = new CountDownLatch(1);

            @LeaderAware
            public void onLeadershipChange() {
                noArgMethodLatch.countDown();
            }
        }

        static class TestBeanWithBooleanArgMethod {
            CountDownLatch booleanArgMethodLatch = new CountDownLatch(1);
            volatile Boolean receivedValue;

            @LeaderAware
            public void onLeadershipChange(boolean isLeader) {
                this.receivedValue = isLeader;
                booleanArgMethodLatch.countDown();
            }
        }

        static class TestBeanWithUnsupportedFieldType {
            @LeaderAware
            private String unsupportedField;
        }

        static class TestBeanWithUnsupportedMethodSignature {
            @LeaderAware
            public void invalidMethod(String param1, int param2) {
            }
        }

        static class TestBeanWithWrongParameterType {
            @LeaderAware
            public void invalidMethod(String wrongType) {
            }
        }

        static class TestBeanWithInaccessibleField {
            @LeaderAware
            private final AtomicBoolean isLeader = new AtomicBoolean(false);
        }

        static class TestBeanWithFailingMethod {
            CountDownLatch failingMethodLatch = new CountDownLatch(1);

            @LeaderAware
            public void failingMethod() {
                failingMethodLatch.countDown();
                throw new RuntimeException("Test exception");
            }
        }
    }

}


