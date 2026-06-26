package space.br1440.platform.datasource.postgres.api.properties;

import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Группа настроек HikariCP-пулов
 * Биндится из {@value PgClusterProperties#PREFIX}.pool.{write,read}.*.
 */
public record PoolGroupProperties(
        @NestedConfigurationProperty @DefaultValue PoolProperties write,
        @NestedConfigurationProperty @DefaultValue PoolProperties read
) {

    public PoolGroupProperties {
        write = (write == null) ? PoolProperties.defaults() : write;
        read = (read == null) ? PoolProperties.defaults() : read;
    }
}
