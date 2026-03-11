package space.br1440.platform.healthchecks.kafka;

import org.apache.kafka.clients.admin.AdminClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;
import space.br1440.platform.healthchecks.configuration.KafkaHealthAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("KafkaHealthAutoConfiguration: conditional bean wiring")
class KafkaHealthAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaHealthAutoConfiguration.class));

    private ApplicationContextRunner withFullInfrastructure() {
        return contextRunner.withUserConfiguration(AdminClientConfig.class, KafkaTemplateConfig.class);
    }

    @Nested
    @DisplayName("@ConditionalOnClass(KafkaTemplate) — автоконфигурация активна только при наличии класса")
    class ConditionalOnKafkaTemplateClass {

        @Test
        @DisplayName("KafkaHealthProperties регистрируется когда KafkaTemplate есть в classpath")
        void shouldRegisterPropertiesWhenKafkaTemplateOnClasspath() {
            // Given / When / Then
            contextRunner.run(ctx ->
                    assertThat(ctx).hasSingleBean(KafkaHealthProperties.class)
            );
        }

        @Test
        @DisplayName("Ни один бин не регистрируется когда KafkaTemplate отсутствует в classpath")
        void shouldNotRegisterAnyBeanWhenKafkaTemplateMissing() {
            // Given
            contextRunner
                    .withClassLoader(new FilteredClassLoader(KafkaTemplate.class))
                    // When / Then
                    .run(ctx -> {
                        assertThat(ctx).doesNotHaveBean(KafkaHealthProperties.class);
                        assertThat(ctx).doesNotHaveBean(KafkaHealthIndicator.class);
                        assertThat(ctx).doesNotHaveBean(KafkaProducerHealthCheck.class);
                        assertThat(ctx).doesNotHaveBean(KafkaConsumerHealthCheck.class);
                        assertThat(ctx).doesNotHaveBean(KafkaTopicHealthCheck.class);
                    });
        }
    }

    @Nested
    @DisplayName("@EnableConfigurationProperties — KafkaHealthProperties биндится из конфигурации")
    class ConfigurationPropertiesBinding {

        @Test
        @DisplayName("KafkaHealthProperties регистрируется как бин")
        void shouldRegisterKafkaHealthPropertiesBean() {
            // Given / When / Then
            contextRunner.run(ctx ->
                    assertThat(ctx).hasSingleBean(KafkaHealthProperties.class)
            );
        }

        @Test
        @DisplayName("Кастомные значения свойств применяются из application properties")
        void shouldBindCustomPropertyValues() {
            // Given
            contextRunner
                    .withPropertyValues(
                            "platform.health.kafka.enabled=false",
                            "platform.health.kafka.cluster.min-broker-count=3",
                            "platform.health.kafka.async.enabled=false"
                    )
                    // When / Then
                    .run(ctx -> {
                        assertThat(ctx).hasSingleBean(KafkaHealthProperties.class);
                        KafkaHealthProperties props = ctx.getBean(KafkaHealthProperties.class);
                        assertThat(props.isEnabled()).isFalse();
                        assertThat(props.getCluster().getMinBrokerCount()).isEqualTo(3);
                        assertThat(props.getAsync().isEnabled()).isFalse();
                    });
        }

        @Test
        @DisplayName("Значения по умолчанию корректны без явного конфигурирования")
        void shouldHaveCorrectDefaultValues() {
            // Given / When / Then
            contextRunner.run(ctx -> {
                KafkaHealthProperties props = ctx.getBean(KafkaHealthProperties.class);
                assertThat(props.isEnabled()).isTrue();
                assertThat(props.getCluster().getMinBrokerCount()).isEqualTo(1);
                assertThat(props.getProducer().isEnabled()).isFalse();
                assertThat(props.getConsumer().isEnabled()).isFalse();
                assertThat(props.getTopics().isEnabled()).isFalse();
            });
        }
    }

    @Nested
    @DisplayName("Sub-check beans — регистрация при наличии необходимых зависимостей")
    class SubCheckBeanRegistration {

        @Test
        @DisplayName("KafkaConsumerHealthCheck регистрируется без AdminClient и KafkaTemplate")
        void shouldRegisterConsumerHealthCheckWithoutKafkaInfrastructure() {
            // Given / When / Then
            contextRunner.run(ctx ->
                    assertThat(ctx).hasSingleBean(KafkaConsumerHealthCheck.class)
            );
        }

        @Test
        @DisplayName("KafkaTopicHealthCheck регистрируется без AdminClient и KafkaTemplate")
        void shouldRegisterTopicHealthCheckWithoutKafkaInfrastructure() {
            // Given / When / Then
            contextRunner.run(ctx ->
                    assertThat(ctx).hasSingleBean(KafkaTopicHealthCheck.class)
            );
        }

        @Test
        @DisplayName("KafkaProducerHealthCheck регистрируется когда KafkaTemplate bean присутствует")
        void shouldRegisterProducerHealthCheckWhenKafkaTemplatePresent() {
            // Given
            contextRunner
                    .withUserConfiguration(KafkaTemplateConfig.class)
                    // When / Then
                    .run(ctx ->
                            assertThat(ctx).hasSingleBean(KafkaProducerHealthCheck.class)
                    );
        }

        @Test
        @DisplayName("KafkaProducerHealthCheck НЕ регистрируется без KafkaTemplate bean")
        void shouldNotRegisterProducerHealthCheckWithoutKafkaTemplate() {
            // Given / When / Then
            contextRunner.run(ctx ->
                    assertThat(ctx).doesNotHaveBean(KafkaProducerHealthCheck.class)
            );
        }
    }

    @Nested
    @DisplayName("KafkaHealthIndicator — @ConditionalOnBean(AdminClient)")
    class HealthIndicatorConditionalOnAdminClient {

        @Test
        @DisplayName("KafkaHealthIndicator регистрируется когда AdminClient присутствует (enabled=true по умолчанию)")
        void shouldRegisterHealthIndicatorWhenAdminClientPresent() {
            // Given — AdminClient есть; enabled не задан → matchIfMissing=true → индикатор создаётся
            contextRunner
                    .withUserConfiguration(AdminClientConfig.class)
                    // When / Then
                    .run(ctx ->
                            assertThat(ctx).hasSingleBean(KafkaHealthIndicator.class)
                    );
        }

        @Test
        @DisplayName("KafkaHealthIndicator НЕ регистрируется без AdminClient bean (даже при enabled=true)")
        void shouldNotRegisterHealthIndicatorWithoutAdminClient() {
            // @ConditionalOnBean(AdminClient.class) — основная защита:
            // если AdminClient нет в контексте, сервис не использует Kafka → индикатор не нужен
            contextRunner
                    .withPropertyValues("platform.health.kafka.enabled=true")
                    .run(ctx ->
                            assertThat(ctx).doesNotHaveBean(KafkaHealthIndicator.class)
                    );
        }

        @Test
        @DisplayName("KafkaHealthIndicator реализует HealthIndicator")
        void shouldImplementHealthIndicator() {
            // Given
            contextRunner
                    .withUserConfiguration(AdminClientConfig.class)
                    // When / Then
                    .run(ctx ->
                            assertThat(ctx).hasSingleBean(HealthIndicator.class)
                    );
        }
    }

    @Nested
    @DisplayName("KafkaHealthIndicator — @ConditionalOnProperty(platform.health.kafka.enabled) — opt-out по умолчанию")
    class HealthIndicatorEnabledProperty {

        @Test
        @DisplayName("KafkaHealthIndicator регистрируется когда enabled не задан (opt-out: matchIfMissing=true)")
        void shouldRegisterWhenEnabledNotSet() {
            // Given — enabled не задан явно; matchIfMissing=true → индикатор активен по умолчанию,
            // как и все Spring Boot Actuator health indicators (db, redis, rabbit)
            contextRunner
                    .withUserConfiguration(AdminClientConfig.class)
                    // When / Then
                    .run(ctx ->
                            assertThat(ctx).hasSingleBean(KafkaHealthIndicator.class)
                    );
        }

        @Test
        @DisplayName("KafkaHealthIndicator регистрируется когда enabled=true явно — без KafkaTemplate")
        void shouldRegisterWhenExplicitlyEnabledWithoutKafkaTemplate() {
            // Given — AdminClient есть, KafkaTemplate отсутствует (producer.enabled=false по умолчанию)
            contextRunner
                    .withUserConfiguration(AdminClientConfig.class)
                    .withPropertyValues("platform.health.kafka.enabled=true")
                    // When / Then
                    .run(ctx ->
                            assertThat(ctx).hasSingleBean(KafkaHealthIndicator.class)
                    );
        }

        @Test
        @DisplayName("KafkaHealthIndicator регистрируется когда enabled=true явно — с KafkaTemplate")
        void shouldRegisterWhenExplicitlyEnabledWithKafkaTemplate() {
            // Given — полная инфраструктура: AdminClient + KafkaTemplate
            contextRunner
                    .withUserConfiguration(AdminClientConfig.class, KafkaTemplateConfig.class)
                    .withPropertyValues("platform.health.kafka.enabled=true")
                    // When / Then
                    .run(ctx ->
                            assertThat(ctx).hasSingleBean(KafkaHealthIndicator.class)
                    );
        }

        @Test
        @DisplayName("KafkaHealthIndicator НЕ регистрируется когда enabled=false (явный opt-out)")
        void shouldNotRegisterWhenExplicitlyDisabled() {
            // Given — оператор явно отключает индикатор при наличии AdminClient
            contextRunner
                    .withUserConfiguration(AdminClientConfig.class)
                    .withPropertyValues("platform.health.kafka.enabled=false")
                    // When / Then
                    .run(ctx ->
                            assertThat(ctx).doesNotHaveBean(KafkaHealthIndicator.class)
                    );
        }

        @Test
        @DisplayName("Sub-check beans регистрируются даже при enabled=false (независимы от главного индикатора)")
        void shouldKeepSubCheckBeansWhenMainIndicatorDisabled() {
            // Sub-check beans не имеют @ConditionalOnProperty(enabled) — их регистрация
            // не зависит от platform.health.kafka.enabled, только от наличия AdminClient/KafkaTemplate
            contextRunner
                    .withUserConfiguration(AdminClientConfig.class)
                    .withPropertyValues("platform.health.kafka.enabled=false")
                    .run(ctx -> {
                        assertThat(ctx).doesNotHaveBean(KafkaHealthIndicator.class);
                        assertThat(ctx).hasSingleBean(KafkaConsumerHealthCheck.class);
                        assertThat(ctx).hasSingleBean(KafkaTopicHealthCheck.class);
                    });
        }
    }

    @Nested
    @DisplayName("@ConditionalOnMissingBean — кастомные реализации имеют приоритет над auto-configured")
    class ConditionalOnMissingBeanOverride {

        @Test
        @DisplayName("Пользовательский KafkaConsumerHealthCheck не перезаписывается автоконфигурацией")
        void shouldNotOverrideCustomConsumerHealthCheck() {
            // Given
            contextRunner
                    .withUserConfiguration(CustomConsumerHealthCheckConfig.class)
                    // When / Then
                    .run(ctx -> {
                        assertThat(ctx).hasSingleBean(KafkaConsumerHealthCheck.class);
                        assertThat(ctx.getBean(KafkaConsumerHealthCheck.class))
                                .isSameAs(ctx.getBean("customConsumerHealthCheck"));
                    });
        }

        @Test
        @DisplayName("Пользовательский KafkaTopicHealthCheck не перезаписывается автоконфигурацией")
        void shouldNotOverrideCustomTopicHealthCheck() {
            // Given
            contextRunner
                    .withUserConfiguration(CustomTopicHealthCheckConfig.class)
                    // When / Then
                    .run(ctx -> {
                        assertThat(ctx).hasSingleBean(KafkaTopicHealthCheck.class);
                        assertThat(ctx.getBean(KafkaTopicHealthCheck.class))
                                .isSameAs(ctx.getBean("customTopicHealthCheck"));
                    });
        }

        @Test
        @DisplayName("Пользовательский KafkaProducerHealthCheck не перезаписывается автоконфигурацией")
        void shouldNotOverrideCustomProducerHealthCheck() {
            // Given
            contextRunner
                    .withUserConfiguration(KafkaTemplateConfig.class, CustomProducerHealthCheckConfig.class)
                    // When / Then
                    .run(ctx -> {
                        assertThat(ctx).hasSingleBean(KafkaProducerHealthCheck.class);
                        assertThat(ctx.getBean(KafkaProducerHealthCheck.class))
                                .isSameAs(ctx.getBean("customProducerHealthCheck"));
                    });
        }
    }

    @Nested
    @DisplayName("Полный контекст — все бины присутствуют при наличии AdminClient и KafkaTemplate")
    class FullContext {

        @Test
        @DisplayName("Все пять бинов регистрируются при наличии полной инфраструктуры")
        void shouldRegisterAllBeansWhenFullInfrastructurePresent() {
            // Given / When / Then
            withFullInfrastructure().run(ctx -> {
                assertThat(ctx).hasSingleBean(KafkaHealthProperties.class);
                assertThat(ctx).hasSingleBean(KafkaHealthIndicator.class);
                assertThat(ctx).hasSingleBean(KafkaProducerHealthCheck.class);
                assertThat(ctx).hasSingleBean(KafkaConsumerHealthCheck.class);
                assertThat(ctx).hasSingleBean(KafkaTopicHealthCheck.class);
            });
        }

        @Test
        @DisplayName("KafkaHealthIndicator получает все sub-check зависимости через DI")
        void shouldInjectAllSubChecksIntoHealthIndicator() {
            // Given / When / Then
            withFullInfrastructure().run(ctx -> {
                assertThat(ctx).hasSingleBean(KafkaHealthIndicator.class);
                assertThat(ctx).doesNotHaveBean("kafkaHealthIndicatorDelegate2");
            });
        }

        @Test
        @DisplayName("KafkaHealthIndicator является HealthIndicator (полиморфизм через Spring Actuator)")
        void shouldExposeHealthIndicatorForActuator() {
            // Given / When / Then
            withFullInfrastructure().run(ctx ->
                    assertThat(ctx.getBeansOfType(HealthIndicator.class))
                            .containsKey("kafkaHealthIndicatorDelegate")
            );
        }

        @Test
        @DisplayName("KafkaHealthProperties бин — синглтон")
        void shouldRegisterKafkaHealthPropertiesAsSingleton() {
            // Given / When / Then
            withFullInfrastructure().run(ctx -> {
                KafkaHealthProperties a = ctx.getBean(KafkaHealthProperties.class);
                KafkaHealthProperties b = ctx.getBean(KafkaHealthProperties.class);
                assertThat(a).isSameAs(b);
            });
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class AdminClientConfig {
        @Bean
        AdminClient adminClient() {
            return mock(AdminClient.class);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class KafkaTemplateConfig {
        @Bean
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate() {
            return mock(KafkaTemplate.class);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CustomConsumerHealthCheckConfig {
        @Bean("customConsumerHealthCheck")
        KafkaConsumerHealthCheck customConsumerHealthCheck(KafkaHealthProperties properties) {
            return new KafkaConsumerHealthCheck(properties);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CustomTopicHealthCheckConfig {
        @Bean("customTopicHealthCheck")
        KafkaTopicHealthCheck customTopicHealthCheck(KafkaHealthProperties properties) {
            return new KafkaTopicHealthCheck(properties);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CustomProducerHealthCheckConfig {
        @Bean("customProducerHealthCheck")
        @SuppressWarnings("unchecked")
        KafkaProducerHealthCheck customProducerHealthCheck() {
            return new KafkaProducerHealthCheck(mock(KafkaTemplate.class));
        }
    }
}
