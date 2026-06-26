package space.br1440.platform.datasource.postgres.internal.proxy.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.datasource.postgres.internal.pool.ReadDataSourceFactory;
import space.br1440.platform.datasource.postgres.internal.pool.WriteDataSourceFactory;
import space.br1440.platform.datasource.postgres.internal.integration.logging.ReadStrategyStartupLogger;
import space.br1440.platform.datasource.postgres.internal.integration.logging.StartupDiagnosticLogger;
import space.br1440.platform.datasource.postgres.api.properties.PgClusterProperties;
import space.br1440.platform.datasource.postgres.internal.pool.PgClusterPropertiesValidator;

@Configuration(proxyBeanMethods = false)
public class DataSourceFactoriesConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PgClusterPropertiesValidator pgClusterPropertiesValidator() {
        return new PgClusterPropertiesValidator();
    }

    @Bean
    @ConditionalOnMissingBean
    public ReadStrategyStartupLogger readStrategyStartupLogger() {
        return new ReadStrategyStartupLogger();
    }

    @Bean
    @ConditionalOnMissingBean
    public WriteDataSourceFactory writeDataSourceFactory(PgClusterPropertiesValidator validator,
                                                         ReadStrategyStartupLogger logger) {
        return new WriteDataSourceFactory(validator, logger);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReadDataSourceFactory readDataSourceFactory() {
        return new ReadDataSourceFactory();
    }

    @Bean
    @ConditionalOnMissingBean
    public StartupDiagnosticLogger startupDiagnosticLogger(PgClusterProperties properties,
                                                           @Value("${" + PgClusterProperties.PREFIX + ".metrics.enabled:true}") boolean metricsEnabled) {
        boolean healthEnabled = isActuatorOnClasspath();
        return new StartupDiagnosticLogger(properties, healthEnabled, metricsEnabled);
    }

    private static boolean isActuatorOnClasspath() {
        try {
            Class.forName("org.springframework.boot.actuate.health.HealthIndicator");
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }
}
