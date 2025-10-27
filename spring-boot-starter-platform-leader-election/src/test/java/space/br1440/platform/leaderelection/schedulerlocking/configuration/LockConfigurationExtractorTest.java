package space.br1440.platform.leaderelection.schedulerlocking.configuration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import space.br1440.platform.leaderelection.annotation.locking.DistributedLock;
import space.br1440.platform.leaderelection.annotation.locking.SchedulerLock;
import space.br1440.platform.leaderelection.configuration.LeaderElectionProperties;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Тесты LockConfigurationExtractor")
class LockConfigurationExtractorTest {

    @Mock
    private Environment environment;

    @Mock
    private ApplicationContext applicationContext;

    private LockConfigurationExtractor extractor;
    private LeaderElectionProperties properties;

    @BeforeEach
    void setUp() {
        properties = new LeaderElectionProperties();
        properties.setLock(new LeaderElectionProperties.LockSettings()
                .setEnabled(true)
                .setDefaultLockAtMostFor(Duration.ofMinutes(30))
                .setDefaultLockAtLeastFor(Duration.ofMinutes(1)));

        Mockito.lenient()
                .when(environment.resolvePlaceholders(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        extractor = new LockConfigurationExtractor(properties, environment, applicationContext);
    }

    static class TestMethods {
        @SchedulerLock(lockName = "test-lock", lockAtMostFor = "5m")
        public void schedulerLockWithIso8601() {
        }

        @SchedulerLock(lockName = "test-lock", lockAtMostFor = "100")
        public void schedulerLockWithMilliseconds() {
        }

        @SchedulerLock(lockName = "test-lock", lockAtMostFor = "10s")
        public void schedulerLockWithSeconds() {
        }

        @SchedulerLock(lockName = "test-lock", lockAtMostFor = "5m")
        public void schedulerLockWithMinutes() {
        }

        @SchedulerLock(lockName = "test-lock", lockAtMostFor = "2h")
        public void schedulerLockWithHours() {
        }

        @SchedulerLock(lockName = "test-lock", lockAtMostFor = "1d")
        public void schedulerLockWithDays() {
        }

        @SchedulerLock(lockName = "test-lock")
        public void schedulerLockWithoutLockAtMostFor() {
        }

        @SchedulerLock(lockName = "test-lock", lockAtMostFor = "30m", lockAtLeastFor = "0s")
        public void schedulerLockWithZeroGracePeriod() {
        }

        @SchedulerLock(lockName = "test-lock", lockAtMostFor = "30m", lockAtLeastFor = "10s")
        public void schedulerLockWithPositiveGracePeriod() {
        }

        @SchedulerLock(lockName = "test-lock", lockAtMostFor = "30m", lockAtLeastFor = "10ms")
        public void schedulerLockWithPositiveGracePeriodSimpleFormat() {
        }

        @SchedulerLock(lockName = "test-lock", lockAtMostFor = "${lock.timeout}")
        public void schedulerLockWithPropertyPlaceholder() {
        }

        @SchedulerLock(lockName = "test-lock", lockAtMostFor = "${lock.timeout:15m}")
        public void schedulerLockWithPropertyPlaceholderAndDefault() {
        }

        @SchedulerLock(lockName = "test-lock", lockAtMostFor = "#{@lockConfigService.getTimeout()}")
        public void schedulerLockWithSpelExpression() {
        }

        @SchedulerLock(lockName = "test-lock", lockAtMostFor = "${lock.base.timeout}", lockAtLeastFor = "${lock.min.timeout}")
        public void schedulerLockWithMultiplePlaceholders() {
        }

        @SchedulerLock(lockName = "test-lock", lockAtMostFor = "30m", keepAliveLockUntil = "5m")
        public void schedulerLockWithKeepAlive() {
        }

        @DistributedLock(lockName = "distributed-lock", lockAtMostFor = "10m")
        public void distributedLockWithIso8601() {
        }

        @DistributedLock(lockName = "distributed-lock", lockAtMostFor = "5m", lockAtLeastFor = "1m")
        public void distributedLockWithSimpleFormat() {
        }

        @DistributedLock(lockName = "distributed-lock")
        public void distributedLockWithDefaults() {
        }

        public void methodWithoutAnnotation() {
        }
    }

    @Nested
    @DisplayName("Annotation Detection Tests")
    class AnnotationDetectionTests {

        @Test
        @DisplayName("Должен определить наличие @SchedulerLock аннотации")
        void shouldDetectSchedulerLockAnnotation() throws NoSuchMethodException {
            Method method = TestMethods.class.getMethod("schedulerLockWithIso8601");
            boolean hasAnnotation = extractor.hasLockAnnotation(method);
            assertThat(hasAnnotation).isTrue();
        }

        @Test
        @DisplayName("Должен определить наличие @DistributedLock аннотации")
        void shouldDetectDistributedLockAnnotation() throws NoSuchMethodException {
            Method method = TestMethods.class.getMethod("distributedLockWithIso8601");
            boolean hasAnnotation = extractor.hasLockAnnotation(method);
            assertThat(hasAnnotation).isTrue();
        }

        @Test
        @DisplayName("Должен вернуть false для метода без аннотаций")
        void shouldReturnFalseForMethodWithoutAnnotation() throws NoSuchMethodException {
            Method method = TestMethods.class.getMethod("methodWithoutAnnotation");
            boolean hasAnnotation = extractor.hasLockAnnotation(method);
            assertThat(hasAnnotation).isFalse();
        }
    }

    @Nested
    @DisplayName("ISO-8601 Duration Format Tests")
    class Iso8601DurationFormatTests {

        @Test
        @DisplayName("Должен корректно распарсить ISO-8601 формат (5m) для @SchedulerLock")
        void shouldParseIso8601FormatForSchedulerLock() throws NoSuchMethodException {
            Method method = TestMethods.class.getMethod("schedulerLockWithIso8601");
            LockConfiguration config = extractor.extractLockConfiguration(method);

            assertThat(config).isNotNull();
            assertThat(config.leaseRole()).isEqualTo("test-lock");
            assertThat(config.lockAtMostFor()).isEqualTo(Duration.of(5, ChronoUnit.MINUTES));
            assertThat(config.lockAtLeastFor()).isEqualTo(properties.getLock().getDefaultLockAtLeastFor());
            assertThat(config.keepAliveLockUntil()).isEqualTo(Duration.ZERO);
        }

        @Test
        @DisplayName("Должен корректно распарсить ISO-8601 формат (10m) для @DistributedLock")
        void shouldParseIso8601FormatForDistributedLock() throws NoSuchMethodException {
            Method method = TestMethods.class.getMethod("distributedLockWithIso8601");
            LockConfiguration config = extractor.extractLockConfiguration(method);

            assertThat(config).isNotNull();
            assertThat(config.leaseRole()).isEqualTo("distributed-lock");
            assertThat(config.lockAtMostFor()).isEqualTo(Duration.of(10, ChronoUnit.MINUTES));
            assertThat(config.lockAtLeastFor()).isEqualTo(properties.getLock().getDefaultLockAtLeastFor());
        }
    }

    @Nested
    @DisplayName("Simple Duration Format Tests")
    class SimpleDurationFormatTests {

        @Test
        @DisplayName("Должен корректно распарсить миллисекунды (100)")
        void shouldParseMilliseconds() throws NoSuchMethodException {
            Method method = TestMethods.class.getMethod("schedulerLockWithMilliseconds");
            LockConfiguration config = extractor.extractLockConfiguration(method);

            assertThat(config.lockAtMostFor()).isEqualTo(Duration.of(100, ChronoUnit.MILLIS));
        }

        @Test
        @DisplayName("Должен корректно распарсить секунды (10s)")
        void shouldParseSeconds() throws NoSuchMethodException {
            Method method = TestMethods.class.getMethod("schedulerLockWithSeconds");
            LockConfiguration config = extractor.extractLockConfiguration(method);

            assertThat(config.lockAtMostFor()).isEqualTo(Duration.of(10, ChronoUnit.SECONDS));
        }

        @Test
        @DisplayName("Должен корректно распарсить минуты (5m)")
        void shouldParseMinutes() throws NoSuchMethodException {
            Method method = TestMethods.class.getMethod("schedulerLockWithMinutes");
            LockConfiguration config = extractor.extractLockConfiguration(method);

            assertThat(config.lockAtMostFor()).isEqualTo(Duration.of(5, ChronoUnit.MINUTES));
        }

        @Test
        @DisplayName("Должен корректно распарсить часы (2h)")
        void shouldParseHours() throws NoSuchMethodException {
            Method method = TestMethods.class.getMethod("schedulerLockWithHours");
            LockConfiguration config = extractor.extractLockConfiguration(method);

            assertThat(config.lockAtMostFor()).isEqualTo(Duration.of(2, ChronoUnit.HOURS));
        }

        @Test
        @DisplayName("Должен корректно распарсить дни (1d)")
        void shouldParseDays() throws NoSuchMethodException {
            Method method = TestMethods.class.getMethod("schedulerLockWithDays");
            LockConfiguration config = extractor.extractLockConfiguration(method);

            assertThat(config.lockAtMostFor()).isEqualTo(Duration.of(1, ChronoUnit.DAYS));
        }
    }

    @Nested
    @DisplayName("Default Values Tests")
    class DefaultValuesTests {

        @Test
        @DisplayName("Должен использовать значение по умолчанию для lockAtMostFor, если не указано")
        void shouldUseDefaultLockAtMostForWhenNotSpecified() throws NoSuchMethodException {
            Method method = TestMethods.class.getMethod("schedulerLockWithoutLockAtMostFor");
            LockConfiguration config = extractor.extractLockConfiguration(method);

            assertThat(config.lockAtMostFor()).isEqualTo(properties.getLock().getDefaultLockAtMostFor());
        }

        @Test
        @DisplayName("Должен использовать значение по умолчанию для lockAtLeastFor, если не указано")
        void shouldUseDefaultLockAtLeastForWhenNotSpecified() throws NoSuchMethodException {
            Method method = TestMethods.class.getMethod("schedulerLockWithIso8601");
            LockConfiguration config = extractor.extractLockConfiguration(method);

            assertThat(config.lockAtLeastFor()).isEqualTo(properties.getLock().getDefaultLockAtLeastFor());
        }

        @Test
        @DisplayName("Должен использовать Duration.ZERO для keepAliveLockUntil, если не указано")
        void shouldUseZeroForKeepAliveLockUntilWhenNotSpecified() throws NoSuchMethodException {
            Method method = TestMethods.class.getMethod("schedulerLockWithIso8601");
            LockConfiguration config = extractor.extractLockConfiguration(method);

            assertThat(config.keepAliveLockUntil()).isEqualTo(Duration.ZERO);
        }

        @Test
        @DisplayName("Должен использовать все значения по умолчанию для @DistributedLock без параметров")
        void shouldUseAllDefaultsForDistributedLockWithoutParameters() throws NoSuchMethodException {
            Method method = TestMethods.class.getMethod("distributedLockWithDefaults");
            LockConfiguration config = extractor.extractLockConfiguration(method);

            assertThat(config.lockAtMostFor()).isEqualTo(properties.getLock().getDefaultLockAtMostFor());
            assertThat(config.lockAtLeastFor()).isEqualTo(properties.getLock().getDefaultLockAtLeastFor());
            assertThat(config.keepAliveLockUntil()).isEqualTo(Duration.ZERO);
        }
    }

    @Nested
    @DisplayName("LockAtLeastFor (Grace Period) Tests")
    class LockAtLeastForTests {

        @Test
        @DisplayName("Должен корректно распарсить нулевой grace period (0s)")
        void shouldParseZeroGracePeriod() throws NoSuchMethodException {
            Method method = TestMethods.class.getMethod("schedulerLockWithZeroGracePeriod");
            LockConfiguration config = extractor.extractLockConfiguration(method);

            assertThat(config.lockAtLeastFor()).isEqualTo(Duration.ZERO);
        }

        @Test
        @DisplayName("Должен корректно распарсить положительный grace period (10s)")
        void shouldParsePositiveGracePeriod() throws NoSuchMethodException {
            Method method = TestMethods.class.getMethod("schedulerLockWithPositiveGracePeriod");
            LockConfiguration config = extractor.extractLockConfiguration(method);

            assertThat(config.lockAtLeastFor()).isEqualTo(Duration.of(10, ChronoUnit.SECONDS));
        }

        @Test
        @DisplayName("Должен корректно распарсить grace period в простом формате (10ms)")
        void shouldParsePositiveGracePeriodInSimpleFormat() throws NoSuchMethodException {
            Method method = TestMethods.class.getMethod("schedulerLockWithPositiveGracePeriodSimpleFormat");
            LockConfiguration config = extractor.extractLockConfiguration(method);

            assertThat(config.lockAtLeastFor()).isEqualTo(Duration.of(10, ChronoUnit.MILLIS));
        }
    }

    @Nested
    @DisplayName("KeepAliveLockUntil Tests")
    class KeepAliveLockUntilTests {

        @Test
        @DisplayName("Должен корректно распарсить keepAliveLockUntil (5m)")
        void shouldParseKeepAliveLockUntil() throws NoSuchMethodException {
            Method method = TestMethods.class.getMethod("schedulerLockWithKeepAlive");
            LockConfiguration config = extractor.extractLockConfiguration(method);

            assertThat(config.keepAliveLockUntil()).isEqualTo(Duration.of(5, ChronoUnit.MINUTES));
        }
    }

    @Nested
    @DisplayName("Property Placeholder Tests")
    class PropertyPlaceholderTests {

        @Test
        @DisplayName("Должен резолвить property placeholder ${lock.timeout}")
        void shouldResolvePropertyPlaceholder() throws NoSuchMethodException {
            when(environment.resolvePlaceholders("${lock.timeout}")).thenReturn("15m");

            Method method = TestMethods.class.getMethod("schedulerLockWithPropertyPlaceholder");
            LockConfiguration config = extractor.extractLockConfiguration(method);

            assertThat(config.lockAtMostFor()).isEqualTo(Duration.of(15, ChronoUnit.MINUTES));
        }

        @Test
        @DisplayName("Должен резолвить property placeholder с default значением ${lock.timeout:15m}")
        void shouldResolvePropertyPlaceholderWithDefault() throws NoSuchMethodException {
            when(environment.resolvePlaceholders("${lock.timeout:15m}")).thenReturn("15m");

            Method method = TestMethods.class.getMethod("schedulerLockWithPropertyPlaceholderAndDefault");
            LockConfiguration config = extractor.extractLockConfiguration(method);

            assertThat(config.lockAtMostFor()).isEqualTo(Duration.of(15, ChronoUnit.MINUTES));
        }

        @Test
        @DisplayName("Должен резолвить множественные property placeholders")
        void shouldResolveMultiplePlaceholders() throws NoSuchMethodException {
            when(environment.resolvePlaceholders("${lock.base.timeout}")).thenReturn("20m");
            when(environment.resolvePlaceholders("${lock.min.timeout}")).thenReturn("2m");

            Method method = TestMethods.class.getMethod("schedulerLockWithMultiplePlaceholders");
            LockConfiguration config = extractor.extractLockConfiguration(method);

            assertThat(config.lockAtMostFor()).isEqualTo(Duration.of(20, ChronoUnit.MINUTES));
            assertThat(config.lockAtLeastFor()).isEqualTo(Duration.of(2, ChronoUnit.MINUTES));
        }
    }

    @Nested
    @DisplayName("SpEL Expression Tests")
    class SpelExpressionTests {

        @Test
        @DisplayName("Должен резолвить SpEL выражение #{@lockConfigService.getTimeout()}")
        void shouldResolveSpelExpression() throws NoSuchMethodException {
            Mockito.lenient()
                    .when(environment.resolvePlaceholders("#{@lockConfigService.getTimeout()}"))
                    .thenReturn("25m");

            Method method = TestMethods.class.getMethod("schedulerLockWithSpelExpression");
            LockConfiguration config = extractor.extractLockConfiguration(method);

            assertThat(config.lockAtMostFor()).isEqualTo(Duration.of(25, ChronoUnit.MINUTES));
        }
    }

    @Nested
    @DisplayName("Lock Name Extraction Tests")
    class LockNameExtractionTests {

        @Test
        @DisplayName("Должен корректно извлечь lockName из @SchedulerLock")
        void shouldExtractLockNameFromSchedulerLock() throws NoSuchMethodException {
            Method method = TestMethods.class.getMethod("schedulerLockWithIso8601");
            LockConfiguration config = extractor.extractLockConfiguration(method);

            assertThat(config.leaseRole()).isEqualTo("test-lock");
        }

        @Test
        @DisplayName("Должен корректно извлечь lockName из @DistributedLock")
        void shouldExtractLockNameFromDistributedLock() throws NoSuchMethodException {
            Method method = TestMethods.class.getMethod("distributedLockWithIso8601");
            LockConfiguration config = extractor.extractLockConfiguration(method);

            assertThat(config.leaseRole()).isEqualTo("distributed-lock");
        }
    }

    @Nested
    @DisplayName("Custom Default Values Tests")
    class CustomDefaultValuesTests {

        @Test
        @DisplayName("Должен использовать кастомные default значения из LeaderElectionProperties")
        void shouldUseCustomDefaultValues() throws NoSuchMethodException {
            properties.getLock().setDefaultLockAtMostFor(Duration.ofMinutes(45));
            properties.getLock().setDefaultLockAtLeastFor(Duration.ofMinutes(3));

            extractor = new LockConfigurationExtractor(properties, environment, applicationContext);

            Method method = TestMethods.class.getMethod("schedulerLockWithoutLockAtMostFor");
            LockConfiguration config = extractor.extractLockConfiguration(method);

            assertThat(config.lockAtMostFor()).isEqualTo(Duration.ofMinutes(45));
            assertThat(config.lockAtLeastFor()).isEqualTo(Duration.ofMinutes(3));
        }
    }

    @Nested
    @DisplayName("Edge Cases and Error Handling Tests")
    class EdgeCasesTests {

        @Test
        @DisplayName("Должен использовать default при невалидном формате Duration")
        void shouldUseDefaultForInvalidDurationFormat() throws NoSuchMethodException {
            when(environment.resolvePlaceholders("${lock.timeout}")).thenReturn("invalid-format");

            Method method = TestMethods.class.getMethod("schedulerLockWithPropertyPlaceholder");
            LockConfiguration config = extractor.extractLockConfiguration(method);

            assertThat(config.lockAtMostFor()).isEqualTo(properties.getLock().getDefaultLockAtMostFor());
        }

        @Test
        @DisplayName("Должен использовать default при отрицательном Duration")
        void shouldUseDefaultForNegativeDuration() throws NoSuchMethodException {
            when(environment.resolvePlaceholders("${lock.timeout}")).thenReturn("-5m");

            Method method = TestMethods.class.getMethod("schedulerLockWithPropertyPlaceholder");
            LockConfiguration config = extractor.extractLockConfiguration(method);

            assertThat(config.lockAtMostFor()).isEqualTo(properties.getLock().getDefaultLockAtMostFor());
        }

        @Test
        @DisplayName("Должен корректно обработать пустой property placeholder")
        void shouldHandleEmptyPropertyPlaceholder() throws NoSuchMethodException {
            when(environment.resolvePlaceholders("${lock.timeout}")).thenReturn("");

            Method method = TestMethods.class.getMethod("schedulerLockWithPropertyPlaceholder");
            LockConfiguration config = extractor.extractLockConfiguration(method);

            assertThat(config.lockAtMostFor()).isEqualTo(properties.getLock().getDefaultLockAtMostFor());
        }

        @Test
        @DisplayName("Должен корректно обработать недоступный bean в SpEL")
        void shouldHandleMissingBeanInSpel() throws NoSuchMethodException {
            org.mockito.Mockito.lenient()
                    .when(environment.resolvePlaceholders("#{@lockConfigService.getTimeout()}"))
                    .thenReturn("#{@lockConfigService.getTimeout()}");

            Method method = TestMethods.class.getMethod("schedulerLockWithSpelExpression");
            LockConfiguration config = extractor.extractLockConfiguration(method);

            assertThat(config.lockAtMostFor()).isEqualTo(properties.getLock().getDefaultLockAtMostFor());
        }
    }

    @Nested
    @DisplayName("Multiple Parameters Tests")
    class MultipleParametersTests {

        @Test
        @DisplayName("Должен корректно извлечь все параметры для @DistributedLock")
        void shouldExtractAllParametersForDistributedLock() throws NoSuchMethodException {
            Method method = TestMethods.class.getMethod("distributedLockWithSimpleFormat");
            LockConfiguration config = extractor.extractLockConfiguration(method);

            assertThat(config.leaseRole()).isEqualTo("distributed-lock");
            assertThat(config.lockAtMostFor()).isEqualTo(Duration.of(5, ChronoUnit.MINUTES));
            assertThat(config.lockAtLeastFor()).isEqualTo(Duration.of(1, ChronoUnit.MINUTES));
            assertThat(config.keepAliveLockUntil()).isEqualTo(Duration.ZERO);
        }

        @Test
        @DisplayName("Должен корректно извлечь все параметры включая keepAlive")
        void shouldExtractAllParametersIncludingKeepAlive() throws NoSuchMethodException {
            Method method = TestMethods.class.getMethod("schedulerLockWithKeepAlive");
            LockConfiguration config = extractor.extractLockConfiguration(method);

            assertThat(config.leaseRole()).isEqualTo("test-lock");
            assertThat(config.lockAtMostFor()).isEqualTo(Duration.of(30, ChronoUnit.MINUTES));
            assertThat(config.lockAtLeastFor()).isEqualTo(properties.getLock().getDefaultLockAtLeastFor());
            assertThat(config.keepAliveLockUntil()).isEqualTo(Duration.of(5, ChronoUnit.MINUTES));
        }
    }

}
