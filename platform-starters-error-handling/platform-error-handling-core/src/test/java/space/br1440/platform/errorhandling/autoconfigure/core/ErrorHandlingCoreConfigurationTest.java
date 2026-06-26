package space.br1440.platform.errorhandling.autoconfigure.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.core.enricher.RemoteServiceDomainEnricher;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.ErrorEntryFactory;
import space.br1440.platform.errorhandling.model.error_detail.dto.ErrorInfoDetailDTO;

import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ErrorHandlingCoreConfiguration — авто-конфигурация")
class ErrorHandlingCoreConfigurationTest {

    /**
     * Локальный домен после {@link ErrorEntryFactory#create}: в актуальном web-error-model
     * домен дублируется в {@link ErrorEntry#getDomain()}; в устаревших сборках 2.0.0 из ~/.m2
     * поле entry оставалось null — тогда единственный источник правды {@link ErrorEntryFactory#getDomain()}.
     */
    private static String effectiveLocalDomain(ErrorEntryFactory factory, ErrorEntry entry) {
        String fromEntry = entry.getDomain();
        return fromEntry != null ? fromEntry : factory.getDomain();
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(UserConfigurations.of(
                    PropertiesEnabler.class,
                    ErrorHandlingCoreConfiguration.class));

    @EnableConfigurationProperties(ErrorHandlingProperties.class)
    @Configuration(proxyBeanMethods = false)
    static class PropertiesEnabler {
    }

    @Configuration(proxyBeanMethods = false)
    static class TracingSuppliersConfig {

        @Bean
        public Supplier<String> platformLocalServiceNameProvider() {
            return () -> "order-service";
        }

        @Bean
        public Supplier<Optional<String>> platformRemoteServiceNameProvider() {
            return () -> Optional.of("billing-service");
        }
    }

    // -----------------------------------------------------------------
    // 1. Без Tracing-стартера
    // -----------------------------------------------------------------

    @Test
    @DisplayName("без Tracing-стартера: factory отдаёт DOMAIN_UNSPECIFIED, enricher — no-op")
    void withoutTracingStarter_factoryReturnsUnspecified_andEnricherIsNoop() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(ErrorEntryFactory.class);
            assertThat(context).hasSingleBean(RemoteServiceDomainEnricher.class);

            // Без tracing-поставщика: при старте подставляется DOMAIN_UNSPECIFIED.
            ErrorEntryFactory factory = context.getBean(ErrorEntryFactory.class);
            ErrorEntry entry = factory.create(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "msg");
            assertThat(effectiveLocalDomain(factory, entry)).isEqualTo(ErrorInfoDetailDTO.DOMAIN_UNSPECIFIED);
        });
    }

    // -----------------------------------------------------------------
    // 2. С Tracing-supplier'ами
    // -----------------------------------------------------------------

    @Test
    @DisplayName("с Tracing-supplier'ами: factory подставляет local, enricher активен")
    void withTracingSuppliers_factoryUsesLocal_andEnricherIsRegistered() {
        runner
                .withConfiguration(UserConfigurations.of(TracingSuppliersConfig.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(ErrorEntryFactory.class);
                    assertThat(context).hasSingleBean(RemoteServiceDomainEnricher.class);

                    ErrorEntryFactory factory = context.getBean(ErrorEntryFactory.class);
                    ErrorEntry entry = factory.create(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "msg");
                    assertThat(effectiveLocalDomain(factory, entry)).isEqualTo("order-service");
                });
    }

    // -----------------------------------------------------------------
    // 3. С выключенным toggle — enricher не регистрируется
    // -----------------------------------------------------------------

    @Test
    @DisplayName("populate-remote-domain-from-tracing=false: RemoteServiceDomainEnricher не создаётся")
    void disabledToggle_enricherIsNotRegistered() {
        runner
                .withConfiguration(UserConfigurations.of(TracingSuppliersConfig.class))
                .withPropertyValues("platform.error-handling.tracing.populate-remote-domain-from-tracing=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(ErrorEntryFactory.class);
                    assertThat(context).doesNotHaveBean(RemoteServiceDomainEnricher.class);

                    // factory всё ещё работает с Tracing-supplier'ом
                    ErrorEntryFactory factory = context.getBean(ErrorEntryFactory.class);
                    ErrorEntry entry = factory.create(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "msg");
                    assertThat(effectiveLocalDomain(factory, entry)).isEqualTo("order-service");
                });
    }
}
