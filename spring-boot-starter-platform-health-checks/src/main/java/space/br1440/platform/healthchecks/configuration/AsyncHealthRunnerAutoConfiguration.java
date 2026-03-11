package space.br1440.platform.healthchecks.configuration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.autoconfigure.health.HealthEndpointAutoConfiguration;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.actuate.health.NamedContributor;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.healthchecks.async.AsyncHealthIndicator;
import space.br1440.platform.healthchecks.async.HealthRunnerHolder;
import space.br1440.platform.healthchecks.async.SchedulingThreadPoolExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(HealthContributorRegistry.class)
@ConditionalOnBean(HealthContributorRegistry.class)
@AutoConfigureAfter(HealthEndpointAutoConfiguration.class)
public class AsyncHealthRunnerAutoConfiguration implements InitializingBean, DisposableBean {

    private static final int DEFAULT_POOL_MAX_SIZE = 10;
    private static final long DEFAULT_POOL_KEEP_ALIVE = 10L;
    private static final long DEFAULT_HARD_TIMEOUT_MS = 90_000L;

    private final ObjectProvider<HealthContributorRegistry> registryProvider;

    private SchedulingThreadPoolExecutor executor;

    public AsyncHealthRunnerAutoConfiguration(ObjectProvider<HealthContributorRegistry> registryProvider) {
        this.registryProvider = registryProvider;
    }

    @Override
    public void afterPropertiesSet() {
        HealthContributorRegistry registry = registryProvider.getObject();
        List<AsyncHealthIndicator> runners = new ArrayList<>();

        for (NamedContributor<?> namedContributor : registry) {
            Object contributor = namedContributor.getContributor();
            resolveRunner(contributor)
                    .filter(AsyncHealthIndicator::isAsyncEnabled)
                    .ifPresent(runner -> {
                        log.info("AsyncHealthRunner scheduled: {}", runner);
                        runners.add(runner);
                    });
        }

        if (!runners.isEmpty()) {
            executor = new SchedulingThreadPoolExecutor(DEFAULT_POOL_MAX_SIZE, DEFAULT_POOL_KEEP_ALIVE, DEFAULT_HARD_TIMEOUT_MS);
            runners.forEach(executor::schedule);
        }
    }

    private static Optional<AsyncHealthIndicator> resolveRunner(Object contributor) {
        return switch (contributor) {
            case AsyncHealthIndicator runner -> Optional.of(runner);
            case HealthRunnerHolder holder -> holder.getRunner();
            default -> Optional.empty();
        };
    }

    @Override
    public void destroy() {
        if (executor != null) {
            executor.shutdown();
            executor.awaitGracefulTermination();
        }
    }
}
