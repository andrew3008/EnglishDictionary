package space.br1440.platform.datasource.postgres.internal.integration.health;

record HealthCheckResult(boolean up, String pgRole, FailureType failureType) {

    static HealthCheckResult success(String pgRole) {
        return new HealthCheckResult(true, pgRole, null);
    }

    static HealthCheckResult failed(FailureType failureType) {
        return new HealthCheckResult(false, null, failureType);
    }
}
