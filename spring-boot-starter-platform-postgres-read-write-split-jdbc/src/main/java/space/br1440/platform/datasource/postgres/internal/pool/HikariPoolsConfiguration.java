package space.br1440.platform.datasource.postgres.internal.pool;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.datasource.postgres.api.properties.PgClusterProperties;

@Configuration(proxyBeanMethods = false)
@ConditionalOnMissingBean(name = "dataSource")
public class HikariPoolsConfiguration {

    @Bean(name = "writeDataSource")
    @ConditionalOnMissingBean(name = "writeDataSource")
    public HikariDataSource writeDataSource(WriteDataSourceFactory factory,
                                            PgClusterProperties properties) {
        return factory.build(properties, properties.pool());
    }

    @Bean(name = "readDataSource")
    @ConditionalOnMissingBean(name = "readDataSource")
    public HikariDataSource readDataSource(ReadDataSourceFactory factory,
                                           PgClusterProperties properties) {
        return factory.build(properties, properties.pool());
    }
}
