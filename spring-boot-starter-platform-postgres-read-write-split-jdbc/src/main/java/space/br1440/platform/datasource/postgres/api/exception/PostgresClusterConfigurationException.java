package space.br1440.platform.datasource.postgres.api.exception;

import space.br1440.platform.errorhandling.startup.PlatformConfigurationException;

public class PostgresClusterConfigurationException extends PlatformConfigurationException {

    public PostgresClusterConfigurationException(String message, String action) {
        super(message, action);
    }
}
