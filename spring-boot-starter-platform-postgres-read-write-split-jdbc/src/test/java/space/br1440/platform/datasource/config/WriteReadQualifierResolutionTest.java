package space.br1440.platform.datasource.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.datasource.postgres.internal.pool.ReadDataSourceFactory;
import space.br1440.platform.datasource.postgres.internal.pool.WriteDataSourceFactory;
import space.br1440.platform.datasource.postgres.internal.integration.logging.ReadStrategyStartupLogger;
import space.br1440.platform.datasource.postgres.api.properties.PgNode;
import space.br1440.platform.datasource.postgres.api.properties.DataSourceRoutingMode;
import space.br1440.platform.datasource.postgres.api.properties.PgClusterProperties;
import space.br1440.platform.datasource.postgres.api.properties.PoolGroupProperties;
import space.br1440.platform.datasource.postgres.api.properties.PoolProperties;
import space.br1440.platform.datasource.postgres.internal.pool.PgClusterPropertiesValidator;
import space.br1440.platform.datasource.postgres.internal.integration.metrics.RoutingMetricNames;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("writeDataSource/readDataSource qualifier + pool name contract")
class WriteReadQualifierResolutionTest {

    private AnnotationConfigApplicationContext ctx;

    @AfterEach
    void tearDown() {
        if (ctx != null) {
            ctx.close();
        }
    }

    @Test
    @DisplayName("@Qualifier(\"writeDataSource\") резолвится в пул с именем postgres-cluster-write-pool")
    void writeQualifierResolvesToWritePool() {
        ctx = bootstrap();
        Consumer consumer = ctx.getBean(Consumer.class);

        assertThat(consumer.writeDs).isNotNull();
        assertThat(consumer.writeDs.getPoolName()).isEqualTo(RoutingMetricNames.DEFAULT_WRITE_POOL_NAME);
        assertThat(consumer.writeDs.getPoolName()).isEqualTo("postgres-cluster-write-pool");
    }

    @Test
    @DisplayName("@Qualifier(\"readDataSource\") резолвится в пул с именем postgres-cluster-read-pool")
    void readQualifierResolvesToReadPool() {
        ctx = bootstrap();
        Consumer consumer = ctx.getBean(Consumer.class);

        assertThat(consumer.readDs).isNotNull();
        assertThat(consumer.readDs.getPoolName()).isEqualTo(RoutingMetricNames.DEFAULT_READ_POOL_NAME);
        assertThat(consumer.readDs.getPoolName()).isEqualTo("postgres-cluster-read-pool");
    }

    @Test
    @DisplayName("read-пул создаётся с readOnly=true (setReadOnly enforced в ReadDataSourceFactory)")
    void readPoolIsReadOnly() {
        ctx = bootstrap();
        Consumer consumer = ctx.getBean(Consumer.class);

        assertThat(consumer.readDs.isReadOnly()).isTrue();
    }

    @Test
    @DisplayName("write-пул НЕ readOnly по умолчанию")
    void writePoolIsNotReadOnly() {
        ctx = bootstrap();
        Consumer consumer = ctx.getBean(Consumer.class);

        assertThat(consumer.writeDs.isReadOnly()).isFalse();
    }

    private AnnotationConfigApplicationContext bootstrap() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(MinimalRoutingTestConfig.class);
        context.refresh();
        return context;
    }

    @Configuration
    static class MinimalRoutingTestConfig {

        @Bean
        PgClusterProperties props() {
            return new PgClusterProperties(
                    DataSourceRoutingMode.CLIENT,
                    java.util.List.of(new PgNode("localhost", 5432)),
                    "testdb",
                    new PgClusterProperties.Credentials("user", "pass"),
                    null,
                    null,
                    noConnectPool(),
                    null);
        }

        private static PoolGroupProperties noConnectPool() {
            PoolProperties write = poolWithNoConnect();
            PoolProperties read = poolWithNoConnect();
            return new PoolGroupProperties(write, read);
        }

        private static PoolProperties poolWithNoConnect() {
            PoolProperties d = PoolProperties.defaults();
            return new PoolProperties(
                    d.poolName(), d.maximumPoolSize(), d.minimumIdle(), d.connectionTimeout(),
                    d.maxLifetime(), d.keepaliveTime(), d.connectionTestQuery(),
                    d.leakDetectionThreshold(), -1L, d.writerValidationMode());
        }

        @Bean
        PgClusterPropertiesValidator validator() {
            return new PgClusterPropertiesValidator();
        }

        @Bean
        ReadStrategyStartupLogger logger() {
            return new ReadStrategyStartupLogger();
        }

        @Bean
        WriteDataSourceFactory writeFactory(PgClusterPropertiesValidator v, ReadStrategyStartupLogger l) {
            return new WriteDataSourceFactory(v, l);
        }

        @Bean
        ReadDataSourceFactory readFactory() {
            return new ReadDataSourceFactory();
        }

        @Bean
        HikariDataSource writeDataSource(WriteDataSourceFactory f, PgClusterProperties p) {
            return f.build(p, p.pool());
        }

        @Bean
        HikariDataSource readDataSource(ReadDataSourceFactory f, PgClusterProperties p) {
            return f.build(p, p.pool());
        }

        @Bean
        Consumer consumer(@Qualifier("writeDataSource") HikariDataSource write,
                          @Qualifier("readDataSource") HikariDataSource read) {
            return new Consumer(write, read);
        }
    }

    static class Consumer {
        final HikariDataSource writeDs;
        final HikariDataSource readDs;

        Consumer(HikariDataSource writeDs, HikariDataSource readDs) {
            this.writeDs = writeDs;
            this.readDs = readDs;
        }
    }
}
