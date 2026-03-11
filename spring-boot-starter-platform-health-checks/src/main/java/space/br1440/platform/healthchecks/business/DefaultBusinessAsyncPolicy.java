package space.br1440.platform.healthchecks.business;

import space.br1440.platform.healthchecks.async.AsyncHealthRefreshPolicy;

import java.time.Duration;

public final class DefaultBusinessAsyncPolicy implements AsyncHealthRefreshPolicy {

    @Override
    public boolean isEnabled() {
        return false; // бизнес-индикаторы по умолчанию синхронны
    }

    @Override
    public Duration getHealthCheckTimeout() {
        return Duration.ofSeconds(10);
    }

    @Override
    public Duration getRefreshInterval() {
        return Duration.ofSeconds(30);
    }

    @Override
    public Duration getStaleResultTtl() {
        return Duration.ofSeconds(120);
    }
}
