package space.br1440.platform.datasource.postgres.internal.bootstrap;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

public final class PgPropertySourceRegistrar {

    public static final String PROPERTY_SOURCE_NAME = "postgresClusterEnvDefaults";

    public void register(ConfigurableEnvironment environment, Map<String, Object> canonical) {
        if (canonical.isEmpty()) {
            return;
        }

        environment.getPropertySources()
                .addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, canonical));
    }
}
