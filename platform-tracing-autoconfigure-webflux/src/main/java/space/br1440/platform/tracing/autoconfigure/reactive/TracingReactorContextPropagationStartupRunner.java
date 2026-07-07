package space.br1440.platform.tracing.autoconfigure.reactive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;

/**
 * Стартовый WARN при отключённой автоматической Reactor context propagation.
 * <p>
 * Для WebFlux trace/MDC на {@code publishOn}/{@code subscribeOn} требуется
 * {@code spring.reactor.context-propagation=AUTO} (default в Spring Boot 3.x).
 */
final class TracingReactorContextPropagationStartupRunner implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(TracingReactorContextPropagationStartupRunner.class);

    private final String contextPropagationMode;

    TracingReactorContextPropagationStartupRunner(Environment environment) {
        this.contextPropagationMode = environment.getProperty("spring.reactor.context-propagation", "AUTO");
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!"AUTO".equalsIgnoreCase(contextPropagationMode)) {
            log.warn("spring.reactor.context-propagation={} — автоматический проброс trace/MDC через Reactor "
                    + "scheduler switch отключён или ограничен. Для WebFlux рекомендуется AUTO "
                    + "(см. TracingReactorEagerInitConfiguration).", contextPropagationMode);
        }
    }
}
