package space.br1440.platform.datasource.postgres;

import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import space.br1440.platform.datasource.postgres.api.properties.PgClusterProperties;
import space.br1440.platform.datasource.postgres.internal.bootstrap.PgEnvMapper;
import space.br1440.platform.datasource.postgres.internal.bootstrap.PgEnvReader;
import space.br1440.platform.datasource.postgres.internal.bootstrap.PgEnvReader.RawPgEnv;
import space.br1440.platform.datasource.postgres.api.exception.PostgresClusterConfigurationException;
import space.br1440.platform.datasource.postgres.internal.bootstrap.PgPropertySourceRegistrar;

import java.util.Map;

public final class PgClusterEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String MSG_PASSWORDLESS_PAIR =
            "PG_USERNAME and PG_PASSWORD must be set together (raw env passwordless pair).";

    static final String ACTION_PASSWORDLESS_PAIR = """
            Either set BOTH PG_USERNAME and PG_PASSWORD, or NEITHER (passwordless/trust-auth mode).
            Materialize both keys via ESO/Vault Agent into the pod's environment.""".stripIndent();

    private final Log logger;
    private final PgEnvMapper mapper;
    private final PgPropertySourceRegistrar registrar;

    public PgClusterEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.logger = logFactory.getLog(PgClusterEnvironmentPostProcessor.class);
        this.mapper = new PgEnvMapper();
        this.registrar = new PgPropertySourceRegistrar();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        RawPgEnv env = new PgEnvReader(environment).read();

        validateRawPasswordlessPair(env);

        Map<String, Object> canonical = mapper.toCanonical(env);

        String conflictKey = PgClusterProperties.PREFIX + ".__pgSchemaConflict";
        if (canonical.remove(conflictKey) != null) {
            logger.warn("[postgres-cluster] PG_SCHEMA and 'currentSchema' in PG_CONNECTION_PROPERTIES " +
                    "are both set. PG_SCHEMA wins within the EPP canonical map. " +
                    "Remove one of the two sources to eliminate this warning.");
        }

        if (canonical.isEmpty()) {
            if (logger.isDebugEnabled()) {
                logger.debug("[postgres-cluster] EPP no-op: no PG_* env vars present");
            }

            return;
        }

        registrar.register(environment, canonical);

        if (logger.isInfoEnabled()) {
            logger.info("[postgres-cluster] EPP injected " + canonical.size()
                    + " canonical default(s) into '" + PgPropertySourceRegistrar.PROPERTY_SOURCE_NAME
                    + "' (lowest precedence)");
        }
    }

    private static void validateRawPasswordlessPair(RawPgEnv env) {
        boolean hasUser = (env.username() != null);
        boolean hasPassword = (env.password() != null);
        if (hasUser != hasPassword) {
            throw new PostgresClusterConfigurationException(MSG_PASSWORDLESS_PAIR, ACTION_PASSWORDLESS_PAIR);
        }
    }
}
