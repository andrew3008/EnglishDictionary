package space.br1440.platform.tracing.autoconfigure.reactive;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;

@Slf4j
final class TracingReactorContextPropagationStartupRunner implements SmartInitializingSingleton {

    private final String contextPropagationMode;

    TracingReactorContextPropagationStartupRunner(Environment environment) {
        this.contextPropagationMode = environment.getProperty("spring.reactor.context-propagation", "AUTO");
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!"AUTO".equalsIgnoreCase(contextPropagationMode)) {
            log.warn("spring.reactor.context-propagation={} — automatic trace/MDC propagation across Reactor "
                    + "scheduler switches is disabled or limited. For WebFlux, AUTO is recommended "
                    + "(see TracingReactorEagerInitConfiguration).", contextPropagationMode);
        }
    }
}
