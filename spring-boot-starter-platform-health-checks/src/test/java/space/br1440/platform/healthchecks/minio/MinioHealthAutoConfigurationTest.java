package space.br1440.platform.healthchecks.minio;

import io.minio.MinioClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import space.br1440.platform.healthchecks.common.HealthProbe;
import space.br1440.platform.healthchecks.configuration.MinioHealthAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("MinioHealthAutoConfiguration: auto-configuration")
class MinioHealthAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MinioHealthAutoConfiguration.class));

    private final MinioClient mockMinioClient = mock(MinioClient.class);

    @Nested
    @DisplayName("когда все условия выполнены")
    class WhenAllConditionsMet {

        private final ApplicationContextRunner runner = contextRunner
                .withBean(MinioClient.class, () -> mockMinioClient);

        @Test
        @DisplayName("MinioBucketHealthCheck зарегистрирован")
        void minioBucketHealthCheckRegistered() {
            // When
            runner.run(ctx ->
                    // Then
                    assertThat(ctx).hasSingleBean(MinioBucketHealthCheck.class));
        }

        @Test
        @DisplayName("MinioSentinelHealthCheck зарегистрирован")
        void minioSentinelHealthCheckRegistered() {
            // When
            runner.run(ctx ->
                    // Then
                    assertThat(ctx).hasSingleBean(MinioSentinelHealthCheck.class));
        }

        @Test
        @DisplayName("HealthIndicator (minioHealthIndicator) зарегистрирован")
        void minioHealthIndicatorRegistered() {
            // When
            runner.run(ctx ->
                    // Then
                    assertThat(ctx).hasBean("minioHealthIndicator"));
        }

        @Test
        @DisplayName("minioHealthIndicator является экземпляром MinioHealthIndicator")
        void minioHealthIndicatorIsCorrectType() {
            // When
            runner.run(ctx ->
                    // Then
                    assertThat(ctx.getBean("minioHealthIndicator"))
                            .isInstanceOf(MinioHealthIndicator.class));
        }

        @Test
        @DisplayName("MinioHealthProperties зарегистрирован и содержит дефолтные значения")
        void minioHealthPropertiesRegistered() {
            // When
            runner.run(ctx -> {
                // Then
                assertThat(ctx).hasSingleBean(MinioHealthProperties.class);
                MinioHealthProperties props = ctx.getBean(MinioHealthProperties.class);
                assertThat(props.isEnabled()).isTrue();
                assertThat(props.getProbes()).containsExactly(HealthProbe.READINESS);
            });
        }
    }

    @Nested
    @DisplayName("когда MinioClient отсутствует в контексте")
    class WhenMinioClientAbsent {

        @Test
        @DisplayName("ни один бин автоконфигурации не создаётся")
        void noBeansCreatedWithoutMinioClient() {
            // When
            contextRunner.run(ctx -> {
                // Then
                assertThat(ctx).doesNotHaveBean(MinioBucketHealthCheck.class);
                assertThat(ctx).doesNotHaveBean(MinioSentinelHealthCheck.class);
                assertThat(ctx).doesNotHaveBean("minioHealthIndicator");
            });
        }

        @Test
        @DisplayName("MinioClient отсутствует в classpath (@ConditionalOnClass) → бины не создаются")
        void noBeansCreatedWhenMinioClientClassNotOnClasspath() {
            // When
            contextRunner
                    .withClassLoader(new FilteredClassLoader(MinioClient.class))
                    .run(ctx -> {
                        // Then
                        assertThat(ctx).doesNotHaveBean(MinioBucketHealthCheck.class);
                        assertThat(ctx).doesNotHaveBean(MinioSentinelHealthCheck.class);
                        assertThat(ctx).doesNotHaveBean("minioHealthIndicator");
                    });
        }
    }

    @Nested
    @DisplayName("platform.health.minio.enabled")
    class EnabledProperty {

        @Test
        @DisplayName("enabled=false → бины не создаются")
        void disabledPropertyPreventsBeansCreation() {
            // When
            contextRunner
                    .withBean(MinioClient.class, () -> mockMinioClient)
                    .withPropertyValues("platform.health.minio.enabled=false")
                    .run(ctx -> {
                        // Then
                        assertThat(ctx).doesNotHaveBean(MinioBucketHealthCheck.class);
                        assertThat(ctx).doesNotHaveBean("minioHealthIndicator");
                    });
        }

        @Test
        @DisplayName("enabled=true → бины создаются")
        void explicitEnabledTrueCreatesBean() {
            // When
            contextRunner
                    .withBean(MinioClient.class, () -> mockMinioClient)
                    .withPropertyValues("platform.health.minio.enabled=true")
                    .run(ctx ->
                            // Then
                            assertThat(ctx).hasBean("minioHealthIndicator"));
        }

        @Test
        @DisplayName("enabled отсутствует → бины создаются (matchIfMissing=true)")
        void missingPropertyDefaultsToEnabled() {
            // When
            contextRunner
                    .withBean(MinioClient.class, () -> mockMinioClient)
                    .run(ctx ->
                            // Then
                            assertThat(ctx).hasBean("minioHealthIndicator"));
        }
    }

    @Nested
    @DisplayName("@ConditionalOnMissingBean — пользовательские бины имеют приоритет")
    class UserDefinedBeansOverride {

        @Test
        @DisplayName("пользовательский MinioBucketHealthCheck не перезаписывается")
        void userDefinedBucketHealthCheckNotOverridden() {
            // Given
            MinioBucketHealthCheck custom = mock(MinioBucketHealthCheck.class);

            // When
            contextRunner
                    .withBean(MinioClient.class, () -> mockMinioClient)
                    .withBean(MinioBucketHealthCheck.class, () -> custom)
                    .run(ctx -> {
                        // Then
                        assertThat(ctx).hasSingleBean(MinioBucketHealthCheck.class);
                        assertThat(ctx.getBean(MinioBucketHealthCheck.class)).isSameAs(custom);
                    });
        }

        @Test
        @DisplayName("пользовательский MinioSentinelHealthCheck не перезаписывается")
        void userDefinedSentinelHealthCheckNotOverridden() {
            // Given
            MinioSentinelHealthCheck custom = mock(MinioSentinelHealthCheck.class);

            // When
            contextRunner
                    .withBean(MinioClient.class, () -> mockMinioClient)
                    .withBean(MinioSentinelHealthCheck.class, () -> custom)
                    .run(ctx -> {
                        // Then
                        assertThat(ctx).hasSingleBean(MinioSentinelHealthCheck.class);
                        assertThat(ctx.getBean(MinioSentinelHealthCheck.class)).isSameAs(custom);
                    });
        }

        @Test
        @DisplayName("пользовательский minioHealthIndicator не перезаписывается")
        void userDefinedHealthIndicatorNotOverridden() {
            // Given
            HealthIndicator custom = mock(HealthIndicator.class);

            // When
            contextRunner
                    .withBean(MinioClient.class, () -> mockMinioClient)
                    .withBean("minioHealthIndicator", HealthIndicator.class, () -> custom)
                    .run(ctx -> {
                        // Then — пользовательский бин зарегистрирован под этим именем
                        assertThat(ctx.getBean("minioHealthIndicator")).isSameAs(custom);
                        // авто-конфигурация не создала новый MinioHealthIndicator
                        assertThat(ctx).doesNotHaveBean(MinioHealthIndicator.class);
                    });
        }
    }

    @Nested
    @DisplayName("привязка properties из конфигурации")
    class PropertiesBinding {

        @Test
        @DisplayName("platform.health.minio.* корректно биндится в MinioHealthProperties")
        void propertiesBindCorrectly() {
            // When
            contextRunner
                    .withBean(MinioClient.class, () -> mockMinioClient)
                    .withPropertyValues(
                            "platform.health.minio.enabled=true",
                            "platform.health.minio.probes=LIVENESS",
                            "platform.health.minio.async.enabled=false",
                            "platform.health.minio.buckets.probe-bucket=my-probe"
                    )
                    .run(ctx -> {
                        // Then
                        MinioHealthProperties props = ctx.getBean(MinioHealthProperties.class);
                        assertThat(props.isEnabled()).isTrue();
                        assertThat(props.getProbes()).containsExactly(HealthProbe.LIVENESS);
                        assertThat(props.getAsync().isEnabled()).isFalse();
                        assertThat(props.getBuckets().getProbeBucket()).isEqualTo("my-probe");
                    });
        }
    }
}
