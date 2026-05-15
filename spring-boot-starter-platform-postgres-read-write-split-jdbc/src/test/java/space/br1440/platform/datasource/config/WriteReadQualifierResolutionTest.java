package space.br1440.platform.datasource.config;

import space.br1440.platform.datasource.configuration.PostgresClusterDataSourceProperties;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.datasource.config.factory.ReadDataSourceFactory;
import space.br1440.platform.datasource.config.factory.WriteDataSourceFactory;
import space.br1440.platform.datasource.configuration.support.ReadStrategyStartupLogger;
import space.br1440.platform.datasource.configuration.support.RoutingStartupValidator;
import space.br1440.platform.datasource.metrics.RoutingMetricNames;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Закрепляет контракт публичных имён бинов {@code writeDataSource} и {@code readDataSource},
 * а также дефолтные имена HikariCP-пулов {@code postgres-cluster-write-pool} и {@code postgres-cluster-read-pool}.
 *
 * <p>Тест умышленно <b>не использует</b> {@code @SpringBootTest}: резолюция по
 * {@code @Qualifier} — это чистый Spring bean-factory концепт, и полный контекст
 * Boot'a тут избыточен. Достаточно {@link AnnotationConfigApplicationContext}
 * с минимальной @Configuration.
 *
 * <p>Этот тест — регрессионная страховка: переименование имени бина или пула
 * где-либо в коде ломает потребителей стартера (они используют
 * {@code @Qualifier("writeDataSource")} / {@code @Qualifier("readDataSource")}).
 */
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

    /**
     * Минимальная тестовая конфигурация: только фабрики пулов и поддерживающие бины.
     * Не поднимает Spring Boot auto-configuration, не требует YAML, не касается web/actuator.
     * Пулы создаются с {@code initializationFailTimeout=-1} — без попытки подключения к БД.
     */
    @Configuration
    static class MinimalRoutingTestConfig {

        @Bean
        PostgresClusterDataSourceProperties props() {
            // Активация autoconfig теперь идёт через env PG_TOPOLOGY=DIRECT, а не через property,
            // поэтому setEnabled здесь больше не нужен — заполняем только данные кластера.
            PostgresClusterDataSourceProperties p = new PostgresClusterDataSourceProperties();
            p.setNodes(java.util.List.of("localhost:5432"));
            p.setDatabase("testdb");
            p.getCredentials().setUsername("user");
            p.getCredentials().setPassword("pass");
            return p;
        }

        @Bean
        HikariRoutingProperties hikariProps() {
            // HikariCP-специфичные настройки пулов живут в отдельном бине стартера.
            // initializationFailTimeout=-1 — чтобы Hikari не открывал соединение к реальной БД.
            HikariRoutingProperties h = new HikariRoutingProperties();
            h.getWrite().setInitializationFailTimeout(-1);
            h.getRead().setInitializationFailTimeout(-1);
            return h;
        }

        @Bean
        RoutingStartupValidator validator() {
            return new RoutingStartupValidator();
        }

        @Bean
        ReadStrategyStartupLogger logger() {
            return new ReadStrategyStartupLogger();
        }

        @Bean
        WriteDataSourceFactory writeFactory(RoutingStartupValidator v, ReadStrategyStartupLogger l) {
            return new WriteDataSourceFactory(v, l);
        }

        @Bean
        ReadDataSourceFactory readFactory() {
            return new ReadDataSourceFactory();
        }

        @Bean
        HikariDataSource writeDataSource(WriteDataSourceFactory f,
                                         PostgresClusterDataSourceProperties p,
                                         HikariRoutingProperties h) {
            return f.build(p, h);
        }

        @Bean
        HikariDataSource readDataSource(ReadDataSourceFactory f,
                                        PostgresClusterDataSourceProperties p,
                                        HikariRoutingProperties h) {
            return f.build(p, h);
        }

        @Bean
        Consumer consumer(@Qualifier("writeDataSource") HikariDataSource write,
                          @Qualifier("readDataSource") HikariDataSource read) {
            return new Consumer(write, read);
        }
    }

    /**
     * Потребитель, имитирующий конечного пользователя стартера: получает два
     * {@link HikariDataSource} строго по {@code @Qualifier}.
     */
    static class Consumer {
        final HikariDataSource writeDs;
        final HikariDataSource readDs;

        Consumer(HikariDataSource writeDs, HikariDataSource readDs) {
            this.writeDs = writeDs;
            this.readDs = readDs;
        }
    }
}
