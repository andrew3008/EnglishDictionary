package space.br1440.platform.datasource.postgres.internal.integration.health;

import space.br1440.platform.datasource.postgres.api.properties.ReadMode;

record ProbeResults(HealthCheckResult write, HealthCheckResult read, ReadMode readMode) {

    static ProbeResults failedBoth(FailureType failureType, ReadMode readMode) {
        HealthCheckResult failed = HealthCheckResult.failed(failureType);
        return new ProbeResults(failed, failed, readMode);
    }
}
