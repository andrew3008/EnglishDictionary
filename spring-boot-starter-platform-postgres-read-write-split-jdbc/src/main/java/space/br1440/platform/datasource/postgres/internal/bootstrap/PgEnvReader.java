package space.br1440.platform.datasource.postgres.internal.bootstrap;

import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

public final class PgEnvReader {

    public static final String ENV_ROUTING_MODE = "PG_ROUTING_MODE";
    public static final String ENV_NODES = "PG_CLUSTER_NODES";
    public static final String ENV_DATABASE = "PG_DATABASE";
    public static final String ENV_SCHEMA = "PG_SCHEMA";
    public static final String ENV_USERNAME = "PG_USERNAME";
    public static final String ENV_PASSWORD = "PG_PASSWORD";
    public static final String ENV_CONNECTION_PROPERTIES = "PG_CONNECTION_PROPERTIES";

    private final Environment environment;

    public PgEnvReader(Environment environment) {
        this.environment = environment;
    }

    public RawPgEnv read() {
        return new RawPgEnv(
                trimToNull(environment.getProperty(ENV_ROUTING_MODE)),
                trimToNull(environment.getProperty(ENV_NODES)),
                trimToNull(environment.getProperty(ENV_DATABASE)),
                trimToNull(environment.getProperty(ENV_SCHEMA)),
                trimToNull(environment.getProperty(ENV_USERNAME)),
                trimToNull(environment.getProperty(ENV_PASSWORD)),
                trimToNull(environment.getProperty(ENV_CONNECTION_PROPERTIES))
        );
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }

    public record RawPgEnv(
            String routingMode,
            String nodes,
            String database,
            String schema,
            String username,
            String password,
            String connectionProperties
    ) {
    }
}
