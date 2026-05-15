package space.br1440.platform.errorhandling.autoconfigure.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.Nullable;
import space.br1440.platform.errorhandling.extension.RootCauseExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.core.strategy.cause.storage.ClickHouseExceptionStrategy;
import space.br1440.platform.errorhandling.core.strategy.cause.storage.ConcurrencyExceptionStrategy;
import space.br1440.platform.errorhandling.core.strategy.cause.storage.DataAccessExceptionStrategy;
import space.br1440.platform.errorhandling.core.strategy.cause.storage.HibernateExceptionStrategy;
import space.br1440.platform.errorhandling.core.strategy.cause.storage.HikariCpExceptionStrategy;
import space.br1440.platform.errorhandling.core.strategy.cause.storage.JooqExceptionStrategy;
import space.br1440.platform.errorhandling.core.strategy.cause.storage.PostgresExceptionStrategy;
import space.br1440.platform.errorhandling.core.strategy.cause.storage.TransactionExceptionStrategy;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
public class ErrorHandlingStorageCoreConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "concurrencyExceptionStrategy")
    @ConditionalOnClass(name = "org.springframework.dao.DataAccessException")
    public RootCauseExceptionHandlingStrategy concurrencyExceptionStrategy(StackHintExtractor stackHintExtractor) {
        return new ConcurrencyExceptionStrategy(stackHintExtractor);
    }

    @Bean
    @ConditionalOnMissingBean(name = "transactionExceptionStrategy")
    @ConditionalOnClass(name = "org.springframework.dao.DataAccessException")
    public RootCauseExceptionHandlingStrategy transactionExceptionStrategy(StackHintExtractor stackHintExtractor) {
        return new TransactionExceptionStrategy(stackHintExtractor);
    }

    @Bean
    @ConditionalOnMissingBean(name = "dataAccessExceptionStrategy")
    @ConditionalOnClass(name = "org.springframework.dao.DataAccessException")
    public RootCauseExceptionHandlingStrategy dataAccessExceptionStrategy(StackHintExtractor stackHintExtractor) {
        return new DataAccessExceptionStrategy(stackHintExtractor);
    }

    @Bean
    @ConditionalOnMissingBean(name = "hibernateExceptionStrategy")
    @ConditionalOnClass(name = "org.hibernate.HibernateException")
    public RootCauseExceptionHandlingStrategy hibernateExceptionStrategy(StackHintExtractor stackHintExtractor) {
        return new HibernateExceptionStrategy(stackHintExtractor);
    }

    @Bean
    @ConditionalOnMissingBean(name = "jooqExceptionStrategy")
    @ConditionalOnClass(name = "org.jooq.exception.DataAccessException")
    public RootCauseExceptionHandlingStrategy jooqExceptionStrategy(StackHintExtractor stackHintExtractor) {
        return new JooqExceptionStrategy(stackHintExtractor);
    }

    @Bean
    @ConditionalOnMissingBean(name = "postgresExceptionStrategy")
    @ConditionalOnClass(name = {
            "org.postgresql.util.PSQLException",
            "org.springframework.jdbc.datasource.DataSourceUtils"
    })
    public RootCauseExceptionHandlingStrategy postgresExceptionStrategy(ErrorHandlingProperties properties,
                                                                        StackHintExtractor stackHintExtractor) {
        return new PostgresExceptionStrategy(Boolean.TRUE.equals(properties.getPostgresIncludeDetail()), stackHintExtractor);
    }

    @Bean
    @ConditionalOnMissingBean(name = "clickHouseExceptionStrategy")
    @ConditionalOnClass(name = "com.clickhouse.client.api.ClickHouseException")
    public RootCauseExceptionHandlingStrategy clickHouseExceptionStrategy(StackHintExtractor stackHintExtractor) {
        return new ClickHouseExceptionStrategy(stackHintExtractor);
    }

    @Bean
    @ConditionalOnMissingBean(name = "hikariCpExceptionStrategy")
    @ConditionalOnClass(name = "com.zaxxer.hikari.HikariDataSource")
    public RootCauseExceptionHandlingStrategy hikariCpExceptionStrategy(@Nullable DataSource dataSource,
                                                                        StackHintExtractor stackHintExtractor) {
        return new HikariCpExceptionStrategy(dataSource, stackHintExtractor);
    }
}
