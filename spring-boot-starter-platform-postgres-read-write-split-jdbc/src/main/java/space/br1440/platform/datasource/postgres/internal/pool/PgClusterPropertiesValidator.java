package space.br1440.platform.datasource.postgres.internal.pool;

import org.apache.commons.lang3.StringUtils;
import space.br1440.platform.datasource.postgres.api.properties.DataSourceRoutingMode;
import space.br1440.platform.datasource.postgres.api.properties.PgClusterProperties;
import space.br1440.platform.datasource.postgres.api.exception.PostgresClusterConfigurationException;

public final class PgClusterPropertiesValidator {

    public void validate(PgClusterProperties props) {
        if (props.datasourceRoutingMode() != DataSourceRoutingMode.CLIENT) {
            return;
        }

        if (StringUtils.isBlank(props.database())) {
            throw new PostgresClusterConfigurationException(
                    "CLIENT mode requires a database name but none was resolved.",
                    """
                    Set\s""" + PgClusterProperties.PREFIX + """
                    .database via the PG_DATABASE env var
                    OR application.yml. Current value: null/blank.""".stripIndent());
        }

        if (props.nodes() == null || props.nodes().isEmpty()) {
            throw new PostgresClusterConfigurationException(
                    "CLIENT mode requires at least one cluster node but none was resolved.",
                    """
                    Set\s""" + PgClusterProperties.PREFIX + """
                    .nodes via the PG_CLUSTER_NODES env var
                    (CSV host:port) OR application.yml. Current value: empty.""".stripIndent());
        }

        validatePasswordlessPair(props);
    }

    private static void validatePasswordlessPair(PgClusterProperties props) {
        PgClusterProperties.Credentials credentials = props.credentials();

        boolean hasUser = StringUtils.isNotBlank(credentials.username());
        boolean hasPassword = StringUtils.isNotBlank(credentials.password());
        if (hasUser != hasPassword) {
            throw new PostgresClusterConfigurationException(
                    "Half-configured credentials: username and password must be set together.",
                    """
                    Set BOTH\s""" + PgClusterProperties.PREFIX + """
                    .credentials.username AND .password
                    (via PG_USERNAME/PG_PASSWORD or application.yml), or NEITHER for passwordless/trust mode.
                    Current: username %s, password %s."""
                            .formatted(present(hasUser), present(hasPassword)).stripIndent());
        }
    }

    private static String present(boolean value) {
        return value ? "set" : "blank";
    }
}
